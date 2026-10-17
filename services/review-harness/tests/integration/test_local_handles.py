from uuid import uuid4

import pytest

from review_harness.domain.handles import HandleRegistry, SourceSelection
from review_harness.domain.submission import SubmissionIssue


def test_source_handles_are_stable_scoped_and_enforce_observed_ranges():
    registry = HandleRegistry(runId=str(uuid4()), snapshotId=str(uuid4()))
    source = {
        "sourceType": "CODE",
        "path": "src/Demo.java",
        "side": "final",
        "sha256": "sha256:" + "a" * 64,
        "startLine": 2,
        "endLine": 3,
    }
    registry, handle = registry.register_source(source, "FULL", "NONE", [[2, 3]])
    assert handle == "s1"
    registry, again = registry.register_source(
        {**source, "startLine": 5, "endLine": 6}, "FULL", "NONE", [[5, 6]]
    )
    assert again == "s1"
    assert (
        registry.resolve(
            SourceSelection(sourceHandle="s1", startLine=2, endLine=3)
        ).startLine
        == 2
    )
    with pytest.raises(SubmissionIssue, match="EVIDENCE_REFERENCE_INVALID"):
        registry.resolve(SourceSelection(sourceHandle="s1", startLine=3, endLine=5))
    changed, handle = registry.register_source(
        {**source, "sha256": "sha256:" + "b" * 64}, "FULL", "NONE", [[2, 3]]
    )
    assert handle == "s2" and changed.sources[0].source.sha256 == source["sha256"]


