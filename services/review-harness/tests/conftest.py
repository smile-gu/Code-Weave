import asyncio
from uuid import uuid4

import pytest

from review_harness.infrastructure.persistence.database import Database, migrate
from tests.snapshot_fixture import publish_snapshot


@pytest.fixture
def payload(tmp_path):
    value = {
        "protocolVersion": "1.0",
        "runId": str(uuid4()),
        "codingSessionId": str(uuid4()),
        "reviewThreadId": str(uuid4()),
        "snapshot": {
            "snapshotId": str(uuid4()),
            "manifestSha256": "sha256:" + "a" * 64,
        },
        "input": {"message": "请审查当前累计修改"},
    }
    publish_snapshot(tmp_path / "snapshots", value)
    (tmp_path / "executions").mkdir(exist_ok=True)
    return value


@pytest.fixture
async def database(tmp_path):
    url = "sqlite+aiosqlite:///" + (tmp_path / "runs.sqlite").as_posix()
    await asyncio.to_thread(migrate, url)
    db = Database(url)
    yield db
    await db.close()


@pytest.fixture
async def workspaces(database, tmp_path):
    from review_harness.infrastructure.persistence.workspaces import WorkspaceStore
    from review_harness.infrastructure.workspaces import WorkspaceManager
    from tests.integration.test_workspaces import workspace_settings

    manager = WorkspaceManager(
        workspace_settings(database, tmp_path), WorkspaceStore(database)
    )
    yield manager
    await manager.close(1)
