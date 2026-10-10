import asyncio
from uuid import uuid4

import pytest

from review_harness.domain.graph import GraphMessage, ToolCall, pending_calls


def message(role="assistant", content="old narration", **kwargs):
    return GraphMessage(messageId=str(uuid4()), role=role, content=content, **kwargs)


def test_compaction_ranges_keep_tool_batch_closed_and_latest_unit():
    from review_harness.runtime.compaction import closed_prefixes

    first, second = str(uuid4()), str(uuid4())
    batch = message(
        toolCalls=[
            ToolCall(toolCallId=first, name="git_diff", arguments={}),
            ToolCall(toolCallId=second, name="glob_files", arguments={"pattern": "**"}),
        ]
    )
    rows = [
        message(),
        batch,
        message("tool", toolCallId=second),
        message("tool", toolCallId=first),
        message(),
    ]
    assert closed_prefixes(rows) == [1, 4]
    assert pending_calls(rows) == []
    assert closed_prefixes(rows[:-2]) == []
    with pytest.raises(ValueError):
        pending_calls(
            [
                batch,
                message("tool", toolCallId=first),
                message("tool", toolCallId=first),
            ]
        )


@pytest.fixture
async def graph_engine(database, tmp_path, payload, workspaces):
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.store import RunStore
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.model_adapter import FakeAdapter
    from tests.integration.test_workspaces import workspace_settings

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    settings = workspace_settings(database, tmp_path)
    artifacts = ArtifactService(settings, database, store, workspaces)
    checkpoints = Checkpoints(tmp_path / "graph.sqlite")
    await checkpoints.open()
    engine = ContextEngine(
        settings,
        store,
        workspaces,
        artifacts,
        checkpoints,
        FakeAdapter(
            responses=[{"content": "Useful clue."}, {"content": "Still useful."}]
        ),
    )
    state = await engine.initial(context, signal)
    try:
        yield engine, context, state, signal
    finally:
        await checkpoints.close()


async def test_two_compactions_keep_platform_anchors_and_full_summary(graph_engine):
    import json

    from review_harness.runtime.compaction import Compactor

    engine, context, state, signal = graph_engine
    from review_harness.domain.graph import ArtifactRef

    material = await engine.artifacts.publish(
        context, "fixed material", "TOOL_OUTPUT", "read_file", signal
    )
    reference = ArtifactRef.model_validate(
        {key: material.wire()[key] for key in ArtifactRef.model_fields}
    )
    state = state.updated(
        artifactRefs=[reference.model_dump()],
        messages=[
            message(content="old long narration " * 300).model_dump() for _ in range(3)
        ],
    )
    old_identity = state.identity()
    compactor = Compactor(engine)
    first = await compactor.compact(context, state, signal)
    assert (
        first.summaryVersion == 1
        and first.identity() == old_identity
        and len(first.messages) == 1
    )
    expanded = first.updated(
        messages=[
            *first.model_dump()["messages"],
            message(content="more narration " * 300).model_dump(),
            message(content="recent tail").model_dump(),
        ]
    )
    second = await compactor.compact(context, expanded, signal)
    assert second.artifactRefs == state.artifactRefs
    assert (
        await engine.artifacts.content(state.runId, reference.artifactId)
    ).text == "fixed material"
    assert second.summaryVersion == 2 and second.compactionCount == 2
    assert (second.userGoalDigest, second.scopeDigest) == (
        state.userGoalDigest,
        state.scopeDigest,
    )
    summary = await engine.artifacts.content(
        state.runId, second.summaryArtifactRef.artifactId
    )
    assert (
        summary.coverage == "FULL"
        and json.loads(summary.text)["previousSummaryVersion"] == 1
    )
    assert len(engine.adapter.calls) == 2
    assert all(
        json.loads(r.wire)["purpose"] == "COMPACTION" for r in engine.adapter.calls
    )