@pytest.mark.parametrize(
    "mode",
    [
        "valid",
        "saved-link",
        "stable-compression",
        "artifact-pages",
        "schema-correction",
        "redacted-source",
        "duplicate-key",
        "duplicate-link",
        "unseen-code-range",
        "second-evidence-invalid",
        "second-candidate-invalid",
        "evidence-lost-ack",
        "evidence-rollback",
        "checkpoint-lost-ack",
        "checkpoint-unconfirmed",
    ],
)
async def test_same_submission_new_evidence_and_candidate_reaches_verified_result(
    database, tmp_path, payload, workspaces, mode
):
    import asyncio
    import json

    import httpx
    from pydantic import SecretStr

    from review_harness.application.evidence import EvidenceService
    from review_harness.domain.model import canonical
    from review_harness.domain.real_graph import HandleReviewGraphState
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from review_harness.infrastructure.persistence.store import RunStore
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.deepseek import DeepSeekAdapter
    from review_harness.runtime.review import RealReviewExecutor
    from review_harness.runtime.tools import ToolGateway
    from tests.integration.test_deepseek_adapter import response
    from tests.snapshot_fixture import publish_snapshot

    source = "class Demo { int value(Integer x) { return x.intValue(); } }\n"
    if mode == "redacted-source":
        source = "password = 'original secret phrase'\n"
    if mode == "artifact-pages":
        source += "// " + "x" * 11000 + "\n"
    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(
        workspaces.settings.snapshot_root, payload, {"src/Demo.java": source.encode()}
    )
    runs = RunStore(database)
    await runs.create_run(payload)
    run, _ = await runs.start_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    evidence = EvidenceService(
        workspaces.settings, database, runs, workspaces, artifacts
    )
    checkpoints = Checkpoints(tmp_path / "handles.sqlite")
    await checkpoints.open()
    sent = []

    def server(request):
        data = json.loads(request.content)
        sent.append(data)
        value = response()
        choice = value["choices"][0]
        anchors = next(
            json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
            for m in data["messages"]
            if "Untrusted fixed input data: " in (m.get("content") or "")
        )
        if len(sent) == 1:
            choice.update(
                finish_reason="tool_calls",
                message={
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "original-handle-read",
                            "type": "function",
                            "function": {
                                "name": "read_file",
                                "arguments": '{"path":"src/Demo.java"}',
                            },
                        }
                    ],
                },
            )
        elif (
            mode == "artifact-pages"
            and data["messages"][-1]["role"] == "tool"
            and (
                (tool := json.loads(data["messages"][-1]["content"])).get("artifactRef")
                or (
                    tool.get("toolName") == "read_artifact"
                    and tool["data"].get("hasMore")
                )
            )
        ):
            arguments = {
                "artifactId": tool["artifactRef"]["artifactId"]
                if tool.get("artifactRef")
                else tool["data"]["artifactId"]
            }
            if tool["data"].get("nextCursor"):
                arguments["cursor"] = tool["data"]["nextCursor"]
            choice.update(
                finish_reason="tool_calls",
                message={
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": f"original-artifact-page-{len(sent)}",
                            "type": "function",
                            "function": {
                                "name": "read_artifact",
                                "arguments": json.dumps(arguments),
                            },
                        }
                    ],
                },
            )
        elif "response_format" not in data:
            choice["message"]["content"] = (
                "Ready to submit the observed null regression."
            )
        elif "LocalValidationBatch" in data["messages"][0]["content"]:
            candidate = anchors["candidates"][0]
            handle = candidate["candidateHandle"]
            source_handle = candidate["codeSelections"][0]["sourceHandle"]
            choice["message"]["content"] = json.dumps(
                {
                    "validations": [
                        {
                            "candidateHandle": handle,
                            "disposition": "VERIFIED",
                            "validationSummary": {
                                "basis": "Original code dereferences null",
                                "counterexampleChecked": "No null guard",
                                "counterexampleHolds": False,
                                "assumptionsResolved": True,
                            },
                            "factChecks": [
                                {
                                    "candidateHandle": handle,
                                    "sourceHandle": source_handle,
                                    "candidateText": "Null input throws",
                                    "sourceQuote": source.splitlines()[0],
                                    "outcome": "SUPPORTED",
                                    "explanation": "The original expression dereferences x without a guard.",
                                }
                            ],
                        }
                    ]
                }
            )
        else:
            code = next(
                s for s in anchors["sourceCatalog"] if s["sourceKind"] == "CODE"
            )
            selection = {
                "sourceHandle": code["sourceHandle"],
                "startLine": 1,
                "endLine": 1,
            }
            choice["message"]["content"] = json.dumps(
                {
                    "decision": "COMPLETE",
                    "evidenceCandidates": [
                        {
                            "localKey": "n1",
                            "evidenceType": "CODE",
                            "selections": [selection],
                            "coverage": "FULL",
                            "summary": "Original null dereference",
                        }
                    ],
                    "candidates": [
                        {
                            "passId": "correctness",
                            "category": "Null handling",
                            "title": "Null input throws",
                            "claim": "Null input throws",
                            "severity": "MEDIUM",
                            "confidence": 0.9,
                            "evidenceLinks": [{"kind": "NEW", "key": "n1"}],
                            "codeSelections": [selection],
                            "reasoningSummary": {
                                "rule": "Handle null",
                                "trigger": "x is null",
                                "executionPath": "x.intValue()",
                                "assumptions": [],
                                "counterexamples": ["A caller may reject null"],
                                "conclusion": "The specified null behavior regresses",
                            },
                            "suggestion": "Restore the guard",
                            "claimKind": "STATIC_CORRECTNESS",
                        }
                    ],
                    "checkedScope": ["src/Demo.java"],
                    "scopeKind": "FINAL_FILES",
                    "coverage": "FULL",
                    "limitations": ["Only the fixed class was checked"],
                    "incompleteReasons": [],
                }
            )
        if (
            "response_format" in data
            and "LocalValidationBatch" not in data["messages"][0]["content"]
        ):
            import copy

            batch = json.loads(choice["message"]["content"])
            if mode == "schema-correction":
                if len(sent) == 3:
                    batch["evidenceCandidates"][0]["selections"][0]["sha256"] = (
                        "sha256:" + "f" * 64
                    )
                else:
                    feedback = next(
                        json.loads(m["content"])
                        for m in data["messages"]
                        if m["role"] == "user"
                        and "platformSubmissionFeedback" in (m.get("content") or "")
                    )
                    assert feedback["platformSubmissionFeedback"]["stage"] == "SCHEMA"
                    assert feedback["allowedHandles"]["evidence"] == []
                    rejected = next(
                        m["content"]
                        for m in reversed(data["messages"])
                        if m["role"] == "assistant"
                    )
                    assert "sha256" in rejected and "localKey" in rejected
            if mode == "duplicate-key":
                batch["evidenceCandidates"].append(
                    copy.deepcopy(batch["evidenceCandidates"][0])
                )
            elif mode == "duplicate-link":
                batch["candidates"][0]["evidenceLinks"] *= 2
            elif mode == "unseen-code-range":
                batch["candidates"][0]["codeSelections"][0].update(
                    startLine=2, endLine=2
                )
            if mode == "saved-link":
                if not anchors.get("evidenceRecords"):
                    batch["decision"] = "CONTINUE"
                    batch["candidates"] = []
                else:
                    batch["evidenceCandidates"] = []
                    batch["candidates"][0]["evidenceLinks"] = [
                        {
                            "kind": "SAVED",
                            "handle": anchors["evidenceRecords"][0]["evidenceHandle"],
                        }
                    ]
            if mode == "second-evidence-invalid":
                bad = copy.deepcopy(batch["evidenceCandidates"][0])
                bad.update(localKey="n2")
                bad["selections"][0].update(startLine=2, endLine=2)
                batch["evidenceCandidates"].append(bad)
            elif mode == "second-candidate-invalid":
                bad = copy.deepcopy(batch["candidates"][0])
                bad["claim"] = "Another invalid candidate"
                bad["evidenceLinks"] = [{"kind": "NEW", "key": "n999"}]
                batch["candidates"].append(bad)
            choice["message"]["content"] = json.dumps(batch)
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

    from review_harness.infrastructure.persistence.evidence import EvidenceStore
    from review_harness.infrastructure.persistence.store import commit

    async def evidence_commit(connection):
        if mode == "evidence-rollback":
            raise OSError("offline write failed before commit")
        await commit(connection)
        if mode == "evidence-lost-ack":
            raise OSError("offline commit acknowledgement lost")

    evidence.store = EvidenceStore(database, commit_transaction=evidence_commit)

    async def checkpoint_boundary():
        if mode.startswith("checkpoint-") and await evidence.list_for_run(run):
            if mode == "checkpoint-unconfirmed":
                await checkpoints.connection.execute(
                    "DELETE FROM checkpoints WHERE json_extract(CAST(checkpoint AS TEXT),'$.channel_values.state.lastSubmissionReceipt') IS NOT NULL"
                )
                await checkpoints.connection.commit()
            raise OSError("offline checkpoint acknowledgement lost")

    checkpoints.after_commit = checkpoint_boundary

    adapter = DeepSeekAdapter(
        SecretStr("offline-only"), transport=httpx.MockTransport(server)
    )
    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        artifacts,
        checkpoints,
        adapter,
        model_calls=ModelCallStore(database),
        state_type=HandleReviewGraphState,
        evidence=evidence,
    )
    executor = RealReviewExecutor(
        engine, evidence, ToolGateway(workspaces.settings, runs, workspaces, artifacts)
    )
    try:
        if mode in {
            "redacted-source",
            "duplicate-key",
            "duplicate-link",
            "unseen-code-range",
            "second-evidence-invalid",
            "second-candidate-invalid",
            "evidence-rollback",
            "checkpoint-unconfirmed",
        }:
            from review_harness.domain.model import HarnessError

            with pytest.raises(HarnessError) as failed:
                await executor.execute(context, signal)
            expected = (
                "EVIDENCE_PERSISTENCE_FAILED"
                if mode == "evidence-rollback"
                else "CHECKPOINT_STATE_WRITE_UNCERTAIN"
                if mode == "checkpoint-unconfirmed"
                else "EVIDENCE_REFERENCE_INVALID"
            )
            assert failed.value.code == expected
            records = await evidence.list_for_run(run)
            assert len(records) == (1 if mode == "checkpoint-unconfirmed" else 0)
            assert len(sent) == (
                5
                if mode.startswith("second-")
                or mode
                in {
                    "redacted-source",
                    "duplicate-key",
                    "duplicate-link",
                    "unseen-code-range",
                }
                else 3
            )
            assert not any(
                "LocalValidationBatch" in r["messages"][0]["content"] for r in sent
            )
            return
        package = await executor.execute(context, signal)
        if mode == "artifact-pages":
            assert len(sent) > 4
            assert executor.last_state.inspections[0].coverage == "FULL"
        else:
            assert len(sent) == (
                6 if mode == "saved-link" else 5 if mode == "schema-correction" else 4
            )
        assert len(package.evidence) == 1 and len(package.findings) == 1
        assert package.findings[0].disposition == "VERIFIED"
        if mode == "valid":
            from review_harness.domain.model import HarnessError

            fresh = await engine.initial(context, signal)
            assert (
                fresh.handleRegistry.evidence == []
                and fresh.handleRegistry.candidates == []
            )
            assert len(fresh.handleRegistry.sources) == 1
            before_calls = len(sent)
            with pytest.raises(HarnessError, match="CHECKPOINT_REPLAY_FORBIDDEN"):
                await checkpoints.assert_fresh(fresh)
            assert len(sent) == before_calls

        assert package.findings[0].evidenceIds == [package.evidence[0].evidenceId]
        assert executor.last_state.handleRegistry.evidence[0].evidenceHandle == "e1"
        assert executor.last_state.handleRegistry.candidates[0].candidateHandle == "c1"
        assert any(
            m.get("tool_call_id") == "original-handle-read"
            for request in sent
            for m in request["messages"]
        )
        if mode == "stable-compression":
            from review_harness.domain.graph import GraphMessage
            from review_harness.runtime.compaction import Compactor

            state = executor.last_state
            before = state.handleRegistry.model_dump()
            for index in range(2):
                state = state.updated(
                    messages=[
                        *[m.model_dump() for m in state.messages],
                        *[
                            GraphMessage(
                                messageId=str(uuid4()),
                                role="assistant",
                                content="Old narration and hypotheses. " * 250,
                            ).model_dump()
                            for _ in range(3)
                        ],
                    ]
                )
                state = await Compactor(engine).compact(context, state, signal)
                assert state.summaryVersion == index + 1
                assert state.handleRegistry.model_dump() == before
                assert state.evidenceRefs == [package.evidence[0].evidenceId]
    finally:
        await executor.cleanup(context)
        await checkpoints.close()
        await workspaces.cleanup(run)


