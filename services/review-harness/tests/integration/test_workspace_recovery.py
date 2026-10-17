import asyncio
from uuid import uuid4

import pytest

from review_harness.api.app import create_app
from review_harness.domain.snapshot import WorkCancelled
from review_harness.infrastructure.persistence.database import Database, migrate
from review_harness.infrastructure.persistence.store import RunStore
from tests.integration.test_lifecycle import client_for, settings_for


async def test_unattributable_execution_directory_blocks_readiness_without_deleting(
    database, tmp_path, payload
):
    settings = settings_for(database, tmp_path)
    orphan = settings.execution_root / str(uuid4())
    orphan.mkdir()
    (orphan / "foreign.txt").write_text("preserve")
    await database.close()
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        async with client_for(app) as client:
            health = await client.get("/internal/v1/health")
            assert health.status_code == 503
            assert health.json()["checks"]["recovery"] == "NOT_READY"
            assert (
                await client.post("/internal/v1/review-runs", json=payload)
            ).status_code == 503
        assert (orphan / "foreign.txt").read_text() == "preserve"


@pytest.mark.parametrize("state", ["PREPARING", "READY", "CLEANING", "RETAINED"])
@pytest.mark.parametrize("cancelling", [False, True])
async def test_restart_cleans_owned_resources_without_resuming_executor(
    database, tmp_path, payload, workspaces, state, cancelling
):
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    if state == "PREPARING":

        def interrupt(run, budget):
            raise WorkCancelled()

        workspaces.copy_hook = interrupt
        with pytest.raises(WorkCancelled):
            await workspaces.prepare(run, asyncio.Event())
    else:
        await workspaces.prepare(run, asyncio.Event())
        if state == "CLEANING":
            await workspaces.resources.begin_cleanup(run)
        elif state == "RETAINED":
            await workspaces.resources.retain(run, "EXECUTION_WORKSPACE_CLEANUP_FAILED")
    if cancelling:
        await store.request_cancel(run.run_id)
    await workspaces.close(1)
    settings = settings_for(database, tmp_path)
    await database.close()
    for restart in range(2):
        app = create_app(settings)
        async with app.router.lifespan_context(app):
            recovered = await app.state.service.store.get_run(run.run_id)
            assert recovered.status == ("CANCELLED" if cancelling else "FAILED")
            assert recovered.resource_state == "RELEASED"
            assert (
                await app.state.workspaces.resources.get(recovered)
            ).state == "REMOVED"
            assert not app.state.workspaces.has_directories(recovered)
            if restart == 0:
                sequence = recovered.last_event_sequence
            else:
                assert recovered.last_event_sequence == sequence


async def test_directory_without_intention_is_preserved_and_session_isolated(
    database, tmp_path, payload
):
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    settings = settings_for(database, tmp_path)
    directory = settings.execution_root / run.run_id
    directory.mkdir()
    (directory / "foreign.txt").write_text("must remain")
    await database.close()
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        async with client_for(app) as client:
            assert (await client.get("/internal/v1/health")).status_code == 200
            response = await client.get("/internal/v1/review-runs/" + run.run_id)
            assert response.json()["errorCode"] == "REVIEW_RUN_INTEGRITY_ERROR"
        assert (directory / "foreign.txt").read_text() == "must remain"


async def test_batch_one_database_requires_explicit_upgrade_and_keeps_accepted_history(
    tmp_path, payload
):
    url = "sqlite+aiosqlite:///" + (tmp_path / "old.sqlite").as_posix()
    await asyncio.to_thread(migrate, url, "0001_runtime")
    database = Database(url)
    try:
        assert not await database.schema_ready()
        store = RunStore(database)
        original, _ = await store.create_run(payload)
        await database.close()
        await asyncio.to_thread(migrate, url)
        assert await database.schema_ready()
        assert await store.get_run(original.run_id) == original
        assert len(await store.read_events(original.run_id, 0, 10)) == 1
    finally:
        await database.close()
