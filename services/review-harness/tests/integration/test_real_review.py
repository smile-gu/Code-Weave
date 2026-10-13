import asyncio
import json
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.application.evidence import EvidenceService
from review_harness.domain.model import canonical, sha256
from review_harness.domain.real_graph import RealReviewGraphState
from review_harness.infrastructure.artifacts import ArtifactService
from review_harness.infrastructure.checkpoints import Checkpoints
from review_harness.infrastructure.persistence.model_calls import ModelCallStore
from review_harness.infrastructure.persistence.store import RunStore
from review_harness.runtime.context import ContextEngine
from review_harness.runtime.deepseek import DeepSeekAdapter
from review_harness.runtime.review import RealReviewExecutor
from tests.snapshot_fixture import publish_snapshot

SEEDS = json.loads(
    (Path(__file__).parents[1] / "fixtures/review-eval-v1.json").read_text(
        encoding="utf-8"
    )
)["seeds"]


@pytest.mark.parametrize("seed", SEEDS, ids=lambda seed: seed["id"])
@pytest.mark.parametrize(
    "padding,artifact_limit", [(0, None), (11000, None), (11000, 4000)]
)
@pytest.mark.parametrize("page_lines", [None, 1])
async def test_autonomous_review_validates_original_material_and_rejects_counterexample(
    database,
    tmp_path,
    payload,
    workspaces,
    seed,
    padding,
    artifact_limit,
    page_lines,
):
    if artifact_limit is not None:
        workspaces.settings = workspaces.settings.model_copy(
            update={"artifact_max_bytes": artifact_limit}
        )
    source = seed["source"].encode("utf-8")
    counterexample, claim_kind, quantitative = (
        seed["counterexample"],
        seed["claimKind"],
        seed["quantitative"],
    )
    source += b"// " + b"x" * padding + b"\n" if padding else b""
    payload["snapshot"]["snapshotId"] = str(uuid4())
    payload["executionContract"] = {"executionMode": "REAL", "resultVersion": "2.0"}
    publish_snapshot(
        workspaces.settings.snapshot_root, payload, {"src/Demo.java": source}
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
    checkpoints = Checkpoints(tmp_path / "review.sqlite")
    await checkpoints.open()
    requests = []
    ref = {
        "sourceType": "CODE",
        "path": "src/Demo.java",
        "side": "final",
        "sha256": sha256(source),
        "startLine": 1,
        "endLine": 1,
    }

    def server(request):
        data = json.loads(request.content)
        requests.append(data)
        anchors = next(
            json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
            for m in data["messages"]
            if "Untrusted fixed input data: " in (m.get("content") or "")
        )
        message = {"role": "assistant", "content": "Investigation boundary reached."}
        finish = "stop"
        if len(requests) == 1:
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "provider-read",
                        "type": "function",
                        "function": {
                            "name": "read_file",
                            "arguments": json.dumps(
                                {
                                    "path": "src/Demo.java",
                                    **({"maxLines": page_lines} if page_lines else {}),
                                }
                            ),
                        },
                    }
                ],
            }
            finish = "tool_calls"
        elif data["messages"][-1]["role"] == "tool" and (
            (last := json.loads(data["messages"][-1]["content"]))["toolName"]
            == "read_file"
            and not last.get("artifactRef")
            and last.get("data", {}).get("hasMore")
        ):
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": f"provider-source-{len(requests)}",
                        "type": "function",
                        "function": {
                            "name": "read_file",
                            "arguments": json.dumps(
                                {
                                    "path": "src/Demo.java",
                                    "startLine": last["data"]["endLine"] + 1,
                                }
                            ),
                        },
                    }
                ],
            }
            finish = "tool_calls"
        elif data["messages"][-1]["role"] == "tool" and (
            (last := json.loads(data["messages"][-1]["content"])).get("artifactRef")
            or last.get("data", {}).get("hasMore")
        ):
            ref_id = (last.get("artifactRef") or last["data"])["artifactId"]
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": f"provider-artifact-{len(requests)}",
                        "type": "function",
                        "function": {
                            "name": "read_artifact",
                            "arguments": json.dumps(
                                {
                                    "artifactId": ref_id,
                                    "cursor": last.get("data", {}).get("nextCursor"),
                                }
                            ),
                        },
                    }
                ],
            }
            finish = "tool_calls"
        elif "Validate candidates" in data["messages"][0]["content"]:
            candidates = anchors["candidates"]
            assert any("x.intValue" in m.get("content", "") for m in data["messages"])
            message["content"] = json.dumps(
                {
                    "validations": [
                        {
                            "candidateId": candidates[0]["candidateId"],
                            "disposition": "REJECTED" if counterexample else "VERIFIED",
                            "factChecks": [
                                {
                                    "candidateText": candidates[0]["claim"],
                                    "evidenceId": candidates[0]["evidenceIds"][0],
                                    "sourceIndex": 0,
                                    "sourceQuote": seed["source"].strip(),
                                    "outcome": "CORE_CONTRADICTED"
                                    if counterexample
                                    else "SUPPORTED",
                                    "explanation": "Compare nullable path with the original guard and implementation.",
                                }
                            ],
                            "validationSummary": {
                                "basis": "The original static path dereferences nullable x.",
                                "counterexampleChecked": "Checked whether null is guarded before the dereference.",
                                "counterexampleHolds": counterexample,
                                "assumptionsResolved": True,
                            },
                        }
                    ]
                }
            )
        elif "response_format" in data:
            records = anchors.get("evidenceRecords", [])
            candidates = []
            if records:
                candidates = [
                    {
                        "passId": "correctness",
                        "category": "NULL_DEREFERENCE",
                        "title": "Nullable parameter is dereferenced",
                        "claim": "This path increases latency by 10 ms."
                        if quantitative
                        else "Passing null throws before a value is returned.",
                        "severity": "HIGH",
                        "confidence": 0.9,
                        "evidenceIds": [records[0]["evidenceId"]],
                        "codeReferences": [ref],
                        "claimKind": claim_kind,
                        "reasoningSummary": {
                            "rule": "Null has no instance methods.",
                            "trigger": "Call value(null).",
                            "executionPath": "Enter value; invoke x.intValue.",
                            "assumptions": [],
                            "counterexamples": [
                                "A preceding null guard would prevent the failure."
                            ],
                            "conclusion": "There is no guard on the supplied path.",
                        },
                        "suggestion": "Guard null or document and enforce the precondition.",
                    }
                ]
            message["content"] = json.dumps(
                {
                    "decision": "COMPLETE" if records else "CONTINUE",
                    "evidenceCandidates": []
                    if records
                    else [
                        {
                            "evidenceType": "CODE",
                            "sourceRefs": [ref],
                            "coverage": "FULL",
                            "summary": "Current value method.",
                        }
                    ],
                    "candidates": candidates,
                    "checkedScope": ["src/Demo.java"],
                    "scopeKind": "FINAL_FILES",
                    "coverage": "FULL",
                    "limitations": ["Only the supplied static path was inspected."],
                    "incompleteReasons": [],
                }
            )
        return httpx.Response(
            200,
            stream=httpx.ByteStream(
                canonical(
                    {
                        "id": "review-mock",
                        "object": "chat.completion",
                        "created": 0,
                        "model": "deepseek-flash",
                        "choices": [
                            {"index": 0, "message": message, "finish_reason": finish}
                        ],
                        "usage": {
                            "prompt_tokens": 1,
                            "completion_tokens": 1,
                            "total_tokens": 2,
                        },
                    }
                )
            ),
        )

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
        state_type=RealReviewGraphState,
        evidence=evidence,
    )
    executor = RealReviewExecutor(engine, evidence)
    try:
        package = await executor.execute(context, signal)
        assert package.passResult.status == (
            "INCOMPLETE" if artifact_limit else "COMPLETED"
        )
        assert len(package.evidence) == 1
        assert len(package.findings) == seed["expectedFindings"]
        if package.findings:
            assert package.findings[0].disposition == (seed["expectedDisposition"])
        assert len(requests) <= 16
        assert any(
            m.get("tool_call_id") == "provider-read"
            for request in requests
            for m in request["messages"]
        )
        assert "tools" not in requests[-1]
        assert not executor.last_state.pendingToolCalls
        await executor.cleanup(context)
        await workspaces.cleanup(run)

        async def lost_confirmation(connection):
            await connection.commit()
            raise OSError("Result commit acknowledgement lost")

        runs.commit_transaction = lost_confirmation
        completed, changed = await runs.complete_success(run.run_id, package)
        assert changed and completed.result_version == "2.0"
        repeated, changed = await runs.complete_success(run.run_id, package)
        assert not changed and repeated.result_id == completed.result_id
        original, digest = await runs.get_result(run.run_id)
        result = json.loads(original)
        assert result["resultVersion"] == "2.0"
        assert digest == sha256(original)
        assert result["reportDraft"]["byteSize"] == len(
            result["reportDraft"]["markdown"].encode("utf-8")
        )
        assert len(result["findings"]) == seed["expectedFindings"]
    finally:
        await executor.cleanup(context)
        await workspaces.cleanup(run)
        await checkpoints.close()