@pytest.mark.parametrize(
    "case",
    ["hash", "uuid", "ambiguous-link", "leading-zero", "wrong-prefix", "too-long"],
)
def test_local_protocol_rejects_identity_copying_and_ambiguous_names(case):
    from pydantic import ValidationError

    from review_harness.domain.handles import LocalCandidate, SourceSelection

    if case == "ambiguous-link":
        with pytest.raises(ValidationError):
            LocalCandidate.model_validate({"evidenceLinks": ["n1"]})
        return
    value = {"sourceHandle": "s1", "startLine": 1, "endLine": 1}
    if case == "hash":
        value["sha256"] = "sha256:" + "a" * 64
    elif case == "uuid":
        value["runId"] = str(uuid4())
    else:
        value["sourceHandle"] = {
            "leading-zero": "s01",
            "wrong-prefix": "e1",
            "too-long": "s" + "1" * 16,
        }[case]
    with pytest.raises(ValidationError):
        SourceSelection.model_validate(value)


def test_user_input_coordinates_are_omitted_and_survive_checkpoint_serialization():
    from review_harness.domain.handles import EvidenceBinding

    registry = HandleRegistry(runId=str(uuid4()), snapshotId=str(uuid4()))
    registry, handle = registry.register_source(
        {"sourceType": "USER_INPUT", "sha256": "sha256:" + "a" * 64}, "FULL", "NONE", []
    )
    selection = SourceSelection(sourceHandle=handle)
    binding = EvidenceBinding(
        evidenceHandle="e1", evidenceId=str(uuid4()), selections=[selection]
    )
    persisted = EvidenceBinding.model_validate_json(binding.model_dump_json())
    assert registry.resolve(persisted.selections[0]).sourceType == "USER_INPUT"
    with pytest.raises(SubmissionIssue):
        registry.resolve(
            SourceSelection(sourceHandle=handle, startLine=None, endLine=None)
        )


