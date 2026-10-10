import re
from dataclasses import asdict, dataclass
from uuid import uuid4

from sqlalchemy import insert, select, update

from review_harness.domain.model import (
    HarnessError,
    integrity_error,
    utc_now,
    valid_time,
    valid_uuid,
)
from review_harness.infrastructure.persistence.store import commit
from review_harness.infrastructure.persistence.tables import runs, workspaces


@dataclass(frozen=True)
class WorkspaceRecord:
    run_id: str
    snapshot_id: str
    manifest_sha256: str
    relative_directory: str
    state: str
    created_at: str
    updated_at: str
    failure_code: str | None
    diagnostic_id: str | None

    def validate(self, run):
        if (
            self.run_id != run.run_id
            or self.relative_directory != run.run_id
            or self.snapshot_id != run.snapshot_id
            or self.manifest_sha256 != run.manifest_sha256
            or self.state
            not in {"PREPARING", "READY", "CLEANING", "REMOVED", "RETAINED"}
            or not valid_time(self.created_at)
            or not valid_time(self.updated_at)
            or self.created_at > self.updated_at
            or not run.started_at
            or self.created_at < run.started_at
            or (self.failure_code is None) != (self.diagnostic_id is None)
        ):
            raise integrity_error()
        if self.diagnostic_id and not valid_uuid(self.diagnostic_id):
            raise integrity_error()
        if self.failure_code is not None and (
            not isinstance(self.failure_code, str)
            or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", self.failure_code)
        ):
            raise integrity_error()


class WorkspaceStore:
    """Resource-specific commands; no directory is touched before intention commit."""

    def __init__(self, database, commit_transaction=commit):
        self.database = database
        self.commit_transaction = commit_transaction

    async def assert_attributable(self, directory_ids):
        """Unknown ownership blocks global recovery; never infer a deletion target."""
        async with self.database.engine.connect() as connection:
            orphan = await connection.execute(
                select(workspaces.c.run_id)
                .outerjoin(runs, workspaces.c.run_id == runs.c.run_id)
                .where(runs.c.run_id.is_(None))
                .limit(1)
            )
            if orphan.first() is not None:
                raise integrity_error()
            for run_id in directory_ids:
                if not valid_uuid(run_id):
                    raise integrity_error()
                owner = await connection.execute(
                    select(runs.c.run_id).where(runs.c.run_id == run_id)
                )
                if owner.first() is None:
                    raise integrity_error()

    async def get(self, run):
        async with self.database.engine.connect() as connection:
            row = (
                (
                    await connection.execute(
                        select(workspaces).where(workspaces.c.run_id == run.run_id)
                    )
                )
                .mappings()
                .first()
            )
        if row is None:
            return None
        try:
            record = WorkspaceRecord(**row)
            record.validate(run)
            return record
        except (ValueError, TypeError):
            raise integrity_error() from None

    async def prepare_intent(self, run):
        if run.status != "RUNNING" or run.resource_state != "ACTIVE":
            raise integrity_error()
        return await self._save(run, "PREPARING", None, create=True)

    async def prepared(self, run):
        return await self._save(run, "READY", {"PREPARING"})

    async def begin_cleanup(self, run):
        return await self._save(
            run, "CLEANING", {"PREPARING", "READY", "CLEANING", "RETAINED"}
        )

    async def removed(self, run):
        return await self._save(run, "REMOVED", {"CLEANING"})

    async def retain(self, run, code):
        return await self._save(
            run,
            "RETAINED",
            {"PREPARING", "READY", "CLEANING", "RETAINED", "REMOVED"},
            failure=code,
        )

    async def _save(self, run, target, sources, *, create=False, failure=None):
        expected = None
        async with self.database.write_lock:
            try:
                async with self.database.engine.connect() as connection:
                    await connection.exec_driver_sql("BEGIN IMMEDIATE")
                    row = (
                        (
                            await connection.execute(
                                select(workspaces).where(
                                    workspaces.c.run_id == run.run_id
                                )
                            )
                        )
                        .mappings()
                        .first()
                    )
                    if row is None:
                        if not create:
                            raise integrity_error()
                        current = (
                            await connection.execute(
                                select(runs.c.status, runs.c.resource_state).where(
                                    runs.c.run_id == run.run_id
                                )
                            )
                        ).one()
                        if tuple(current) not in {
                            ("RUNNING", "ACTIVE"),
                            ("CANCELLING", "RELEASING"),
                        }:
                            raise integrity_error()
                        at = max(utc_now(), run.updated_at)
                        expected = WorkspaceRecord(
                            run.run_id,
                            run.snapshot_id,
                            run.manifest_sha256,
                            run.run_id,
                            target,
                            at,
                            at,
                            None,
                            None,
                        )
                        await connection.execute(
                            insert(workspaces).values(**asdict(expected))
                        )
                    else:
                        previous = WorkspaceRecord(**row)
                        previous.validate(run)
                        if create:
                            if previous.state != "PREPARING":
                                raise integrity_error()
                            await connection.rollback()
                            return previous
                        if previous.state not in sources:
                            if previous.state == target:
                                await connection.rollback()
                                return previous
                            raise integrity_error()
                        values = asdict(previous)
                        values.update(
                            state=target, updated_at=max(utc_now(), previous.updated_at)
                        )
                        if failure:
                            values.update(
                                failure_code=failure, diagnostic_id=str(uuid4())
                            )
                        expected = WorkspaceRecord(**values)
                        await connection.execute(
                            update(workspaces)
                            .where(
                                workspaces.c.run_id == run.run_id,
                                workspaces.c.state == previous.state,
                            )
                            .values(**values)
                        )
                    expected.validate(run)
                    await self.commit_transaction(connection)
            except HarnessError:
                raise
            except Exception:
                try:
                    persisted = await self.get(run)
                except Exception:
                    raise HarnessError(
                        "REVIEW_RUN_STATE_WRITE_UNCERTAIN",
                        503,
                        "执行空间资源记录写入无法确认",
                    ) from None
                if expected is None or persisted != expected:
                    raise HarnessError(
                        "REVIEW_HARNESS_INTERNAL_ERROR", 500, "执行空间资源记录未保存"
                    ) from None
        return expected
