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


@pytest.mark.parametrize(
    "conservative_input,should_compact", [(205619, False), (205620, True)]
)
async def test_256k_soft_watermark_triggers_before_hard_budget(
    conservative_input, should_compact
):
    from types import SimpleNamespace

    from review_harness.runtime.compaction import Compactor

    state = SimpleNamespace(messages=[message(), message()])

    async def build(*args, **kwargs):
        return object()

    engine = SimpleNamespace(
        build=build,
        input_cost=lambda request: conservative_input,
        settings=SimpleNamespace(compaction_attempts=2),
        adapter=SimpleNamespace(
            capabilities=SimpleNamespace(
                input_budget=257024, meter_id="V41_REFERENCE_LOCAL_TOKENS"
            )
        ),
    )
    compactor = Compactor(engine)

    async def observe_compaction(*args):
        raise RuntimeError("SOFT_WATERMARK_COMPACTION_REQUESTED")

    compactor.compact = observe_compaction
    if should_compact:
        with pytest.raises(RuntimeError, match="SOFT_WATERMARK_COMPACTION_REQUESTED"):
            await compactor.fit(None, state, asyncio.Event())
    else:
        assert await compactor.fit(None, state, asyncio.Event()) is state


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
    from review_harness.domain.model import HarnessError

    # A smaller summary request is possible, but its remaining tail still
    # exceeds the final request budget. Do not replace the valid view early.
    with pytest.raises(HarnessError, match="CONTEXT_REQUEST_EXCEEDS_BUDGET"):
        await Compactor(engine).compact(context, state, signal)
    request = json.loads(engine.adapter.calls[0].wire)
    included = [
        block for block in request["blocks"] if block["kind"] == "untrusted-message"
    ]
    assert len(included) == 1
    assert state.summaryVersion == 0 and len(state.messages) == 4
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


async def test_summary_and_retained_state_share_prefix_only_sources(graph_engine):
    import json

    from review_harness.domain.graph import ArtifactRef
    from review_harness.domain.model import sha256
    from review_harness.runtime.compaction import Compactor

    engine, context, state, signal = graph_engine
    material = await engine.artifacts.publish(
        context, "fixed material", "TOOL_OUTPUT", "read_file", signal
    )
    reference = {key: material.wire()[key] for key in ArtifactRef.model_fields}
    source = {
        "path": "src/Demo.java",
        "contentSha256": sha256(b"class Demo {}\n"),
        "startLine": 1,
        "endLine": 1,
    }
    state = state.updated(
        messages=[
            message(
                content="closed history " * 300,
                artifactRefs=[reference],
                sourceRefs=[source],
            ).model_dump(),
            message(content="recent tail").model_dump(),
        ]
    )
    compacted = await Compactor(engine).compact(context, state, signal)
    summary = json.loads(
        (
            await engine.artifacts.content(
                state.runId, compacted.summaryArtifactRef.artifactId
            )
        ).text
    )
    assert summary["sourceRefs"] == [r.model_dump() for r in compacted.sourceRefs]
    assert len(summary["sourceRefs"]) == 1
    assert summary["referencedArtifactIds"] == [material.artifact_id]
    assert [r.artifactId for r in compacted.artifactRefs] == [material.artifact_id]
    assert summary["sourceMessageIds"] == [state.messages[0].messageId]
    assert compacted.messages == state.messages[1:]
    expanded = compacted.updated(
        sourceRefs=[],
        messages=[
            *[m.model_dump() for m in compacted.messages],
            message(
                content="more closed history " * 300, artifactRefs=[reference]
            ).model_dump(),
            message(content="latest tail").model_dump(),
        ],
    )
    second = await Compactor(engine).compact(context, expanded, signal)
    previous = json.loads(
        (
            await engine.artifacts.content(
                state.runId, second.summaryArtifactRef.artifactId
            )
        ).text
    )
    assert (
        previous["sourceRefs"]
        == summary["sourceRefs"]
        == [r.model_dump() for r in second.sourceRefs]
    )
    assert previous["referencedArtifactIds"] == [material.artifact_id]
    assert second.messages == expanded.messages[-1:]


