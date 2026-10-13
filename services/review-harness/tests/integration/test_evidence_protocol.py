import asyncio
import json
import subprocess
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.application.evidence import EvidenceService
from review_harness.domain.model import HarnessError, canonical, sha256
from review_harness.domain.review import CodeReference, EvidenceCandidate
from tests.integration.test_deepseek_adapter import response
from tests.integration.test_lifecycle import client_for, settings_for
from tests.integration.test_tools import setup_gateway
from tests.legacy_runtime_fixture import legacy_protocol_app as create_app
from tests.snapshot_fixture import publish_snapshot


@pytest.mark.parametrize("case", ["literal", "real-git", "crlf", "zero-count", "paged"])
async def test_diff_header_like_code_is_projected_and_accepted(
    database, tmp_path, payload, workspaces, case
):
    payload["snapshot"]["snapshotId"] = str(uuid4())
    files = {"a.java": b"++ new\nordinary\n"}
    diff = b"diff --git a/a.java b/a.java\n--- a/a.java\n+++ b/a.java\n@@ -1,2 +1,2 @@\n--- old\n+++ new\n ordinary\n"
    expected = {"a.java": [(5, 7)]}
    if case == "crlf":
        diff = diff.replace(b"\n", b"\r\n")
    elif case == "paged":
        diff = diff.replace(b"--- old\n", b"--- old" + b"x" * 3000 + b"\n")
    elif case == "zero-count":
        diff = b"diff --git a/a.java b/a.java\n--- a/a.java\n+++ b/a.java\n@@ -0,0 +1 @@\n+++ new\n@@ -2 +2,0 @@\n--- old\n\\ No newline at end of file\n"
        expected = {"a.java": [(5, 5), (7, 7)]}
    elif case == "real-git":
        repository = tmp_path / "git-probe"
        repository.mkdir()

        def git(*arguments):
            return subprocess.run(
                [
                    "git",
                    "-c",
                    "core.autocrlf=false",
                    "-c",
                    "core.fsmonitor=false",
                    *arguments,
                ],
                cwd=repository,
                check=True,
                capture_output=True,
            ).stdout

        git("init")
        # Text-block contents are legitimate Java source, including tokens that
        # resemble patch headers. A distant edit forces a second hunk.
        old = (
            'class A {\nString text = """\n-- old\nordinary\n""";\n'
            + "// unchanged\n" * 10
            + "// before\n}"
        )
        new = old.replace("-- old", "++ new").replace("// before", "// after")
        (repository / "a.java").write_bytes(old.encode())
        (repository / "b.java").write_bytes(b"-- old")
        git("add", "--", "a.java", "b.java")
        (repository / "a.java").write_bytes(new.encode())
        (repository / "b.java").write_bytes(b"++ new")
        diff = git("diff", "--no-ext-diff", "--no-textconv", "--unified=1")
        files = {"a.java": new.encode(), "b.java": b"++ new"}
        # Independent physical-coordinate oracle, fixed for this actual Git
        # output. Neither projection nor its shared parser computes expectations.
        expected = {"a.java": [(6, 9), (11, 14)], "b.java": [(21, 21), (23, 23)]}
    location = publish_snapshot(workspaces.settings.snapshot_root, payload, files)
    (location / "artifacts/changes.diff").write_bytes(diff)
    manifest = json.loads((location / "manifest.json").read_bytes())
    manifest["targetPaths"] = list(expected)
    manifest["changes"]["files"] = [
        {
            "path": path,
            "oldPath": None,
            "changeType": "MODIFIED",
            "binary": False,
            "gitMode": "100644",
        }
        for path in expected
    ]
    manifest["changes"]["diff"].update(byteSize=len(diff), diffSha256=sha256(diff))
    raw = canonical(manifest)
    (location / "manifest.json").write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = sha256(raw)
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    signal = asyncio.Event()
    try:
        pages, cursor = [], None
        while True:
            page = await gateway.call(
                context, "git_diff", {"cursor": cursor}, signal, output_budget=8192
            )
            assert page.status == "OK"
            pages.append(page)
            if not page.data["hasMore"]:
                break
            cursor = page.data["nextCursor"]
        if case == "paged":
            assert len(pages) > 1
            assert pages[0].data["pageCoordinates"]["endsMidLine"]
            assert pages[1].data["pageCoordinates"]["startsMidLine"]
        references = [ref for page in pages for ref in page.data["evidenceReferences"]]
        actual = {}
        for ref in references:
            actual.setdefault(ref["path"], set()).update(
                range(ref["startLine"], ref["endLine"] + 1)
            )
        assert actual == {
            path: {line for first, last in groups for line in range(first, last + 1)}
            for path, groups in expected.items()
        }, diff.decode()
        service = EvidenceService(
            gateway.settings, database, gateway.runs, workspaces, gateway.artifacts
        )
        record = await service.capture(
            context,
            EvidenceCandidate.model_validate(
                {
                    "evidenceType": "CODE",
                    "sourceRefs": references,
                    "coverage": "FULL",
                    "summary": "Header-like code",
                }
            ),
            str(uuid4()),
            signal,
        )
        material = await service.materials(context.run, record.sourceRefs, signal)
        assert any("--- old" in item["text"] for item in material)
    finally:
        await workspaces.cleanup(context.run)