@pytest.mark.parametrize("skipped", [False, True])
def test_half_line_is_authorized_only_after_contiguous_complete_pages(skipped):
    from review_harness.application.handle_observations import observe_page

    registry = HandleRegistry(runId=str(uuid4()), snapshotId=str(uuid4()))
    ref = {
        "sourceType": "ARTIFACT",
        "artifactId": str(uuid4()),
        "sha256": "sha256:" + "a" * 64,
        "coverage": "FULL",
        "startLine": 1,
        "endLine": 1,
    }
    registry, handle = observe_page(
        registry,
        ref,
        "FULL",
        "NONE",
        {"startLine": 1, "endLine": 1, "startsMidLine": False, "endsMidLine": True},
        None,
        "cursor-1",
    )
    with pytest.raises(SubmissionIssue):
        registry.resolve(SourceSelection(sourceHandle=handle, startLine=1, endLine=1))
    registry, again = observe_page(
        registry,
        ref,
        "FULL",
        "NONE",
        {"startLine": 1, "endLine": 1, "startsMidLine": True, "endsMidLine": False},
        "skipped-cursor" if skipped else "cursor-1",
        None,
    )
    assert again == handle
    if skipped:
        with pytest.raises(SubmissionIssue):
            registry.resolve(
                SourceSelection(sourceHandle=handle, startLine=1, endLine=1)
            )
    else:
        assert (
            registry.resolve(
                SourceSelection(sourceHandle=handle, startLine=1, endLine=1)
            ).endLine
            == 1
        )


