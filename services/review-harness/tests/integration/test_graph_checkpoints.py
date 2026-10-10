from uuid import uuid4

import pytest

from review_harness.domain.model import HarnessError


async def test_checkpoint_exact_identity_confirmation_and_no_latest_inheritance(
    tmp_path,
):
    from review_harness.domain.graph import ReviewGraphState
    from review_harness.infrastructure.checkpoints import Checkpoints

    ids = {
        name: str(uuid4())
        for name in ("runId", "reviewThreadId", "snapshotId", "investigationId")
    }
    state = ReviewGraphState.model_validate(
        {
            **ids,
            "manifestSha256": "sha256:" + "a" * 64,
            "toolSetDigest": "sha256:" + "b" * 64,
            "userGoalDigest": "sha256:" + "c" * 64,
            "scopeDigest": "sha256:" + "d" * 64,
        }
    )
    checkpoints = Checkpoints(tmp_path / "graph.sqlite")
    await checkpoints.open()
    try:
        ref = await checkpoints.save(state)
        assert await checkpoints.read(state, ref) == state
        other = state.model_copy(update={"runId": str(uuid4())})
        with pytest.raises(HarnessError, match="CHECKPOINT_IDENTITY_MISMATCH"):
            await checkpoints.read(other, ref)
        await checkpoints.assert_fresh(other)
        with pytest.raises(HarnessError, match="CHECKPOINT_REPLAY_FORBIDDEN"):
            await checkpoints.assert_fresh(state)
    finally:
        await checkpoints.close()


async def test_checkpoint_commit_ack_loss_and_corruption_are_checked(tmp_path):
    import sqlite3

    from review_harness.domain.graph import ReviewGraphState
    from review_harness.infrastructure.checkpoints import Checkpoints

    state = ReviewGraphState(
        runId=str(uuid4()),
        reviewThreadId=str(uuid4()),
        snapshotId=str(uuid4()),
        investigationId=str(uuid4()),
        manifestSha256="sha256:" + "a" * 64,
        toolSetDigest="sha256:" + "b" * 64,
        userGoalDigest="sha256:" + "c" * 64,
        scopeDigest="sha256:" + "d" * 64,
    )

    async def lost_ack():
        raise OSError("commit acknowledgement lost")

    checkpoints = Checkpoints(tmp_path / "graph.sqlite", after_commit=lost_ack)
    await checkpoints.open()
    try:
        ref = await checkpoints.save(state)
        assert await checkpoints.read(state, ref) == state
        with sqlite3.connect(checkpoints.path) as conn:
            conn.execute(
                "UPDATE checkpoints SET type=? WHERE checkpoint_id=?", ("pickle", ref)
            )
        with pytest.raises(HarnessError, match="CHECKPOINT_INTEGRITY_FAILED"):
            await checkpoints.read(state, ref)
        other = state.updated(runId=str(uuid4()))
        await checkpoints.assert_fresh(other)
    finally:
        await checkpoints.close()


def test_checkpoint_configuration_rejects_product_alias_and_managed_roots(tmp_path):
    from review_harness.config import Settings

    values = dict(
        internal_bearer_token="valid-internal-credential",
        snapshot_root=tmp_path / "snapshots",
        execution_root=tmp_path / "executions",
        artifact_root=tmp_path / "artifacts",
        database_url="sqlite+aiosqlite:///" + (tmp_path / "runs.sqlite").as_posix(),
    )
    from pydantic import ValidationError

    for path in (
        tmp_path / "runs.sqlite",
        tmp_path / "artifacts" / "graph.sqlite",
        tmp_path / "executions" / "graph.sqlite",
    ):
        with pytest.raises(ValidationError):
            Settings(
                **values,
                checkpoint_database_url="sqlite+aiosqlite:///" + path.as_posix(),
            )