async def test_final_tool_projects_a_copyable_evidence_reference(
    database, tmp_path, payload, workspaces
):
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    signal = asyncio.Event()
    try:
        result = await gateway.call(
            context, "read_file", {"path": "src/Demo.java"}, signal
        )
        reference = CodeReference.model_validate(result.data["evidenceReferences"][0])
        assert reference.side == "final" and reference.sourceType == "CODE"
        assert reference.sha256 == result.sourceRefs[0]["contentSha256"]
        assert (reference.startLine, reference.endLine) == (1, 1)
    finally:
        await workspaces.cleanup(context.run)


@pytest.mark.parametrize(
    "mistake",
    [
        "line",
        "hash",
        "coverage",
        "candidate",
        "schema",
        "schema-validation",
        "schema-large",
        "always-bad",
        "snapshot",
        "storage",
        "diagnostic",
        "cancel",
    ],
)
async def test_legacy_graph_corrects_semantic_references_without_partial_evidence(
    database, tmp_path, payload, mistake
):
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "execution_mode": "REAL",
            "deepseek_api_key": SecretStr("offline-only"),
            "checkpoint_database_url": f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
            "shutdown_grace_seconds": 2,
        }
    )
    payload["executionContract"] = settings.execution_contract
    if mistake == "coverage":
        payload["snapshot"]["snapshotId"] = str(uuid4())
        publish_snapshot(
            settings.snapshot_root,
            payload,
            {"src/Demo.java": b"password = 'source secret phrase'\n"},
        )
    attempts, validation_attempts, requests, diagnostics = 0, 0, [], []
    reference = None

    async def server(request):
        nonlocal attempts, validation_attempts, reference
        data = json.loads(request.content)
        requests.append(data)
        anchors = next(
            json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
            for m in data["messages"]
            if "Untrusted fixed input data: " in (m.get("content") or "")
        )
        value = response()
        if "Validate candidates" in data["messages"][0]["content"]:
            validation_attempts += 1
            if mistake == "schema-validation" and validation_attempts > 1:
                feedback = next(
                    json.loads(m["content"])
                    for m in data["messages"]
                    if m["role"] == "user"
                    and "platformSubmissionFeedback" in (m.get("content") or "")
                )
                assert feedback["platformSubmissionFeedback"]["fieldPath"] == [
                    "validations",
                    0,
                    "disposition",
                ]
                assert any(
                    m["role"] == "assistant"
                    and "INVALID_DISPOSITION" in (m.get("content") or "")
                    for m in data["messages"]
                )
                assert any(
                    '"materials"' in (m.get("content") or "") for m in data["messages"]
                )
                diagnostics.append(feedback["diagnosticArtifactRef"]["artifactId"])
            value["choices"][0]["message"]["content"] = json.dumps(
                {
                    "validations": [
                        {
                            "candidateId": anchors["candidates"][0]["candidateId"],
                            "disposition": "INVALID_DISPOSITION"
                            if mistake == "schema-validation"
                            and validation_attempts == 1
                            else "REJECTED",
                            "validationSummary": {
                                "basis": "Original material contains the class declaration",
                                "counterexampleChecked": "Checked the actual class declaration",
                                "counterexampleHolds": True,
                                "assumptionsResolved": True,
                            },
                        }
                    ]
                }
            )
            return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))
        if len(requests) == 1:
            value["choices"][0].update(
                finish_reason="tool_calls",
                message={
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "actual-read-id",
                            "type": "function",
                            "function": {
                                "name": "read_file",
                                "arguments": '{"path":"src/Demo.java"}',
                            },
                        }
                    ],
                },
            )
        elif "response_format" not in data:
            value["choices"][0]["message"]["content"] = "Ready for submission."
        else:
            for message in data["messages"]:
                if message["role"] == "tool":
                    reference = json.loads(message["content"])["data"][
                        "evidenceReferences"
                    ][0]
                elif message["role"] == "user":
                    try:
                        feedback = json.loads(message["content"])
                    except ValueError:
                        continue
                    if "platformSubmissionFeedback" in feedback:
                        if mistake in {"schema", "schema-large"}:
                            assert feedback["platformSubmissionFeedback"][
                                "fieldPath"
                            ] == ["candidates", 0, "claimKind"]
                            violations = feedback["platformSubmissionFeedback"][
                                "schemaViolations"
                            ]
                            assert violations[0] == {
                                "fieldPath": ["candidates", 0, "claimKind"],
                                "errorType": "literal_error",
                                "expected": {
                                    "type": "string",
                                    "enum": [
                                        "STATIC_CORRECTNESS",
                                        "ENVIRONMENT_DEPENDENT",
                                        "QUANTITATIVE",
                                    ],
                                },
                            }
                            # A stateless provider must receive its rejected
                            # submission, not just a digest and field location.
                            assert any(
                                m["role"] == "assistant"
                                and "NOT_A_CLAIM_KIND" in (m.get("content") or "")
                                for m in data["messages"]
                            )
                            if mistake == "schema-large":
                                assert any(
                                    "REJECTED_END_MARKER" in (m.get("content") or "")
                                    for m in data["messages"]
                                    if m["role"] == "assistant"
                                )
                            diagnostics.append(
                                feedback["diagnosticArtifactRef"]["artifactId"]
                            )
                            continue
                        assert feedback["platformSubmissionFeedback"]["reason"] in {
                            "RANGE_INVALID",
                            "HASH_MISMATCH",
                            "COVERAGE_INCOMPLETE",
                            "REFERENCE_NOT_IN_EVIDENCE",
                        }
                        expected_path = (
                            ["candidates", 0, "codeReferences", 0]
                            if mistake == "candidate"
                            else [
                                "evidenceCandidates",
                                1,
                                "sourceRefs",
                                0,
                                *(
                                    []
                                    if mistake == "coverage"
                                    else ["sha256" if mistake == "hash" else "endLine"]
                                ),
                            ]
                        )
                        assert (
                            feedback["platformSubmissionFeedback"]["fieldPath"]
                            == expected_path
                        )
                        diagnostics.append(
                            feedback["diagnosticArtifactRef"]["artifactId"]
                        )
            recorded = bool(anchors.get("evidenceRecords"))
            assert (
                "checkedScope must copy complete file paths verbatim from scope.targetPaths"
                in data["messages"][0]["content"]
            )
            assert (
                "Submission phase: CANDIDATES_ALLOWED"
                if recorded
                else "Submission phase: EVIDENCE_FIRST"
            ) in data["messages"][0]["content"]
            evidence = []
            if not recorded:
                attempts += 1
                bad = dict(reference)
                if (
                    attempts == 1
                    and mistake
                    not in {
                        "storage",
                        "coverage",
                        "candidate",
                        "schema",
                        "schema-validation",
                        "schema-large",
                    }
                ) or mistake == "always-bad":
                    if mistake == "hash":
                        bad["sha256"] = "sha256:" + "f" * 64
                    else:
                        bad["endLine"] = 99
                evidence = [
                    {
                        "evidenceType": "DOMAIN_CONTEXT",
                        "sourceRefs": [anchors["userInputEvidenceReference"]],
                        "coverage": "FULL",
                        "summary": "Original request",
                    },
                    {
                        "evidenceType": "CODE",
                        "sourceRefs": [bad],
                        "coverage": "PARTIAL"
                        if mistake == "coverage" and attempts > 1
                        else "FULL",
                        "summary": "password = 'private diagnostic phrase'",
                    },
                ]
            candidates = []
            if recorded and mistake in {
                "candidate",
                "schema",
                "schema-validation",
                "schema-large",
            }:
                record = next(
                    record
                    for record in anchors["evidenceRecords"]
                    if record["evidenceType"] == "CODE"
                )
                ref = dict(record["sourceRefs"][0])
                if not diagnostics and mistake == "candidate":
                    ref["sha256"] = "sha256:" + "f" * 64
                candidates = [
                    {
                        "passId": "correctness",
                        "category": "SYNTHETIC_CLAIM",
                        "title": "Synthetic candidate",
                        "claim": "The class declaration is absent",
                        "severity": "LOW",
                        "confidence": 0.5,
                        "evidenceIds": [record["evidenceId"]],
                        "codeReferences": [ref],
                        "claimKind": "NOT_A_CLAIM_KIND"
                        if mistake in {"schema", "schema-large"} and not diagnostics
                        else "STATIC_CORRECTNESS",
                        "reasoningSummary": {
                            "rule": "Declaration should be present",
                            "trigger": "Inspect the source",
                            "executionPath": "Read the declaration",
                            "assumptions": [],
                            "counterexamples": [
                                "A present declaration disproves the claim"
                            ],
                            "conclusion": "Check independently",
                        },
                        "suggestion": "Check the actual declaration",
                    }
                ]
                if mistake == "schema-large" and not diagnostics:
                    candidates[0]["unknown_field"] = "x" * 20000 + "REJECTED_END_MARKER"
            value["choices"][0]["message"]["content"] = json.dumps(
                {
                    "decision": "COMPLETE" if recorded else "CONTINUE",
                    "evidenceCandidates": evidence,
                    "candidates": candidates,
                    "checkedScope": ["src/Demo.java"],
                    "scopeKind": "FINAL_FILES",
                    "coverage": "FULL",
                    "limitations": ["Only fixed synthetic input was checked"],
                    "incompleteReasons": [],
                }
            )
            if mistake == "snapshot":
                (
                    settings.snapshot_root
                    / payload["snapshot"]["snapshotId"]
                    / "content/src/Demo.java"
                ).write_bytes(b"tampered\n")
            elif mistake == "cancel":
                await app.state.service.cancel(payload["runId"])
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

    app = create_app(settings, provider_transport=httpx.MockTransport(server))
    async with app.router.lifespan_context(app), client_for(app) as client:

        async def storage_failure(connection):
            raise OSError("offline injected commit failure")

        if mistake == "storage":
            app.state.evidence.store.commit_transaction = storage_failure
        elif mistake == "diagnostic":
            publish = app.state.artifacts.publish

            async def diagnostic_failure(*args, **kwargs):
                if args[3] == "submission_diagnostic":
                    raise HarnessError(
                        "ARTIFACT_PERSISTENCE_FAILED",
                        503,
                        "offline injected publication failure",
                    )
                return await publish(*args, **kwargs)

            app.state.artifacts.publish = diagnostic_failure
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 60)
        run_url = f"/internal/v1/review-runs/{payload['runId']}"
        run = (await client.get(run_url)).json()
        assert run["resourceState"] == "RELEASED"
        if mistake in {"always-bad", "snapshot", "storage", "diagnostic", "cancel"}:
            assert run["status"] == ("CANCELLED" if mistake == "cancel" else "FAILED")
            assert len(requests) == (5 if mistake == "always-bad" else 3)
            stored = await app.state.evidence.list_for_run(
                await app.state.service.store.get_run(payload["runId"])
            )
            assert stored == []
            if mistake != "always-bad":
                if mistake != "cancel":
                    assert (
                        run["failure"]["code"]
                        == {
                            "snapshot": "EVIDENCE_SNAPSHOT_MISMATCH",
                            "storage": "EVIDENCE_PERSISTENCE_FAILED",
                            "diagnostic": "EVIDENCE_REFERENCE_INVALID",
                        }[mistake]
                    )
                assert diagnostics == []
                return
        else:
            assert run["status"] == "SUCCEEDED", run
            result = (await client.get(run_url + "/result")).json()
            assert len(result["evidence"]) == 2 and len(requests) == (
                7
                if mistake
                in {"candidate", "schema", "schema-large", "schema-validation"}
                else 6
            )
            assert not result["findings"]
            for record in result["evidence"]:
                material = await client.get(
                    run_url + f"/evidence/{record['evidenceId']}/materials"
                )
                assert material.status_code == 200
        assert diagnostics
        assert all(
            "private diagnostic phrase" not in json.dumps(request)
            for request in requests
        )
        for identity in set(diagnostics):
            content = (
                await client.get(run_url + f"/artifacts/{identity}/content")
            ).json()["text"]
            assert "private diagnostic phrase" not in content
            diagnostic = json.loads(content)
            assert diagnostic["modelCallId"] and diagnostic[
                "submissionDigest"
            ].startswith("sha256:")
            if not mistake.startswith("schema"):
                assert diagnostic["rejectedReference"]["endLine"] in {1, 99}


