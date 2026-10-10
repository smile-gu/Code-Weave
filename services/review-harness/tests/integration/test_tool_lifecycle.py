import asyncio
from uuid import uuid4

from review_harness.api.app import create_app
from review_harness.domain.snapshot import WorkCancelled
from tests.integration.network import network_client
from tests.integration.test_lifecycle import settings_for
from tests.snapshot_fixture import publish_snapshot


async def test_supervisor_tool_probe_and_authenticated_artifact_survive_cleanup(
    database, tmp_path, payload
):
    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(tmp_path / "snapshots", payload, {"src/Demo.java": b"x" * 60000})
    settings = settings_for(database, tmp_path).model_copy(
        update={"shutdown_grace_seconds": 5}
    )

    class Probe:
        artifact_id = None

        async def execute(self, context, signal):
            response = await app.state.tools.call(
                context, "read_file", {"path": "src/Demo.java"}, signal
            )
            assert response.status == "OK" and response.artifactRef
            self.artifact_id = response.artifactRef["artifactId"]

        async def cleanup(self, context):
            pass

    probe = Probe()
    app = create_app(settings, executor=probe)
    async with network_client(app) as client:
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 5)
        run = await app.state.service.store.get_run(payload["runId"])
        assert (run.status, run.resource_state) == ("SUCCEEDED", "RELEASED")
        assert not (settings.execution_root / run.run_id).exists()
        result = await client.get(f"/internal/v1/review-runs/{run.run_id}/result")
        assert result.json()["findings"] == []
        path = f"/internal/v1/review-runs/{run.run_id}/artifacts/{probe.artifact_id}"
        metadata = await client.get(path)
        assert metadata.status_code == 200
        assert "path" not in metadata.json()
        page = await client.get(path + "/content")
        assert (
            page.status_code == 200 and len(page.content) <= settings.tool_output_bytes
        )
        assert page.json()["hasMore"]
        assert (await client.get(path + "/content?cursor=forged")).status_code == 422
        assert (
            await client.get(path.replace(run.run_id, str(uuid4())))
        ).status_code == 404
        assert (
            await client.get(path, headers={"Authorization": "Bearer wrong"})
        ).status_code == 401
    reopened = create_app(settings)
    async with network_client(reopened) as client:
        assert (await client.get(path)).status_code == 200
    material = settings.artifact_root / run.run_id / probe.artifact_id / "content"
    material.write_bytes(b"tampered")
    recovered = create_app(settings)
    async with network_client(recovered) as client:
        assert (await client.get(path)).status_code == 503
        assert (await client.get("/internal/v1/health")).json()["status"] == "READY"
        other = dict(payload, runId=str(uuid4()), codingSessionId=str(uuid4()))
        other["snapshot"] = dict(payload["snapshot"], snapshotId=str(uuid4()))
        publish_snapshot(settings.snapshot_root, other)
        assert (
            await client.post("/internal/v1/review-runs", json=other)
        ).status_code == 202
        await asyncio.wait_for(recovered.state.supervisor.join(), 5)
        other_run = await recovered.state.service.store.get_run(other["runId"])
        assert (other_run.status, other_run.resource_state) == ("SUCCEEDED", "RELEASED")


async def test_live_tool_thread_prevents_safe_terminal_during_cancellation(
    database, tmp_path, payload
):
    import threading

    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.persistence.store import RunStore
    from review_harness.infrastructure.persistence.workspaces import WorkspaceStore
    from review_harness.infrastructure.workspaces import WorkspaceManager
    from review_harness.runtime.supervisor import Supervisor
    from review_harness.runtime.tools import ToolGateway

    entered, release = threading.Event(), threading.Event()
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "tool_timeout_seconds": 0.05,
            "workspace_cleanup_seconds": 0.05,
        }
    )
    store = RunStore(database)
    manager = WorkspaceManager(settings, WorkspaceStore(database))
    gateway = ToolGateway(
        settings, store, manager, ArtifactService(settings, database, store, manager)
    )
    original = manager.read_bounded

    def blocked(root, path, limit, budget, *args, **kwargs):
        if path == "src/Demo.java" and entered.is_set() is False:
            entered.set()
            release.wait()
            budget.check()
        return original(root, path, limit, budget, *args, **kwargs)

    class Probe:
        async def execute(self, context, signal):
            manager.read_bounded = blocked
            try:
                await gateway.call(
                    context, "read_file", {"path": "src/Demo.java"}, signal
                )
            except WorkCancelled:
                raise

        async def cleanup(self, context):
            pass

    worker = Supervisor(store, Probe(), 2, manager)
    await store.create_run(payload)
    worker.start()
    try:
        worker.ensure_submitted(payload["runId"])
        assert await asyncio.wait_for(asyncio.to_thread(entered.wait), 5)
        await store.request_cancel(payload["runId"])
        worker.signal_cancel(payload["runId"])
        await asyncio.wait_for(worker.join(), 5)
        run = await store.get_run(payload["runId"])
        assert (run.status, run.resource_state) == ("FAILED", "UNCERTAIN")
        assert (await manager.resources.get(run)).state == "RETAINED"
        assert manager.has_directories(run)
    finally:
        release.set()
        await worker.stop(1)
        await manager.close(1)


async def test_spill_storage_failure_fails_run_without_result_or_reference(
    database, tmp_path, payload
):
    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(tmp_path / "snapshots", payload, {"src/Demo.java": b"x" * 60000})
    settings = settings_for(database, tmp_path)

    class Probe:
        response = None

        async def execute(self, context, signal):
            self.response = await app.state.tools.call(
                context, "read_file", {"path": "src/Demo.java"}, signal
            )

        async def cleanup(self, context):
            pass

    def unavailable_disk(directory):
        raise OSError("simulated storage outage")

    probe = Probe()
    app = create_app(settings, executor=probe)
    async with network_client(app) as client:
        app.state.artifacts.publish_hook = unavailable_disk
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 5)
        run = await app.state.service.store.get_run(payload["runId"])
        assert (run.status, run.resource_state) == ("FAILED", "RELEASED")
        assert run.failure.code == "ARTIFACT_WRITE_FAILED"
        assert probe.response is None
        assert (
            await client.get(f"/internal/v1/review-runs/{run.run_id}/result")
        ).status_code == 409
