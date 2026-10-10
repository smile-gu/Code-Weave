import asyncio
import json
from uuid import uuid4

import pytest

from review_harness.domain.graph import ToolCall
from review_harness.runtime.model_adapter import FakeAdapter


@pytest.mark.parametrize(
    "mode",
    [
        "spill",
        "window_recovery",
        "redacted_source",
        "search_medium",
        "publish_failure_medium",
        7000,
        8000,
        10000,
        16000,
        30000,
        33000,
    ],
)
async def test_langgraph_probe_supervisor_spill_and_restart_no_replay(
    database, tmp_path, payload, mode
):
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.store import RunStore
    from review_harness.infrastructure.persistence.workspaces import WorkspaceStore
    from review_harness.infrastructure.workspaces import WorkspaceManager
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.graph import GraphProbeExecutor
    from review_harness.runtime.supervisor import Supervisor
    from review_harness.runtime.tools import ToolGateway
    from tests.integration.test_workspaces import workspace_settings
    from tests.snapshot_fixture import publish_snapshot

    payload["snapshot"]["snapshotId"] = str(uuid4())
    settings = workspace_settings(database, tmp_path)
    source = b"x" * (60000 if mode == "spill" else 6000)
    if isinstance(mode, int):
        source = b"x" * mode
    elif mode == "search_medium":
        source = (b"needle " + b"x" * 40 + b"\n") * 60
    elif mode == "publish_failure_medium":
        source = b"x" * 10000
    if mode == "redacted_source":
        source = b'password = "alpha beta gamma"\nsafe=count\n'
    publish_snapshot(
        settings.snapshot_root,
        payload,
        {"src/Demo.java": source},
    )
    store = RunStore(database)
    manager = WorkspaceManager(settings, WorkspaceStore(database))
    artifacts = ArtifactService(settings, database, store, manager)
    checkpoints = Checkpoints(tmp_path / "graph.sqlite")
    await checkpoints.open()
    call = ToolCall(
        toolCallId=str(uuid4()), name="read_file", arguments={"path": "src/Demo.java"}
    )
    if mode == "search_medium":
        call = ToolCall(
            toolCallId=str(uuid4()), name="search_code", arguments={"query": "needle"}
        )
    if mode == "publish_failure_medium":

        def reject_tool_spill(directory):
            if (
                json.loads((directory / "staging").read_bytes()).get("toolName")
                == "read_file"
            ):
                raise OSError("Tool storage unavailable")

        artifacts.publish_hook = reject_tool_spill
    adapter = FakeAdapter(
        responses=[
            {"content": "Read fixed source.", "toolCalls": [call.model_dump()]},
            {"content": "Done."},
        ]
    )
    if mode == "window_recovery":
        from review_harness.runtime.model_adapter import (
            TestWindowExceeded as WindowExceeded,
        )

        adapter.responses = [
            {"content": "Read first.", "toolCalls": [call.model_dump()]},
            {"content": "Read second.", "toolCalls": [call.model_dump()]},
            WindowExceeded(),
            {"content": "Preserved clue."},
            {"content": "Done."},
        ]
    engine = ContextEngine(settings, store, manager, artifacts, checkpoints, adapter)
    executor = GraphProbeExecutor(
        engine, ToolGateway(settings, store, manager, artifacts)
    )
    worker = Supervisor(store, executor, 2, manager)
    await store.create_run(payload)
    worker.start()
    try:
        worker.ensure_submitted(payload["runId"])
        await asyncio.wait_for(worker.join(), 10)
        run = await store.get_run(payload["runId"])
        if mode == "publish_failure_medium":
            assert (run.status, run.resource_state) == ("FAILED", "RELEASED")
            assert run.failure.code == "ARTIFACT_WRITE_FAILED"
            assert len(adapter.calls) == 1
            assert all(
                message.role != "tool" for message in executor.last_state.messages
            )
            return
        assert (run.status, run.resource_state) == ("SUCCEEDED", "RELEASED"), (
            run.failure
        )
        expected_calls = 5 if mode == "window_recovery" else 2
        assert len(adapter.calls) == expected_calls
        assert executor.last_state.pendingToolCalls == []
        tool = next(m for m in executor.last_state.messages if m.role == "tool")
        if mode == "spill":
            assert tool.artifactRefs
            assert (
                await artifacts.content(run.run_id, tool.artifactRefs[0].artifactId)
            ).hasMore
        elif mode == "window_recovery":
            assert executor.last_state.summaryVersion == 1
            assert executor.last_state.modelCallCount == 5
            ids = [json.loads(request.wire)["modelCallId"] for request in adapter.calls]
            assert len(set(ids)) == 5
            assert json.loads(adapter.calls[3].wire)["purpose"] == "COMPACTION"
        elif isinstance(mode, int) or mode == "search_medium":
            next_request = json.loads(adapter.calls[1].wire)
            model_tool = next(
                block["content"]
                for block in next_request["blocks"]
                if block["role"] == "tool"
            )
            delivered = json.loads(model_tool["content"])
            assert delivered == json.loads(tool.content)
            assert len(tool.content.encode()) <= 8192
            if delivered["artifactRef"] is None:
                assert delivered["data"]["text"] == source.decode()
            else:
                cursor, parts = None, []
                while True:
                    page = await artifacts.content(
                        run.run_id, delivered["artifactRef"]["artifactId"], cursor
                    )
                    parts.append(page.text)
                    cursor = page.nextCursor
                    if cursor is None:
                        break
                saved = json.loads("".join(parts))
                if mode == "search_medium":
                    assert len(saved["data"]["matches"]) == 60
                    assert saved["data"]["scanComplete"]
                    assert len(saved["sourceRefs"]) == 60
                else:
                    assert saved["data"]["text"] == source.decode()
                assert delivered["data"]["preview"]
        else:
            assert "alpha" not in tool.content and "beta" not in tool.content
            assert "safe=count" in tool.content
        assert not (settings.execution_root / run.run_id).exists()
        await worker.stop(1)
        await checkpoints.close()
        await checkpoints.open()
        import pytest

        from review_harness.domain.model import HarnessError

        with pytest.raises(HarnessError, match="CHECKPOINT_REPLAY_FORBIDDEN"):
            await checkpoints.assert_fresh(executor.last_state)
        assert len(adapter.calls) == expected_calls
    finally:
        await worker.stop(1)
        await manager.close(1)
        await checkpoints.close()


