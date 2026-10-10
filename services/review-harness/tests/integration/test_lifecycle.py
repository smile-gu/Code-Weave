import asyncio
import copy
from uuid import uuid4

import httpx
import pytest
from sqlalchemy import text

from review_harness.api.app import create_app
from review_harness.config import Settings
from review_harness.infrastructure.persistence.database import Database
from review_harness.infrastructure.persistence.store import RunStore
from tests.integration.test_supervisor import ControlledExecutor


def settings_for(database, tmp_path):
    for name in ("snapshots", "executions", "artifacts"):
        (tmp_path / name).mkdir(exist_ok=True)
    return Settings(
        internal_bearer_token="test-only-credential",
        snapshot_root=tmp_path / "snapshots",
        execution_root=tmp_path / "executions",
        artifact_root=tmp_path / "artifacts",
        database_url=str(database.engine.url),
        shutdown_grace_seconds=0.05,
    )


def client_for(app):
    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
        headers={"Authorization": "Bearer test-only-credential"},
    )


@pytest.mark.parametrize(
    "state,expected",
    [("PENDING", "SUCCEEDED"), ("RUNNING", "FAILED"), ("CANCELLING", "CANCELLED")],
)
async def test_rebuilt_app_recovers_file_database_without_restarting_old_execution(
    database, tmp_path, payload, state, expected
):
    store = RunStore(database)
    await store.create_run(payload)
    if state != "PENDING":
        await store.start_run(payload["runId"])
    if state == "CANCELLING":
        await store.request_cancel(payload["runId"])
    settings = settings_for(database, tmp_path)
    await database.close()
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        await asyncio.wait_for(app.state.supervisor.join(), 2)
        async with client_for(app) as client:
            assert (await client.get("/internal/v1/health")).status_code == 200
            run = (
                await client.get("/internal/v1/review-runs/" + payload["runId"])
            ).json()
            assert run["status"] == expected and run["resourceState"] == "RELEASED"
            if state == "RUNNING":
                assert run["failure"]["code"] == "REVIEW_RUN_INTERRUPTED"
        sequence = run["lastEventSequence"]
    second = create_app(settings)
    async with second.router.lifespan_context(second):
        async with client_for(second) as client:
            recovered = (
                await client.get("/internal/v1/review-runs/" + payload["runId"])
            ).json()
            assert recovered["lastEventSequence"] == sequence


@pytest.mark.parametrize(
    "fault",
    ["wrong_revision", "missing_schema", "physical_corruption", "snapshot_missing"],
)
async def test_readiness_refuses_global_storage_and_snapshot_faults(
    database, tmp_path, fault
):
    settings = settings_for(database, tmp_path)
    if fault == "wrong_revision":
        async with database.engine.begin() as connection:
            await connection.execute(
                text("UPDATE alembic_version SET version_num='future'")
            )
    elif fault == "missing_schema":
        settings = settings.model_copy(
            update={
                "database_url": "sqlite+aiosqlite:///"
                + (tmp_path / "empty.sqlite").as_posix()
            }
        )
    elif fault == "physical_corruption":
        path = tmp_path / "corrupt.sqlite"
        path.write_bytes(b"not a SQLite database")
        settings = settings.model_copy(
            update={"database_url": "sqlite+aiosqlite:///" + path.as_posix()}
        )
    else:
        settings = settings.model_copy(update={"snapshot_root": tmp_path / "missing"})
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        async with client_for(app) as client:
            response = await client.get("/internal/v1/health")
            assert response.status_code == 503
            assert (
                response.json()["status"] == "NOT_READY"
                and not response.json()["acceptingRuns"]
            )
            assert (
                "Traceback" not in response.text and str(tmp_path) not in response.text
            )


async def test_corrupt_run_is_isolated_while_other_sessions_remain_ready(
    database, tmp_path, payload
):
    store = RunStore(database)
    await store.create_run(payload)
    async with database.engine.begin() as connection:
        await connection.execute(
            text("UPDATE review_runs SET resource_state='RELEASED' WHERE run_id=:id"),
            {"id": payload["runId"]},
        )
    settings = settings_for(database, tmp_path)
    await database.close()
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        async with client_for(app) as client:
            assert (await client.get("/internal/v1/health")).status_code == 200
            root = "/internal/v1/review-runs/" + payload["runId"]
            for path in [root, root + "/events", root + "/result"]:
                assert (await client.get(path)).json()[
                    "errorCode"
                ] == "REVIEW_RUN_INTEGRITY_ERROR"
            same_session = copy.deepcopy(payload)
            same_session["runId"] = str(uuid4())
            assert (
                await client.post("/internal/v1/review-runs", json=same_session)
            ).status_code == 500
            same_session["codingSessionId"] = str(uuid4())
            assert (
                await client.post("/internal/v1/review-runs", json=same_session)
            ).status_code == 202