async def test_artifact_projection_uses_artifact_lines_and_preserves_half_line_pages(
    database, tmp_path, payload, workspaces
):
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    signal = asyncio.Event()
    try:
        artifact = await gateway.artifacts.publish(
            context, "x" * 3000 + "\nnext line\n", "TOOL_OUTPUT", "read_file", signal
        )
        first = await gateway.call(
            context,
            "read_artifact",
            {"artifactId": artifact.artifact_id},
            signal,
            output_budget=2048,
        )
        assert first.status == "OK"
        assert len(canonical(first.model_dump())) <= 2048
        assert first.data["coordinates"]["endsMidLine"]
        second = await gateway.call(
            context,
            "read_artifact",
            {"artifactId": artifact.artifact_id, "cursor": first.data["nextCursor"]},
            signal,
            output_budget=2048,
        )
        assert second.data["coordinates"]["startsMidLine"]
        assert second.data["coordinates"]["startLine"] == 1
        assert len(canonical(second.model_dump())) <= 2048
        ref = first.data["evidenceReferences"][0]
        assert ref["sourceType"] == "ARTIFACT" and ref["sha256"] == artifact.sha256
        assert "path" not in ref and "side" not in ref
    finally:
        await workspaces.cleanup(context.run)


async def test_artifact_small_output_budget_is_a_tool_limit_not_resource_failure(
    database, tmp_path, payload, workspaces
):
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    signal = asyncio.Event()
    try:
        artifact = await gateway.artifacts.publish(
            context, "x" * 3000, "TOOL_OUTPUT", "read_file", signal
        )
        result = await gateway.call(
            context,
            "read_artifact",
            {"artifactId": artifact.artifact_id},
            signal,
            output_budget=1024,
        )
        assert result.status == "ERROR" and result.error.code == "TOOL_LIMIT_EXCEEDED"
        assert not workspaces.runtime_tasks_active(context.run.run_id)
    finally:
        await workspaces.cleanup(context.run)