async def test_cancelled_stubborn_model_keeps_uncertain_and_blocks_session(
    database, tmp_path, payload
):
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.store import RunStore
    from review_harness.infrastructure.persistence.workspaces import WorkspaceStore
    from review_harness.infrastructure.workspaces import WorkspaceManager
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.graph import GraphProbeExecutor
    from review_harness.runtime.supervisor import Supervisor
    from review_harness.runtime.tools import ToolGateway
    from tests.integration.test_workspaces import workspace_settings

    entered, cancellation_seen, release = (
        asyncio.Event(),
        asyncio.Event(),
        asyncio.Event(),
    )

    async def stubborn(request, signal):
        import json

        if (
            json.loads(request.wire)["blocks"][2]["content"]["runId"]
            != payload["runId"]
        ):
            return
        entered.set()
        try:
            await release.wait()
        except asyncio.CancelledError:
            cancellation_seen.set()
            await release.wait()

    settings = workspace_settings(database, tmp_path).model_copy(
        update={"model_timeout_seconds": 1, "workspace_cleanup_seconds": 0.5}
    )
    store = RunStore(database)
    manager = WorkspaceManager(settings, WorkspaceStore(database))
    artifacts = ArtifactService(settings, database, store, manager)
    checkpoints = Checkpoints(tmp_path / "graph.sqlite")
    await checkpoints.open()
    adapter = FakeAdapter(hook=stubborn)
    engine = ContextEngine(settings, store, manager, artifacts, checkpoints, adapter)
    executor = GraphProbeExecutor(
        engine, ToolGateway(settings, store, manager, artifacts)
    )
    worker = Supervisor(store, executor, 2, manager)
    await store.create_run(payload)
    worker.start()
    try:
        worker.ensure_submitted(payload["runId"])
        await asyncio.wait_for(entered.wait(), 5)
        await store.request_cancel(payload["runId"])
        worker.signal_cancel(payload["runId"])
        await asyncio.wait_for(cancellation_seen.wait(), 5)
        await asyncio.wait_for(worker.join(), 5)
        run = await store.get_run(payload["runId"])
        assert (run.status, run.resource_state) == ("FAILED", "UNCERTAIN")
        assert any(not task.done() for task in engine.tasks)
        assert (await manager.resources.get(run)).state == "RETAINED"
        recreated = WorkspaceManager(settings, WorkspaceStore(database))
        import pytest

        from review_harness.domain.snapshot import WorkspaceError

        with pytest.raises(WorkspaceError, match="EXECUTION_WORKSPACE_CLEANUP_FAILED"):
            await recreated.cleanup(run)
        await recreated.close(1)
        other = dict(payload, runId=str(uuid4()))
        import pytest

        from review_harness.domain.model import HarnessError

        with pytest.raises(HarnessError, match="REVIEW_HARNESS_NOT_READY"):
            await store.create_run(other)
        from tests.snapshot_fixture import publish_snapshot

        independent = dict(payload, runId=str(uuid4()), codingSessionId=str(uuid4()))
        independent["snapshot"] = dict(payload["snapshot"], snapshotId=str(uuid4()))
        publish_snapshot(settings.snapshot_root, independent)
        await store.create_run(independent)
        worker.ensure_submitted(independent["runId"])
        await asyncio.wait_for(worker.join(), 5)
        finished = await store.get_run(independent["runId"])
        assert (finished.status, finished.resource_state) == (
            "SUCCEEDED",
            "RELEASED",
        ), finished.failure
        assert not release.is_set()
    finally:
        release.set()
        await asyncio.gather(*engine.tasks, return_exceptions=True)
        await worker.stop(1)
        await manager.close(1)
        await checkpoints.close()