async def test_graceful_shutdown_leaves_pending_for_next_app(
    database, tmp_path, payload
):
    # This case proves graceful cleanup, not expiration of the shutdown budget.
    # Real SQLite/file cleanup must have the production grace budget under load.
    settings = settings_for(database, tmp_path).model_copy(
        update={"shutdown_grace_seconds": 5}
    )
    executor = ControlledExecutor()
    app = create_app(settings, executor=executor)
    pending = copy.deepcopy(payload)
    pending["runId"] = str(uuid4())
    async with app.router.lifespan_context(app):
        async with client_for(app) as client:
            await client.post("/internal/v1/review-runs", json=payload)
            await asyncio.wait_for(executor.entered.wait(), 2)
            await client.post("/internal/v1/review-runs", json=pending)
    restarted = create_app(settings)
    async with restarted.router.lifespan_context(restarted):
        await asyncio.wait_for(restarted.state.supervisor.join(), 2)
        assert (
            await restarted.state.service.store.get_run(pending["runId"])
        ).status == "SUCCEEDED"
        assert (
            await restarted.state.service.store.get_run(payload["runId"])
        ).status == "FAILED"


@pytest.mark.parametrize(
    "mutation",
    [
        "UPDATE review_runs SET created_at='invalid'",
        "UPDATE review_runs SET status='UNKNOWN'",
        "UPDATE review_run_events SET payload=x'6e756c6c'",
        "UPDATE review_run_events SET event_type='ACCEPTED'",
        "DELETE FROM review_run_events",
    ],
)
async def test_each_logically_corrupt_history_is_isolated_on_restart(
    database, tmp_path, payload, mutation
):
    store = RunStore(database)
    await store.create_run(payload)
    async with database.engine.begin() as connection:
        await connection.execute(text("PRAGMA ignore_check_constraints=ON"))
        await connection.execute(text(mutation))
    settings = settings_for(database, tmp_path)
    await database.close()
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        async with client_for(app) as client:
            assert (await client.get("/internal/v1/health")).status_code == 200
            response = await client.get("/internal/v1/review-runs/" + payload["runId"])
            assert response.status_code == 500
            assert response.json()["errorCode"] == "REVIEW_RUN_INTEGRITY_ERROR"


async def test_uncertain_resources_block_only_associated_session_until_confirmation(
    database, tmp_path, payload, workspaces
):
    from review_harness.domain.model import Failure

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    context = await workspaces.prepare(run, asyncio.Event())
    owner = context.workspace_root / "owner.json"
    original_owner = owner.read_bytes()
    owner.write_bytes(b"{}")
    await store.fail_cleanup_uncertain(
        payload["runId"], Failure.make("REVIEW_RESOURCE_CLEANUP_FAILED")
    )
    app = create_app(settings_for(database, tmp_path))
    async with app.router.lifespan_context(app):
        async with client_for(app) as client:
            same_session = copy.deepcopy(payload)
            same_session["runId"] = str(uuid4())
            assert (
                await client.post("/internal/v1/review-runs", json=same_session)
            ).status_code == 503
            assert (await client.get("/internal/v1/health")).status_code == 200
            owner.write_bytes(original_owner)
            failed = await app.state.service.store.get_run(payload["runId"])
            await app.state.workspaces.cleanup(failed)
            await app.state.service.store.confirm_resources_released(payload["runId"])
            assert (
                await client.post("/internal/v1/review-runs", json=same_session)
            ).status_code == 202


async def test_shutdown_timeout_preserves_unconfirmed_running_for_recovery(
    database, tmp_path, payload
):
    class UnresponsiveSimulator(ControlledExecutor):
        async def execute(self, run, cancel):
            self.entered.set()
            await self.release.wait()

    executor = UnresponsiveSimulator()
    settings = settings_for(database, tmp_path)
    app = create_app(settings, executor=executor)
    async with app.router.lifespan_context(app):
        async with client_for(app) as client:
            await client.post("/internal/v1/review-runs", json=payload)
            await asyncio.wait_for(executor.entered.wait(), 2)
    check = Database(settings.database_url)
    try:
        assert (await RunStore(check).get_run(payload["runId"])).status == "RUNNING"
    finally:
        await check.close()
    restarted = create_app(settings)
    async with restarted.router.lifespan_context(restarted):
        run = await restarted.state.service.store.get_run(payload["runId"])
        assert run.status == "FAILED" and run.failure_code == "REVIEW_RUN_INTERRUPTED"
