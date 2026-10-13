import asyncio
import json
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.domain.graph import GraphMessage, pending_calls
from review_harness.domain.model import HarnessError, canonical
from review_harness.domain.real_graph import RealReviewGraphState
from review_harness.infrastructure.artifacts import ArtifactService
from review_harness.infrastructure.checkpoints import Checkpoints
from review_harness.infrastructure.persistence.model_calls import ModelCallStore
from review_harness.infrastructure.persistence.store import RunStore
from review_harness.runtime.context import ContextEngine
from review_harness.runtime.deepseek import DeepSeekAdapter
from review_harness.runtime.tools import ToolGateway
from tests.integration.test_deepseek_adapter import response


@pytest.mark.parametrize(
    "repeat_id,actual",
    [(False, 17), (True, 17), (False, 7975), (False, 40010), (False, 260000)],
)
@pytest.mark.parametrize("previous_tools", [0, 31, 63, 80])
async def test_actual_provider_ids_round_trip_with_durable_call_facts(
    database,
    tmp_path,
    payload,
    workspaces,
    repeat_id,
    actual,
    previous_tools,
):
    runs = RunStore(database)
    await runs.create_run(payload)
    run, _ = await runs.start_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    checkpoints = Checkpoints(tmp_path / "real-checkpoint.sqlite")
    await checkpoints.open()
    sent = []

    def server(request):
        sent.append(request.content)
        value = response()
        value["usage"] = {
            "prompt_tokens": actual,
            "completion_tokens": 1,
            "total_tokens": actual + 1,
        }
        if len(sent) == 1 or repeat_id:
            choice = value["choices"][0]
            choice["finish_reason"] = "tool_calls"
            choice["message"].update(
                content=None,
                tool_calls=[
                    {
                        "id": "provider-original-a",
                        "type": "function",
                        "function": {
                            "name": "read_file",
                            "arguments": '{"path":"src/Demo.java"}',
                        },
                    },
                    {
                        "id": "provider-original-b",
                        "type": "function",
                        "function": {
                            "name": "glob_files",
                            "arguments": '{"pattern":"**/*.java"}',
                        },
                    },
                ],
            )
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

    adapter = DeepSeekAdapter(
        SecretStr("offline-test-key"), transport=httpx.MockTransport(server)
    )
    if actual != 17:
        adapter.count_request = lambda _: 40000 if actual == 40010 else 7897
    calls = ModelCallStore(database)
    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        artifacts,
        checkpoints,
        adapter,
        model_calls=calls,
        state_type=RealReviewGraphState,
    )
    gateway = ToolGateway(workspaces.settings, runs, workspaces, artifacts)
    engine.gateway = gateway
    try:
        state = await engine.initial(context, signal)
        state = state.updated(
            toolCallCount=previous_tools,
            providerCallMappings=[
                {
                    "modelCallId": str(uuid4()),
                    "toolCallId": str(uuid4()),
                    "providerToolCallId": f"previous-provider-{index}",
                }
                for index in range(previous_tools)
            ],
        )
        if actual > 257024:
            with pytest.raises(
                HarnessError, match="CONTEXT_ACTUAL_USAGE_EXCEEDS_BUDGET"
            ):
                await engine.invoke(context, state, signal)
            facts = await calls.list_for_run(run.run_id)
            assert len(sent) == 1 and facts[0].usage.prompt_tokens == actual
            assert facts[0].failureCode == "CONTEXT_ACTUAL_USAGE_EXCEEDS_BUDGET"
            assert adapter.ready() == 257024
            return
        state, result = await engine.invoke(context, state, signal)
        assert state.toolCallCount == previous_tools + 2
        if actual in {7975, 40010}:
            expected_estimate, expected_conservative = (
                (7897, 8921) if actual == 7975 else (40000, 44000)
            )
            fact = (await calls.list_for_run(run.run_id))[0]
            assert fact.inputEstimateWarning.code == "INPUT_ESTIMATE_UNDERCOUNT"
            assert (
                fact.conservativeInputTokens == expected_conservative
                and fact.failureCode is None
            )
            manifest = json.loads(
                (await artifacts.content(run.run_id, fact.manifestRef.artifactId)).text
            )
            assert manifest["manifestVersion"] == "1.2"
            assert (
                manifest["estimatedInputTokens"] == expected_estimate
                and manifest["conservativeInputTokens"] == expected_conservative
            )
        assert [c.providerToolCallId for c in result.toolCalls] == [
            "provider-original-a",
            "provider-original-b",
        ]
        assistant = GraphMessage(
            messageId=str(uuid4()),
            role="assistant",
            content="",
            toolCalls=result.toolCalls,
        )
        messages = [assistant]
        state = state.updated(
            messages=[m.model_dump() for m in messages],
            pendingToolCalls=pending_calls(messages),
        )
        # Results may complete in the opposite order. The IDs, not positions,
        # determine pairing on the exact next provider request.
        for call in reversed(result.toolCalls):
            envelope = await gateway.call(
                context, call.name, call.arguments, signal, output_budget=8192
            )
            messages.append(
                GraphMessage(
                    messageId=str(uuid4()),
                    role="tool",
                    toolCallId=call.toolCallId,
                    content=canonical(envelope.model_dump()).decode(),
                )
            )
        state = state.updated(
            messages=[m.model_dump() for m in messages], pendingToolCalls=[]
        )
        await engine.persist(state, signal)
        if repeat_id:
            with pytest.raises(HarnessError, match="PROVIDER_CALL_MAPPING_INVALID"):
                await engine.invoke(context, state, signal)
        else:
            state, _ = await engine.invoke(context, state, signal)
        request = json.loads(sent[1])
        returned = [
            m["tool_call_id"] for m in request["messages"] if m["role"] == "tool"
        ]
        assert returned == ["provider-original-b", "provider-original-a"]
        facts = await calls.list_for_run(run.run_id)
        assert len(facts) == 2
        assert all(
            f.status == "LOCAL_CLOSED" and not f.remoteOutcomeUnknown for f in facts
        )
        for fact in facts:
            manifest = await artifacts.content(run.run_id, fact.manifestRef.artifactId)
            data = json.loads(manifest.text)
            assert fact.requestDigest == data["requestDigest"]
            assert fact.meterVersion == data["meterVersion"]
        assert len(sent) == 2
    finally:
        await engine.cleanup(run.run_id)
        await workspaces.cleanup(run)
        await checkpoints.close()