async def test_redacted_artifact_cannot_be_published_as_full_evidence(
    database, tmp_path, payload, workspaces
):
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    signal = asyncio.Event()
    service = EvidenceService(
        gateway.settings, database, gateway.runs, workspaces, gateway.artifacts
    )
    try:
        artifact = await gateway.artifacts.publish(
            context,
            "password = 'artifact secret phrase'\n",
            "TOOL_OUTPUT",
            "read_file",
            signal,
        )
        assert artifact.redaction_state == "APPLIED"
        page = await gateway.call(
            context, "read_artifact", {"artifactId": artifact.artifact_id}, signal
        )
        ref = page.data["evidenceReferences"][0]
        candidate = EvidenceCandidate.model_validate(
            {
                "evidenceType": "CODE",
                "sourceRefs": [ref],
                "coverage": "FULL",
                "summary": "Captured artifact",
            }
        )
        with pytest.raises(HarnessError) as caught:
            await service.prepare_batch(context, [candidate], str(uuid4()), signal)
        assert caught.value.reason == "COVERAGE_INCOMPLETE"
        assert caught.value.stage == "MATERIAL_COVERAGE"
        assert caught.value.path == ("evidenceCandidates", 0, "sourceRefs", 0)
        assert not await service.list_for_run(context.run)
    finally:
        await workspaces.cleanup(context.run)


