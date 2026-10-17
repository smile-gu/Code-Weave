import asyncio
import sqlite3

import pytest

from review_harness.infrastructure.persistence.database import Database, migrate


async def test_empty_database_upgrade_enforces_constraints(tmp_path):
    path = tmp_path / "runs.sqlite"
    url = "sqlite+aiosqlite:///" + path.as_posix()
    await asyncio.to_thread(migrate, url)
    db = Database(url)
    assert await db.schema_ready()
    assert await db.writable()
    await db.close()
    with sqlite3.connect(path) as connection:
        connection.execute("PRAGMA foreign_keys=ON")
        assert connection.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
        assert connection.execute(
            "SELECT version_num FROM alembic_version"
        ).fetchall() == [("0008_review_passes",)]
        assert {
            r[0]
            for r in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        } == {
            "alembic_version",
            "execution_workspaces",
            "review_runs",
            "review_run_events",
            "review_results",
            "review_artifacts",
            "review_model_calls",
            "review_evidence",
            "run_fee_budget",
            "model_call_reservations",
            "review_passes",
        }
        with pytest.raises(sqlite3.IntegrityError):
            connection.execute(
                "INSERT INTO review_run_events VALUES ('missing',1,'REVIEW_RUN_ACCEPTED','x',x'7b7d')"
            )


async def test_startup_does_not_migrate_missing_schema(tmp_path):
    db = Database("sqlite+aiosqlite:///" + (tmp_path / "empty.sqlite").as_posix())
    assert not await db.schema_ready()
    await db.close()