@pytest.mark.parametrize(
    "response,code",
    [
        (
            {"content": "invented", "referencedArtifactIds": [str(uuid4())]},
            "COMPACTION_REFERENCE_INVALID",
        ),
        ({"content": "x" * 12000}, "CONTEXT_RESPONSE_INVALID"),
    ],
)
async def test_failed_summary_never_replaces_valid_view(graph_engine, response, code):
    from review_harness.domain.model import HarnessError
    from review_harness.runtime.compaction import Compactor

    engine, context, state, signal = graph_engine
    engine.adapter.responses = [response]
    state = state.updated(
        messages=[
            message(content="narration " * 300).model_dump(),
            message(content="recent").model_dump(),
        ]
    )
    original = state.model_dump()
    with pytest.raises(HarnessError, match=code):
        await Compactor(engine).compact(context, state, signal)
    assert state.model_dump() == original and state.summaryVersion == 0


@pytest.mark.parametrize(
    "failure",
    [
        "partial_manifest",
        "publish_failure",
        "checkpoint_failure",
        "capability_drift",
        "permission_drift",
    ],
)
async def test_precall_failures_have_zero_model_calls(graph_engine, failure):
    from review_harness.domain.model import HarnessError
    from review_harness.runtime.model_adapter import ModelCapabilities

    engine, context, state, signal = graph_engine
    if failure == "partial_manifest":
        engine.artifacts.settings = engine.settings.model_copy(
            update={"artifact_max_bytes": 100}
        )
        expected = "CONTEXT_MANIFEST_INCOMPLETE"
    elif failure == "publish_failure":

        def fail(directory):
            raise OSError("storage unavailable")

        engine.artifacts.publish_hook = fail
        expected = "ARTIFACT_WRITE_FAILED"
    elif failure == "checkpoint_failure":

        async def fail(*args):
            raise OSError("write rejected")

        engine.checkpoints.saver.aput = fail
        expected = "CHECKPOINT_STATE_WRITE_UNCERTAIN"
    elif failure == "permission_drift":
        from review_harness.runtime.tools import ToolGateway

        engine.gateway = ToolGateway(
            engine.settings, engine.runs, engine.workspaces, engine.artifacts
        )

        async def revoke():
            engine.gateway.allowed = frozenset()

        engine.checkpoints.after_commit = revoke
        expected = "CONTEXT_TOOL_SET_CHANGED"
    else:

        async def change():
            engine.adapter.capabilities = ModelCapabilities(context_window=70000)

        engine.checkpoints.after_commit = change
        expected = "CONTEXT_REQUEST_CHANGED"
    with pytest.raises(HarnessError, match=expected):
        await engine.invoke(context, state, signal)
    assert engine.adapter.calls == []


async def test_no_progress_summary_preserves_old_view(graph_engine):
    from review_harness.domain.model import HarnessError
    from review_harness.runtime.compaction import Compactor

    engine, context, state, signal = graph_engine
    engine.adapter.responses = [{"content": "bloated summary " * 40}]
    state = state.updated(
        messages=[
            message(content="short").model_dump(),
            message(content="tail").model_dump(),
        ]
    )
    with pytest.raises(HarnessError, match="COMPACTION_NO_PROGRESS"):
        await Compactor(engine).compact(context, state, signal)
    assert state.summaryVersion == 0 and state.messages[0].content == "short"
    assert len(engine.adapter.calls) == 1


async def test_cancel_after_manifest_prevents_model_and_safe_checkpoint_commit(
    graph_engine,
):
    from review_harness.domain.snapshot import WorkCancelled

    engine, context, state, signal = graph_engine

    async def cancel_after_commit():
        signal.set()

    engine.checkpoints.after_commit = cancel_after_commit
    with pytest.raises(WorkCancelled):
        await engine.invoke(context, state, signal)
    assert engine.adapter.calls == []
    await engine.cleanup()


async def test_summary_request_uses_smaller_closed_prefix(graph_engine):
    import json

    from review_harness.runtime.compaction import Compactor
    from review_harness.runtime.model_adapter import ModelCapabilities

    engine, context, state, signal = graph_engine
    rows = [message(content="narration " * 280).model_dump() for _ in range(4)]
    state = state.updated(messages=rows)
    prefix = await engine.build(
        context, state, signal, purpose="COMPACTION", messages=state.messages[:1]
    )
    engine.adapter.capabilities = ModelCapabilities(
        context_window=len(prefix.wire) + 1200
    )
    reduced = await Compactor(engine).compact(context, state, signal)
    request = json.loads(engine.adapter.calls[0].wire)
    included = [
        block for block in request["blocks"] if block["kind"] == "untrusted-message"
    ]
    assert len(included) == 1 and len(reduced.messages) == 3
    assert len(engine.adapter.calls[0].wire) <= engine.adapter.capabilities.input_budget