@pytest.mark.parametrize("name", ["git_diff", "read_artifact"])
async def test_paged_graph_tools_preserve_text_and_cursor_in_every_model_request(
    database, tmp_path, payload, name
):
    from review_harness.domain.model import canonical
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.store import RunStore
    from review_harness.infrastructure.persistence.workspaces import WorkspaceStore
    from review_harness.infrastructure.workspaces import WorkspaceManager
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.graph import GraphProbeExecutor
    from review_harness.runtime.supervisor import Supervisor
    from review_harness.runtime.tools import ToolGateway
    from tests.integration.test_workspaces import workspace_settings
    from tests.snapshot_fixture import digest, publish_snapshot

    settings = workspace_settings(database, tmp_path)
    text = (
        ('diff中文😀"\\\n' * 1200)
        if name == "read_artifact"
        else ('diff中文😀"\\\n' * 500)
    )
    location = publish_snapshot(settings.snapshot_root, payload)
    if name == "git_diff":
        raw = text.encode()
        (location / "artifacts/changes.diff").write_bytes(raw)
        manifest = json.loads((location / "manifest.json").read_bytes())
        manifest["changes"]["diff"].update(byteSize=len(raw), diffSha256=digest(raw))
        raw_manifest = canonical(manifest)
        (location / "manifest.json").write_bytes(raw_manifest)
        payload["snapshot"]["manifestSha256"] = digest(raw_manifest)
    store = RunStore(database)
    manager = WorkspaceManager(settings, WorkspaceStore(database))
    artifacts = ArtifactService(settings, database, store, manager)
    checkpoints = Checkpoints(tmp_path / "graph.sqlite")
    await checkpoints.open()
    adapter = FakeAdapter()
    engine = ContextEngine(settings, store, manager, artifacts, checkpoints, adapter)
    graph = GraphProbeExecutor(engine, ToolGateway(settings, store, manager, artifacts))
    pages = []
    artifact_id = None

    async def observe_and_continue(request, signal):
        nonlocal artifact_id
        wire = json.loads(request.wire)
        results = [
            block["content"] for block in wire["blocks"] if block["role"] == "tool"
        ]
        arguments = {"artifactId": artifact_id} if name == "read_artifact" else {}
        if results:
            output = json.loads(results[-1]["content"])
            assert output["status"] == "OK" and output["artifactRef"] is None
            assert len(results[-1]["content"].encode()) <= 8192
            page = output["data"]
            assert page["text"] and ("nextCursor" in page)
            pages.append(page["text"])
            if not page["hasMore"]:
                assert page["nextCursor"] is None
                adapter.responses = [{"content": "All pages read."}]
                return
            assert page["nextCursor"]
            arguments["cursor"] = page["nextCursor"]
        adapter.responses = [
            {
                "content": "Read the next fixed page.",
                "toolCalls": [
                    ToolCall(
                        toolCallId=str(uuid4()), name=name, arguments=arguments
                    ).model_dump()
                ],
            }
        ]

    adapter.hook = observe_and_continue

    class Probe:
        async def execute(self, context, signal):
            nonlocal artifact_id
            if name == "read_artifact":
                artifact_id = (
                    await artifacts.publish(
                        context, text, "TOOL_OUTPUT", "read_file", signal
                    )
                ).artifact_id
            await graph.execute(context, signal)

        async def cleanup(self, context):
            await graph.cleanup(context)

    worker = Supervisor(store, Probe(), 2, manager)
    await store.create_run(payload)
    worker.start()
    try:
        worker.ensure_submitted(payload["runId"])
        await asyncio.wait_for(worker.join(), 15)
        run = await store.get_run(payload["runId"])
        assert (run.status, run.resource_state) == ("SUCCEEDED", "RELEASED"), (
            run.failure
        )
        assert len(pages) > 1 and "".join(pages) == text
        # Pagination creates no recursive TOOL_OUTPUT or SOURCE_EXCERPT artifacts.
        from sqlalchemy import select

        from review_harness.infrastructure.persistence.tables import artifacts as table

        async with database.engine.connect() as connection:
            kinds = (
                (
                    await connection.execute(
                        select(table.c.kind).where(table.c.run_id == run.run_id)
                    )
                )
                .scalars()
                .all()
            )
        assert kinds.count("TOOL_OUTPUT") == (1 if name == "read_artifact" else 0)
        assert "SOURCE_EXCERPT" not in kinds
    finally:
        await worker.stop(1)
        await manager.close(1)
        await checkpoints.close()