@pytest.mark.parametrize(
    "case,code",
    [
        ("hash", "CONTEXT_REFERENCE_INVALID"),
        ("artifact", "ARTIFACT_INTEGRITY_FAILED"),
        ("overflow", "COMPACTION_REFERENCE_LIMIT_EXCEEDED"),
        ("pending", "COMPACTION_UNCLOSED_TOOLS"),
    ],
)
async def test_platform_source_failures_preserve_old_view_before_dispatch(
    graph_engine, case, code
):
    from review_harness.domain.graph import ArtifactRef
    from review_harness.domain.model import HarnessError, sha256
    from review_harness.runtime.compaction import Compactor

    engine, context, state, signal = graph_engine
    source = {
        "path": "src/Demo.java",
        "contentSha256": sha256(b"class Demo {}\n"),
        "startLine": 1,
        "endLine": 1,
    }
    refs = []
    sources = [source]
    if case == "hash":
        sources = [{**source, "contentSha256": "sha256:" + "f" * 64}]
    if case == "artifact":
        material = await engine.artifacts.publish(
            context, "fixed material", "TOOL_OUTPUT", "read_file", signal
        )
        refs = [{key: material.wire()[key] for key in ArtifactRef.model_fields}]
        (
            engine.settings.artifact_root
            / state.runId
            / material.artifact_id
            / "content"
        ).write_bytes(b"corrupt")
    if case == "overflow":
        sources = [{**source, "startLine": i + 1, "endLine": i + 1} for i in range(32)]
        other = [
            {**source, "startLine": i + 1, "endLine": i + 1} for i in range(32, 64)
        ]
        extra = {**source, "startLine": 65, "endLine": 65}
        state = state.updated(sourceRefs=[extra])
    rows = [
        message(
            content="closed narration " * 300, sourceRefs=sources, artifactRefs=refs
        ).model_dump()
    ]
    if case == "overflow":
        rows.append(
            message(content="closed narration " * 300, sourceRefs=other).model_dump()
        )
    rows.append(message(content="recent tail").model_dump())
    if case == "pending":
        call = ToolCall(toolCallId=str(uuid4()), name="git_diff", arguments={})
        rows.append(message(content="", toolCalls=[call]).model_dump())
        state = state.updated(messages=rows, pendingToolCalls=[call.toolCallId])
    else:
        state = state.updated(messages=rows)
    original = state.model_dump()
    with pytest.raises(HarnessError, match=code):
        await Compactor(engine).compact(context, state, signal)
    assert state.model_dump() == original and engine.adapter.calls == []


async def test_compaction_does_not_import_other_run_artifact(graph_engine):
    import json

    from review_harness.domain.graph import ArtifactRef
    from review_harness.domain.model import HarnessError
    from review_harness.runtime.compaction import Compactor
    from tests.snapshot_fixture import publish_snapshot

    engine, context, state, signal = graph_engine
    payload = json.loads(context.run.request_payload)
    payload.update(
        runId=str(uuid4()), codingSessionId=str(uuid4()), reviewThreadId=str(uuid4())
    )
    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(engine.settings.snapshot_root, payload)
    await engine.runs.create_run(payload)
    other, _ = await engine.runs.start_run(payload["runId"])
    other_context = await engine.workspaces.prepare(other, signal)
    try:
        material = await engine.artifacts.publish(
            other_context, "other Run material", "TOOL_OUTPUT", "read_file", signal
        )
        ref = {key: material.wire()[key] for key in ArtifactRef.model_fields}
        state = state.updated(
            messages=[
                message(
                    content="closed history " * 300, artifactRefs=[ref]
                ).model_dump(),
                message(content="latest tail").model_dump(),
            ]
        )
        original = state.model_dump()
        with pytest.raises(HarnessError, match="ARTIFACT_NOT_FOUND"):
            await Compactor(engine).compact(context, state, signal)
        assert state.model_dump() == original and engine.adapter.calls == []
    finally:
        await engine.workspaces.cleanup(other)


async def test_platform_summary_redacts_model_text_before_publishing(graph_engine):
    from review_harness.runtime.compaction import Compactor

    engine, context, state, signal = graph_engine
    engine.adapter.responses = [
        {"content": 'Investigated clue. password = "private phrase"'}
    ]
    state = state.updated(
        messages=[
            message(content="closed narration " * 300).model_dump(),
            message(content="tail").model_dump(),
        ]
    )
    compacted = await Compactor(engine).compact(context, state, signal)
    page = await engine.artifacts.content(
        state.runId, compacted.summaryArtifactRef.artifactId
    )
    assert "private phrase" not in page.text and "[REDACTED]" in page.text
    assert page.redactionState == "APPLIED" and compacted.summaryVersion == 1