async def test_deleted_diff_pages_expose_physical_coordinates_across_half_lines(
    database, tmp_path, payload, workspaces
):
    payload["snapshot"]["snapshotId"] = str(uuid4())
    location = publish_snapshot(
        workspaces.settings.snapshot_root, payload, {"context.txt": b"context\n"}
    )
    diff = (
        "diff --git a/src/Deleted.java b/src/Deleted.java\n--- a/src/Deleted.java\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-"
        + "x" * 3000
        + "\n-class Deleted {}\n"
    ).encode()
    (location / "artifacts/changes.diff").write_bytes(diff)
    manifest = json.loads((location / "manifest.json").read_bytes())
    manifest["targetPaths"] = ["src/Deleted.java"]
    manifest["changes"]["files"] = [
        {
            "path": "src/Deleted.java",
            "oldPath": None,
            "changeType": "DELETED",
            "binary": False,
            "gitMode": "000000",
        }
    ]
    manifest["changes"]["diff"].update(byteSize=len(diff), diffSha256=sha256(diff))
    raw = canonical(manifest)
    (location / "manifest.json").write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = sha256(raw)
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    signal = asyncio.Event()
    try:
        pages, cursor = [], None
        while True:
            page = await gateway.call(
                context, "git_diff", {"cursor": cursor}, signal, output_budget=8192
            )
            assert page.status == "OK"
            pages.append(page)
            if not page.data["hasMore"]:
                break
            cursor = page.data["nextCursor"]
        assert pages[0].data["pageCoordinates"]["endsMidLine"]
        assert pages[1].data["pageCoordinates"]["startsMidLine"]
        reference = next(
            ref for ref in pages[-1].data["evidenceReferences"] if ref["endLine"] == 6
        )
        assert reference["side"] == "diff" and reference["path"] == "src/Deleted.java"
        # Copy the trusted projection, without injecting a fixture digest into
        # the candidate. Choose the last physical hunk line from that projection.
        reference = {**reference, "startLine": 6}
        service = EvidenceService(
            gateway.settings, database, gateway.runs, workspaces, gateway.artifacts
        )
        record = await service.capture(
            context,
            EvidenceCandidate.model_validate(
                {
                    "evidenceType": "CODE",
                    "sourceRefs": [reference],
                    "coverage": "FULL",
                    "summary": "Deleted declaration",
                }
            ),
            str(uuid4()),
            signal,
        )
        material = await service.materials(context.run, record.sourceRefs, signal)
        assert material[0]["text"] == "-class Deleted {}\n"
    finally:
        await workspaces.cleanup(context.run)