async def test_real_text_compactions_preserve_anchors_and_continue_original_tool_id(
    database, tmp_path, payload, workspaces
):
    from review_harness.application.evidence import EvidenceService
    from review_harness.domain.model import sha256
    from review_harness.domain.review import EvidenceCandidate
    from review_harness.runtime.compaction import Compactor

    runs = RunStore(database)
    await runs.create_run(payload)
    run, _ = await runs.start_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    evidence = EvidenceService(
        workspaces.settings, database, runs, workspaces, artifacts
    )
    checkpoints = Checkpoints(tmp_path / "compaction-real.sqlite")
    await checkpoints.open()
    sent = []

    unknown_id = str(uuid4())

    def server(request):
        sent.append(json.loads(request.content))
        data = sent[-1]
        value = response()
        if len(sent) <= 3:
            assert "response_format" not in data and "tools" not in data
            assert "Return plain text only" in data["messages"][0]["content"]
            assert (
                "Return json with exactly this format"
                not in data["messages"][0]["content"]
            )
            value["choices"][0]["message"]["content"] = (
                "Only the fixed class declaration was observed; no behavior is proven."
                if len(sent) < 3
                else json.dumps(
                    {"sourceRefs": [unknown_id], "note": "Only untrusted narrative."}
                )
            )
        elif len(sent) == 4:
            value["choices"][0].update(
                finish_reason="tool_calls",
                message={
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "after-summary-original-id",
                            "type": "function",
                            "function": {
                                "name": "read_file",
                                "arguments": '{"path":"src/Demo.java"}',
                            },
                        }
                    ],
                },
            )
        else:
            assert any(
                m.get("tool_call_id") == "after-summary-original-id"
                for m in data["messages"]
            )
            value["choices"][0]["message"]["content"] = (
                "Continue investigating from fixed material."
            )
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

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
    try:
        state = await engine.initial(context, signal)
        ref = {
            "sourceType": "CODE",
            "path": "src/Demo.java",
            "side": "final",
            "sha256": sha256(b"class Demo {}\n"),
            "startLine": 1,
            "endLine": 1,
        }
        record = await evidence.capture(
            context,
            EvidenceCandidate.model_validate(
                {
                    "evidenceType": "CODE",
                    "sourceRefs": [ref],
                    "coverage": "FULL",
                    "summary": "Class declaration",
                }
            ),
            str(uuid4()),
            signal,
        )
        candidate_id = str(uuid4())
        candidate = {
            "candidateId": candidate_id,
            "passId": "correctness",
            "category": "STATIC",
            "title": "A hypothesis",
            "claim": "A hypothesis still needs validation",
            "severity": "LOW",
            "confidence": 0.2,
            "evidenceIds": [record.evidenceId],
            "codeReferences": [ref],
            "suggestion": "Inspect original material",
            "claimKind": "STATIC_CORRECTNESS",
            "reasoningSummary": {
                "rule": "Check declaration",
                "trigger": "Read class",
                "executionPath": "Declaration only",
                "assumptions": [],
                "counterexamples": ["There may be no defect"],
                "conclusion": "Unvalidated",
            },
        }
        state = state.updated(
            evidenceRefs=[record.evidenceId],
            candidateRefs=[candidate_id],
            candidates=[candidate],
        )
        for index in range(2):
            state = state.updated(
                messages=[
                    *[m.model_dump() for m in state.messages],
                    *[
                        GraphMessage(
                            messageId=str(uuid4()),
                            role="assistant",
                            content="old closed investigation narration " * 300,
                        ).model_dump()
                        for _ in range(3)
                    ],
                ]
            )
            state = await Compactor(engine).compact(context, state, signal)
            assert state.summaryVersion == index + 1
            assert state.evidenceRefs == [
                record.evidenceId
            ] and state.candidateRefs == [candidate_id]
            assert state.candidates[0].evidenceIds == [record.evidenceId]
            assert await evidence.get(run, record.evidenceId) == record
            summary = json.loads(
                (
                    await artifacts.content(
                        run.run_id, state.summaryArtifactRef.artifactId
                    )
                ).text
            )
            assert summary["referencedEvidenceIds"] == [record.evidenceId]
            assert summary["referencedCandidateIds"] == [candidate_id]
        state = state.updated(
            messages=[
                *[m.model_dump() for m in state.messages],
                GraphMessage(
                    messageId=str(uuid4()), role="assistant", content="recent tail"
                ).model_dump(),
            ]
        )
        state = await Compactor(engine).compact(context, state, signal)
        assert state.summaryVersion == 3
        summary = json.loads(
            (
                await artifacts.content(run.run_id, state.summaryArtifactRef.artifactId)
            ).text
        )
        assert unknown_id not in summary["referencedArtifactIds"]
        assert summary["sourceRefs"] == [r.model_dump() for r in state.sourceRefs]
        assert unknown_id in summary["summaryText"]
        state, result = await engine.invoke(context, state, signal)
        assert result.toolCalls[0].providerToolCallId == "after-summary-original-id"
        assistant = GraphMessage(
            messageId=str(uuid4()),
            role="assistant",
            content="",
            toolCalls=result.toolCalls,
        )
        gateway = ToolGateway(workspaces.settings, runs, workspaces, artifacts)
        call = result.toolCalls[0]
        envelope = await gateway.call(
            context, call.name, call.arguments, signal, output_budget=8192
        )
        tool = GraphMessage(
            messageId=str(uuid4()),
            role="tool",
            toolCallId=call.toolCallId,
            content=canonical(envelope.model_dump()).decode(),
        )
        state = state.updated(
            messages=[
                *[m.model_dump() for m in state.messages],
                assistant.model_dump(),
                tool.model_dump(),
            ],
            pendingToolCalls=[],
        )
        state, _ = await engine.invoke(context, state, signal)
        facts = await engine.model_calls.list_for_run(run.run_id)
        assert len(sent) == 5 and len(facts) == 5
        assert sum(f.purpose == "COMPACTION" for f in facts) == 3
        assert all(f.status == "LOCAL_CLOSED" and f.requestDigest for f in facts)
        for fact in facts:
            manifest = json.loads(
                (await artifacts.content(run.run_id, fact.manifestRef.artifactId)).text
            )
            assert fact.requestDigest == manifest["requestDigest"]
        assert state.evidenceRefs == [record.evidenceId] and state.candidateRefs == [
            candidate_id
        ]
        assert state.pendingToolCalls == []

    finally:
        await engine.cleanup(run.run_id)
        await workspaces.cleanup(run)
        await checkpoints.close()


async def test_unconfirmed_send_intent_makes_zero_provider_requests(
    database, tmp_path, payload, workspaces
):
    runs = RunStore(database)
    await runs.create_run(payload)
    run, _ = await runs.start_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    checkpoints = Checkpoints(tmp_path / "real-checkpoint.sqlite")
    await checkpoints.open()
    sent = []

    async def reject_commit(_):
        raise OSError("offline database unavailable")

    def server(request):
        sent.append(request)
        return httpx.Response(200, stream=httpx.ByteStream(canonical(response())))

    adapter = DeepSeekAdapter(
        SecretStr("offline-test-key"), transport=httpx.MockTransport(server)
    )
    calls = ModelCallStore(database, commit_transaction=reject_commit)
    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        artifacts,
        checkpoints,
        adapter,
        model_calls=calls,
        state_type=RealReviewGraphState,
    )
    try:
        state = await engine.initial(context, signal)
        with pytest.raises(HarnessError, match="PROVIDER_CALL_STATE_WRITE_UNCERTAIN"):
            await engine.invoke(context, state, signal)
        assert not sent
        assert not await calls.list_for_run(run.run_id)
    finally:
        await engine.cleanup(run.run_id)
        await workspaces.cleanup(run)
        await checkpoints.close()
