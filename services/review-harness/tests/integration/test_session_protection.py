import asyncio
import copy
from uuid import uuid4

import pytest
from sqlalchemy import text

from review_harness.api.app import create_app
from review_harness.domain.model import Failure, HarnessError
from review_harness.infrastructure.persistence.store import RunStore
from review_harness.runtime.supervisor import Supervisor
from tests.integration.test_lifecycle import client_for, settings_for


class RecordingExecutor:
    def __init__(self):
        self.calls = []

    async def execute(self, run, cancel):
        self.calls.append(run.run_id)

    async def cleanup(self, run):
        pass


def another_run(payload, *, same_session=True, snapshot_root=None):
    value = copy.deepcopy(payload)
    value["runId"] = str(uuid4())
    if not same_session:
        value["codingSessionId"] = str(uuid4())
        value["snapshot"]["snapshotId"] = str(uuid4())
        from tests.snapshot_fixture import publish_snapshot

        publish_snapshot(snapshot_root, value)
    return value


async def test_queued_run_cannot_start_under_uncertain_session_protection(
    database, payload, workspaces
):
    store = RunStore(database)
    first, _ = await store.create_run(payload)
    queued, _ = await store.create_run(another_run(payload))
    independent, _ = await store.create_run(
        another_run(
            payload, same_session=False, snapshot_root=workspaces.settings.snapshot_root
        )
    )
    executor = RecordingExecutor()
    worker = Supervisor(store, executor, capacity=2, workspaces=workspaces)
    worker.ensure_submitted(queued.run_id)
    worker.ensure_submitted(independent.run_id)
    await store.start_run(first.run_id)
    await store.fail_cleanup_uncertain(
        first.run_id, Failure.make("REVIEW_RESOURCE_CLEANUP_FAILED")
    )
    worker.start()
    try:
        await asyncio.wait_for(worker.join(), 2)
        assert executor.calls == [independent.run_id]
        assert worker.healthy
        assert (await store.get_run(queued.run_id)) == queued
        assert [
            e["eventType"] for e in await store.read_events(queued.run_id, 0, 10)
        ] == ["REVIEW_RUN_ACCEPTED"]
        assert (await store.get_run(first.run_id)).resource_state == "UNCERTAIN"
        with pytest.raises(HarnessError):
            await store.start_run(queued.run_id)
        # The pending task becomes eligible only after durable resource confirmation.
        await store.confirm_resources_released(first.run_id)
        worker.ensure_submitted(queued.run_id)
        await asyncio.wait_for(worker.join(), 2)
        assert executor.calls == [independent.run_id, queued.run_id]
    finally:
        await worker.stop(1)


@pytest.mark.parametrize("pending_first", [False, True])
async def test_recovery_blocks_whole_corrupt_session_in_either_scan_order(
    database, tmp_path, payload, pending_first, workspaces
):
    store = RunStore(database)
    payload["runId"] = (
        "00000000-0000-0000-0000-000000000002"
        if pending_first
        else "00000000-0000-0000-0000-000000000001"
    )
    first, _ = await store.create_run(payload)
    pending = another_run(payload)
    pending["runId"] = (
        "00000000-0000-0000-0000-000000000001"
        if pending_first
        else "00000000-0000-0000-0000-000000000002"
    )
    queued, _ = await store.create_run(pending)
    independent, _ = await store.create_run(
        another_run(
            payload, same_session=False, snapshot_root=workspaces.settings.snapshot_root
        )
    )
    async with database.engine.begin() as connection:
        await connection.execute(
            text("UPDATE review_runs SET created_at='bad' WHERE run_id=:id"),
            {"id": first.run_id},
        )
    settings = settings_for(database, tmp_path)
    await database.close()
    executor = RecordingExecutor()
    app = create_app(settings, executor=executor)
    async with app.router.lifespan_context(app):
        await asyncio.wait_for(app.state.supervisor.join(), 2)
        assert executor.calls == [independent.run_id]
        recovered_store = app.state.service.store
        assert (await recovered_store.get_run(queued.run_id)) == queued
        assert [
            e["eventType"]
            for e in await recovered_store.read_events(queued.run_id, 0, 10)
        ] == ["REVIEW_RUN_ACCEPTED"]
        with pytest.raises(HarnessError):
            await recovered_store.start_run(queued.run_id)
        async with client_for(app) as client:
            assert (await client.get("/internal/v1/health")).status_code == 200
            response = await client.get("/internal/v1/review-runs/" + first.run_id)
            assert response.json()["errorCode"] == "REVIEW_RUN_INTEGRITY_ERROR"