async def test_second_bad_evidence_has_no_partial_publication_and_exposes_location(
    database, tmp_path, payload, workspaces
):
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    signal = asyncio.Event()
    service = EvidenceService(
        gateway.settings, database, gateway.runs, workspaces, gateway.artifacts
    )
    try:
        tool = await gateway.call(
            context, "read_file", {"path": "src/Demo.java"}, signal
        )
        ref = tool.data["evidenceReferences"][0]
        valid = EvidenceCandidate.model_validate(
            {
                "evidenceType": "CODE",
                "sourceRefs": [ref],
                "coverage": "FULL",
                "summary": "Declaration",
            }
        )
        invalid = valid.model_copy(
            update={
                "sourceRefs": [CodeReference.model_validate({**ref, "endLine": 99})]
            }
        )
        with pytest.raises(HarnessError) as caught:
            await service.prepare_batch(context, [valid, invalid], str(uuid4()), signal)
        assert caught.value.reason == "RANGE_INVALID"
        assert caught.value.path == (
            "evidenceCandidates",
            1,
            "sourceRefs",
            0,
            "endLine",
        )
        assert not await service.list_for_run(context.run)
        prepared = await service.prepare_batch(
            context, [valid, valid], str(uuid4()), signal
        )
        records = await service.store.save_batch(context.run, prepared)
        assert len(records) == 2 and len(await service.list_for_run(context.run)) == 2
    finally:
        await workspaces.cleanup(context.run)


async def test_evidence_batch_confirms_all_records_after_lost_commit_ack(
    database, tmp_path, payload, workspaces
):
    context, gateway = await setup_gateway(database, tmp_path, payload, workspaces)
    signal = asyncio.Event()
    service = EvidenceService(
        gateway.settings, database, gateway.runs, workspaces, gateway.artifacts
    )
    try:
        output = await gateway.call(
            context, "read_file", {"path": "src/Demo.java"}, signal
        )
        candidate = EvidenceCandidate.model_validate(
            {
                "evidenceType": "CODE",
                "sourceRefs": output.data["evidenceReferences"],
                "coverage": "FULL",
                "summary": "Observed declaration",
            }
        )
        records = await service.prepare_batch(
            context, [candidate, candidate], str(uuid4()), signal
        )

        async def lost_ack(connection):
            await connection.commit()
            raise OSError("offline lost acknowledgement")

        service.store.commit_transaction = lost_ack
        saved = await service.store.save_batch(context.run, records)
        assert saved == records
        stored = await service.list_for_run(context.run)
        assert {r.evidenceId for r in stored} == {r.evidenceId for r in records}
        assert len(stored) == 2
    finally:
        await workspaces.cleanup(context.run)