async def test_complete_request_budget_and_manifest_failure_prevent_invocation(
    database, tmp_path, payload, workspaces
):
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.store import RunStore
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.model_adapter import FakeAdapter
    from tests.integration.test_workspaces import workspace_settings

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    import asyncio

    context = await workspaces.prepare(run, asyncio.Event())
    settings = workspace_settings(database, tmp_path)
    artifacts = ArtifactService(settings, database, store, workspaces)
    checkpoints = Checkpoints(tmp_path / "graph.sqlite")
    await checkpoints.open()
    adapter = FakeAdapter(context_window=100)
    engine = ContextEngine(settings, store, workspaces, artifacts, checkpoints, adapter)
    try:
        state = await engine.initial(context, asyncio.Event())
        with pytest.raises(HarnessError, match="CONTEXT_MINIMUM_EXCEEDS_BUDGET"):
            await engine.invoke(context, state, asyncio.Event())
        assert adapter.calls == []
    finally:
        await checkpoints.close()


async def test_model_request_manifest_is_full_and_bound_to_exact_wire(
    database, tmp_path, payload, workspaces
):
    import asyncio
    import json

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
    adapter = FakeAdapter()
    engine = ContextEngine(settings, store, workspaces, artifacts, checkpoints, adapter)
    try:
        state = await engine.initial(context, signal)
        updated, response = await engine.invoke(context, state, signal)
        assert len(adapter.calls) == 1 and updated.modelCallCount == 1
        page = await artifacts.content(
            run.run_id, updated.lastContextManifestRef.artifactId
        )
        manifest = json.loads(page.text)
        assert (
            page.coverage == "FULL"
            and manifest["requestDigest"] == adapter.calls[0].digest
        )
        assert manifest["estimatedInputTokens"] == len(adapter.calls[0].wire)
        assert response.modelCallId == manifest["modelCallId"]
        blocks = json.loads(adapter.calls[0].wire)["blocks"]
        assert [b["role"] for b in blocks] == ["system", "user", "user"]
        assert blocks[1]["content"] == payload["input"]["message"]
    finally:
        await checkpoints.close()


async def test_checkpoint_failure_before_commit_never_adopts_state(tmp_path):
    from review_harness.domain.graph import ReviewGraphState
    from review_harness.infrastructure.checkpoints import Checkpoints

    u = str(uuid4())
    d = "sha256:" + "a" * 64
    state = ReviewGraphState(
        runId=u,
        reviewThreadId=u,
        snapshotId=u,
        investigationId=u,
        manifestSha256=d,
        userGoalDigest=d,
        scopeDigest=d,
        toolSetDigest=d,
    )
    checkpoints = Checkpoints(tmp_path / "graph.sqlite")
    await checkpoints.open()

    async def unavailable(*args):
        raise OSError("failed before insert")

    checkpoints.saver.aput = unavailable
    try:
        with pytest.raises(HarnessError, match="CHECKPOINT_STATE_WRITE_UNCERTAIN"):
            await checkpoints.save(state)
        await checkpoints.assert_fresh(state)
    finally:
        await checkpoints.close()


async def test_graph_capability_diagnostic_is_distinct_from_simulator_health(
    database, tmp_path
):
    from review_harness.api.app import create_app
    from tests.integration.network import network_client
    from tests.integration.test_lifecycle import settings_for

    settings = settings_for(database, tmp_path)
    app = create_app(settings)
    async with network_client(app) as client:
        response = await client.get("/internal/v1/graph-capabilities")
        assert response.json() == {
            "checkpoint": "DISABLED",
            "model": "TEST_ONLY",
            "productionGraphEnabled": False,
        }
        assert (await client.get("/internal/v1/health")).json()["status"] == "READY"
    configured = settings.model_copy(
        update={
            "checkpoint_database_url": "sqlite+aiosqlite:///"
            + (tmp_path / "graph.sqlite").as_posix()
        }
    )
    app = create_app(configured)
    async with network_client(app) as client:
        assert (await client.get("/internal/v1/graph-capabilities")).json()[
            "checkpoint"
        ] == "READY"
        assert (
            await client.get(
                "/internal/v1/graph-capabilities",
                headers={"Authorization": "Bearer wrong"},
            )
        ).status_code == 401
    assert app.state.checkpoints.connection is None