def test_registry_rejects_rebound_or_cross_run_state():
    from pydantic import ValidationError

    from review_harness.domain.real_graph import (
        HandleReviewGraphState,
        RealReviewGraphState,
        parse_state,
    )

    identity = str(uuid4())
    legacy = RealReviewGraphState(
        runId=identity,
        reviewThreadId=str(uuid4()),
        snapshotId=str(uuid4()),
        manifestSha256="sha256:" + "a" * 64,
        investigationId=str(uuid4()),
        userGoalDigest="sha256:" + "b" * 64,
        scopeDigest="sha256:" + "c" * 64,
        toolSetDigest="sha256:" + "d" * 64,
    )
    assert type(parse_state(legacy.model_dump())) is RealReviewGraphState
    registry = HandleRegistry(runId=identity, snapshotId=legacy.snapshotId)
    state = HandleReviewGraphState.model_validate(
        {
            **legacy.model_dump(),
            "stateVersion": "3.0",
            "handleRegistry": registry.model_dump(),
        }
    )
    with pytest.raises(ValidationError):
        HandleReviewGraphState.model_validate(
            {
                **state.model_dump(),
                "handleRegistry": {**registry.model_dump(), "runId": str(uuid4())},
            }
        )
    with pytest.raises(ValidationError):
        HandleReviewGraphState.model_validate(
            {
                **state.model_dump(),
                "handleRegistry": {**registry.model_dump(), "snapshotId": str(uuid4())},
            }
        )