async def test_legacy_model_uses_deleted_diff_page_projection_through_atomic_result(
    database, tmp_path, payload
):
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "execution_mode": "REAL",
            "deepseek_api_key": SecretStr("offline-only"),
            "checkpoint_database_url": f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
            "shutdown_grace_seconds": 2,
        }
    )
    payload["executionContract"] = settings.execution_contract
    payload["snapshot"]["snapshotId"] = str(uuid4())
    location = publish_snapshot(
        settings.snapshot_root, payload, {"context.txt": b"context\n"}
    )
    diff = (
        "diff --git a/src/Deleted.java b/src/Deleted.java\n--- a/src/Deleted.java\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-"
        + "x" * 3000
        + "\n-class Deleted {}\n"
    ).encode()
    (location / "artifacts/changes.diff").write_bytes(diff)
    manifest = json.loads((location / "manifest.json").read_bytes())
    manifest["targetPaths"] = ["src/Deleted.java"]
    manifest["changes"]["files"] = [
        {
            "path": "src/Deleted.java",
            "oldPath": None,
            "changeType": "DELETED",
            "binary": False,
            "gitMode": "000000",
        }
    ]
    manifest["changes"]["diff"].update(byteSize=len(diff), diffSha256=sha256(diff))
    raw = canonical(manifest)
    (location / "manifest.json").write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = sha256(raw)
    requests, pages = [], []

    def server(request):
        data = json.loads(request.content)
        requests.append(data)
        value = response()
        anchors = next(
            json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
            for m in data["messages"]
            if "Untrusted fixed input data: " in (m.get("content") or "")
        )
        last = data["messages"][-1]
        more = len(requests) == 1
        cursor = None
        if last["role"] == "tool":
            page = json.loads(last["content"])["data"]
            pages.append(page)
            more = page["hasMore"]
            cursor = page["nextCursor"]
        if more:
            value["choices"][0].update(
                finish_reason="tool_calls",
                message={
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": f"diff-page-{len(requests)}",
                            "type": "function",
                            "function": {
                                "name": "git_diff",
                                "arguments": json.dumps({"cursor": cursor}),
                            },
                        }
                    ],
                },
            )
        elif "response_format" in data:
            refs = pages[-1]["evidenceReferences"]
            ref = {**refs[-1], "startLine": refs[-1]["endLine"]}
            recorded = bool(anchors.get("evidenceRecords"))
            value["choices"][0]["message"]["content"] = json.dumps(
                {
                    "decision": "COMPLETE" if recorded else "CONTINUE",
                    "evidenceCandidates": []
                    if recorded
                    else [
                        {
                            "evidenceType": "CODE",
                            "sourceRefs": [ref],
                            "coverage": "FULL",
                            "summary": "Deleted declaration",
                        }
                    ],
                    "candidates": [],
                    "checkedScope": ["src/Deleted.java"],
                    "scopeKind": "FIXED_DIFF",
                    "coverage": "FULL",
                    "limitations": ["Only the fixed deletion diff"],
                    "incompleteReasons": [],
                }
            )
        else:
            value["choices"][0]["message"]["content"] = "Ready for json submission."
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

    app = create_app(settings, provider_transport=httpx.MockTransport(server))
    async with app.router.lifespan_context(app), client_for(app) as client:
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 60)
        result = await client.get(f"/internal/v1/review-runs/{payload['runId']}/result")
        assert result.status_code == 200, result.text
        body = result.json()
        assert body["passResults"][0]["status"] == "COMPLETED", body["passResults"][0]
        assert len(pages) > 1 and pages[1]["pageCoordinates"]["startsMidLine"]
        ref = body["evidence"][0]["sourceRefs"][0]
        assert ref["side"] == "diff" and ref["startLine"] == 6