async def test_batch_three_upgrade_preserves_artifact_metadata(tmp_path, payload):
    import asyncio
    import sqlite3

    from review_harness.infrastructure.persistence.database import Database, migrate
    from review_harness.infrastructure.persistence.store import RunStore

    url = "sqlite+aiosqlite:///" + (tmp_path / "old.sqlite").as_posix()
    await asyncio.to_thread(migrate, url, "0003_artifacts")
    database = Database(url)
    store = RunStore(database)
    before, _ = await store.create_run(payload)
    identity = str(uuid4())
    with sqlite3.connect(tmp_path / "old.sqlite") as connection:
        connection.execute(
            "INSERT INTO review_artifacts(artifact_id,run_id,snapshot_id,kind,producer_type,producer_id,media_type,encoding,sha256,coverage,redaction_state,created_at,integrity_state,byte_size) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                identity,
                before.run_id,
                before.snapshot_id,
                "TOOL_OUTPUT",
                "TOOL",
                "read_file:" + str(uuid4()),
                "text/plain",
                "UTF-8",
                "sha256:" + "a" * 64,
                "FULL",
                "NONE",
                before.created_at,
                "READY",
                1,
            ),
        )
    await database.close()
    await asyncio.to_thread(migrate, url)
    try:
        assert await database.schema_ready()
        assert await store.get_run(before.run_id) == before
        with sqlite3.connect(tmp_path / "old.sqlite") as connection:
            assert connection.execute(
                "SELECT kind FROM review_artifacts WHERE artifact_id=?", (identity,)
            ).fetchone() == ("TOOL_OUTPUT",)
    finally:
        await database.close()


async def test_same_thread_other_snapshot_and_mutated_metadata_cannot_load(tmp_path):
    import json
    import sqlite3

    from review_harness.domain.graph import ReviewGraphState
    from review_harness.infrastructure.checkpoints import Checkpoints

    u = str(uuid4())
    d = "sha256:" + "a" * 64
    state = ReviewGraphState(
        runId=u,
        reviewThreadId=u,
        snapshotId=u,
        investigationId=u,
        manifestSha256=d,
        userGoalDigest=d,
        scopeDigest=d,
        toolSetDigest=d,
    )
    checkpoints = Checkpoints(tmp_path / "graph.sqlite")
    await checkpoints.open()
    try:
        ref = await checkpoints.save(state)
        other = state.updated(snapshotId=str(uuid4()))
        with pytest.raises(HarnessError, match="CHECKPOINT_NOT_FOUND"):
            await checkpoints.read(other, ref)
        with sqlite3.connect(checkpoints.path) as connection:
            row = connection.execute(
                "SELECT metadata FROM checkpoints WHERE checkpoint_id=?", (ref,)
            ).fetchone()
            metadata = json.loads(row[0])
            metadata["snapshotId"] = str(uuid4())
            connection.execute(
                "UPDATE checkpoints SET metadata=? WHERE checkpoint_id=?",
                (json.dumps(metadata), ref),
            )
        with pytest.raises(HarnessError, match="CHECKPOINT_INTEGRITY_FAILED"):
            await checkpoints.read(state, ref)
    finally:
        await checkpoints.close()


async def test_checkpoint_capability_detects_missing_table_without_repair(tmp_path):
    import json
    import sqlite3
    from pathlib import Path

    from review_harness.domain.graph import ReviewGraphState
    from review_harness.infrastructure.checkpoints import Checkpoints

    checkpoints = Checkpoints(tmp_path / "graph.sqlite")
    await checkpoints.open()
    try:
        fixture = json.loads(
            (
                Path(__file__).resolve().parents[4]
                / "contracts/fixtures/review-context-v1/examples.json"
            ).read_bytes()
        )
        state = ReviewGraphState.model_validate(
            fixture["review-graph-state-v1"]["positive"]
        )
        reference = await checkpoints.save(state)
        assert await checkpoints.ready()
        with sqlite3.connect(checkpoints.path) as connection:
            connection.execute("DROP TABLE writes")
        assert not await checkpoints.ready()
        with pytest.raises(HarnessError, match="CHECKPOINT_INTEGRITY_FAILED"):
            await checkpoints.read(state, reference)
        with sqlite3.connect(checkpoints.path) as connection:
            assert (
                connection.execute(
                    "SELECT name FROM sqlite_master WHERE name='writes'"
                ).fetchone()
                is None
            )
    finally:
        await checkpoints.close()
