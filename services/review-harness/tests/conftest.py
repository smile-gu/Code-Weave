import asyncio
from uuid import uuid4

import pytest

from review_harness.infrastructure.persistence.database import Database, migrate


@pytest.fixture
def payload():
    return {
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


@pytest.fixture
async def database(tmp_path):
    url = "sqlite+aiosqlite:///" + (tmp_path / "runs.sqlite").as_posix()
    await asyncio.to_thread(migrate, url)
    db = Database(url)
    yield db
    await db.close()