async def test_manifest_corruption_after_checkpoint_prevents_model(graph_engine):
    from review_harness.domain.model import HarnessError

    engine, context, state, signal = graph_engine

    async def corrupt():
        for path in engine.settings.artifact_root.glob("*/*/content"):
            path.write_bytes(b"corrupt")

    engine.checkpoints.after_commit = corrupt
    with pytest.raises(HarnessError, match="ARTIFACT_INTEGRITY_FAILED"):
        await engine.invoke(context, state, signal)
    assert engine.adapter.calls == []


async def test_partial_summary_and_failed_candidate_checkpoint_leave_old_view(
    graph_engine,
):
    from review_harness.domain.model import HarnessError
    from review_harness.runtime.compaction import Compactor
    from review_harness.runtime.model_adapter import ModelCapabilities

    engine, context, state, signal = graph_engine
    state = state.updated(
        messages=[
            message(content="history " * 600).model_dump(),
            message(content="tail").model_dump(),
        ]
    )
    engine.adapter.capabilities = ModelCapabilities(reserved_output=4096)
    engine.adapter.responses = [{"content": "summary " * 300}]
    engine.artifacts.settings = engine.settings.model_copy(
        update={"artifact_max_bytes": 2000}
    )
    original = state.model_dump()
    with pytest.raises(HarnessError, match="COMPACTION_SUMMARY_INCOMPLETE"):
        await Compactor(engine).compact(context, state, signal)
    assert state.model_dump() == original and len(engine.adapter.calls) == 1


async def test_candidate_checkpoint_not_confirmed_never_adopts_summary(graph_engine):
    from review_harness.domain.model import HarnessError
    from review_harness.runtime.compaction import Compactor

    engine, context, state, signal = graph_engine
    state = state.updated(
        messages=[
            message(content="history " * 600).model_dump(),
            message(content="tail").model_dump(),
        ]
    )
    original_aput = engine.checkpoints.saver.aput

    async def fail_candidate(config, checkpoint, metadata, versions):
        if checkpoint["channel_values"]["state"]["summaryVersion"] > 0:
            raise OSError("candidate checkpoint rejected")
        return await original_aput(config, checkpoint, metadata, versions)

    engine.checkpoints.saver.aput = fail_candidate
    with pytest.raises(HarnessError, match="CHECKPOINT_STATE_WRITE_UNCERTAIN"):
        await Compactor(engine).compact(context, state, signal)
    assert state.summaryVersion == 0 and len(state.messages) == 2
    assert len(engine.adapter.calls) == 1


@pytest.mark.parametrize("invalid", ["model", "meter", "window"])
async def test_unknown_model_or_meter_or_window_refuses_call(graph_engine, invalid):
    from dataclasses import replace

    from review_harness.domain.model import HarnessError

    engine, context, state, signal = graph_engine
    changes = {
        "model": {"model_id": "unverified-provider"},
        "meter": {"meter_id": "unverified-tokenizer"},
        "window": {"context_window": 0},
    }[invalid]
    engine.adapter.capabilities = replace(engine.adapter.capabilities, **changes)
    with pytest.raises(HarnessError, match="CONTEXT_MODEL_UNSUPPORTED"):
        await engine.invoke(context, state, signal)
    assert engine.adapter.calls == []


async def test_combined_role_schema_wrapper_and_unicode_exceeds_input_budget(
    graph_engine,
):
    from dataclasses import replace

    from review_harness.domain.model import HarnessError

    engine, context, state, signal = graph_engine
    minimum = await engine.build(context, state, signal, minimum=True)
    caps = engine.adapter.capabilities
    engine.adapter.capabilities = replace(
        caps,
        context_window=len(minimum.wire)
        + caps.reserved_output
        + caps.safety_margin
        + 500,
    )
    state = state.updated(messages=[message(content="中文😀" * 300).model_dump()])
    assert (
        engine.adapter.count_request(minimum)
        <= engine.adapter.capabilities.input_budget
    )
    with pytest.raises(HarnessError, match="CONTEXT_REQUEST_EXCEEDS_BUDGET"):
        await engine.invoke(context, state, signal)
    assert engine.adapter.calls == []
