import asyncio
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import event, select, text
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from review_harness.infrastructure.persistence.tables import (
    artifacts,
    events,
    results,
    runs,
    workspaces,
)

REVISION = "0003_artifacts"
SERVICE_ROOT = Path(__file__).resolve().parents[3]


def migrate(url: str, revision: str = "head") -> None:
    """Explicit operator/test command; never called by application startup."""
    config = Config(str(SERVICE_ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(SERVICE_ROOT / "migrations"))
    config.set_main_option("sqlalchemy.url", url.replace("%", "%%"))
    config.attributes["explicit_url"] = True
    command.upgrade(config, revision)


class Database:
    def __init__(self, url: str):
        self.engine = create_async_engine(
            url, connect_args={"timeout": 5}, poolclass=NullPool
        )
        # One process owns mutations; SQLite still enforces conditional transitions.
        self.write_lock = asyncio.Lock()

        @event.listens_for(self.engine.sync_engine, "connect")
        def configure(connection, _):
            cursor = connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute("PRAGMA busy_timeout=5000")
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.close()

    async def schema_ready(self) -> bool:
        try:
            async with self.engine.connect() as connection:
                await connection.exec_driver_sql("BEGIN")
                versions = (
                    (
                        await connection.execute(
                            text("SELECT version_num FROM alembic_version")
                        )
                    )
                    .scalars()
                    .all()
                )
                if versions != [REVISION]:
                    return False
                # Revision metadata alone cannot prove the schema still exists.
                # Qualified projections validate every required column even on
                # an empty database, without reading rows or repairing anything.
                for table in (runs, events, results, workspaces, artifacts):
                    await connection.execute(select(table).limit(0))
                return True
        except Exception:
            return False

    async def writable(self) -> bool:
        try:
            async with self.write_lock, self.engine.connect() as connection:
                await connection.execute(text("BEGIN IMMEDIATE"))
                await connection.execute(
                    text("UPDATE alembic_version SET version_num=version_num")
                )
                await connection.rollback()
            return True
        except Exception:
            return False

    async def close(self) -> None:
        await self.engine.dispose()
