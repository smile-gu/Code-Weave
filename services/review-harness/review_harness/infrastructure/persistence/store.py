import logging
from uuid import uuid4

from sqlalchemy import insert, select, update

from review_harness.domain.history import Event, HistoryValidator
from review_harness.domain.model import (
    TERMINAL,
    HarnessError,
    Run,
    SessionBlocked,
    canonical,
    empty_result,
    integrity_error,
    sha256,
    utc_now,
)
from review_harness.infrastructure.persistence.tables import events, results, runs

logger = logging.getLogger("review_harness")


async def commit(connection):
    await connection.commit()


class RunStore:
    """Business commands own the transaction; callers never receive mutable rows."""

    def __init__(self, database, notify=None, commit_transaction=commit):
        self.database = database
        self.notify = notify
        self.commit_transaction = commit_transaction
        self.quarantined_runs: set[str] = set()
        self.blocked_sessions: set[str] = set()

    def _quarantine(self, run_id, session_id=None):
        self.quarantined_runs.add(run_id)
        if session_id:
            self.blocked_sessions.add(session_id)

    async def get_run(self, run_id: str) -> Run:
        if run_id in self.quarantined_runs:
            raise integrity_error()
        async with self.database.engine.connect() as connection:
            # One read transaction prevents a writer from splitting snapshot/history.
            await connection.exec_driver_sql("BEGIN")
            row = (
                (await connection.execute(select(runs).where(runs.c.run_id == run_id)))
                .mappings()
                .first()
            )
            if row is None:
                raise HarnessError("REVIEW_RUN_NOT_FOUND", 404, "运行不存在")
            try:
                run = Run(**row)
                run.validate()
                await self._validate_history(connection, run)
                await self._validate_result(connection, run)
                return run
            except HarnessError:
                self._quarantine(run_id, row["coding_session_id"])
                raise
            except (ValueError, TypeError, KeyError, UnicodeError, AttributeError):
                self._quarantine(run_id, row["coding_session_id"])
                raise integrity_error() from None

    async def _validate_history(self, connection, run):
        history = HistoryValidator(run)
        while True:
            page = (
                (
                    await connection.execute(
                        select(events)
                        .where(
                            events.c.run_id == run.run_id,
                            events.c.sequence > history.sequence,
                        )
                        .order_by(events.c.sequence)
                        .limit(100)
                    )
                )
                .mappings()
                .all()
            )
            if not page:
                break
            for row in page:
                history.accept(Event(**row))
        history.finish()

    async def _validate_result(self, connection, run):
        row = (
            (
                await connection.execute(
                    select(results).where(results.c.run_id == run.run_id)
                )
            )
            .mappings()
            .first()
        )
        if run.status != "SUCCEEDED":
            if row is not None:
                raise integrity_error()
            return None
        if (
            row is None
            or row["result_id"] != run.result_id
            or row["sha256"] != run.result_sha256
            or sha256(row["document_bytes"]) != run.result_sha256
        ):
            raise integrity_error()
        if (
            row["snapshot_id"] != run.snapshot_id
            or row["manifest_sha256"] != run.manifest_sha256
            or row["result_version"] != "1.0"
            or row["created_at"] != run.finished_at
        ):
            raise integrity_error()
        if row["document_bytes"] != empty_result(run, run.finished_at, run.result_id):
            raise integrity_error()
        return row

    async def read_events(self, run_id: str, after: int, limit: int) -> list[dict]:
        await self.get_run(run_id)
        async with self.database.engine.connect() as connection:
            page = (
                (
                    await connection.execute(
                        select(events)
                        .where(events.c.run_id == run_id, events.c.sequence > after)
                        .order_by(events.c.sequence)
                        .limit(limit)
                    )
                )
                .mappings()
                .all()
            )
        return [Event(**row).wire() for row in page]

    async def get_result(self, run_id: str) -> tuple[bytes, str]:
        run = await self.get_run(run_id)
        if run.status != "SUCCEEDED":
            code = (
                "REVIEW_RESULT_UNAVAILABLE"
                if run.status in TERMINAL
                else "REVIEW_RESULT_NOT_READY"
            )
            raise HarnessError(code, 409, "当前运行没有可读取的成功结果")
        async with self.database.engine.connect() as connection:
            row = await self._validate_result(connection, run)
        return row["document_bytes"], row["sha256"]

    async def scan_runs(self):
        after = ""
        while True:
            async with self.database.engine.connect() as connection:
                page = (
                    await connection.execute(
                        select(runs.c.run_id, runs.c.coding_session_id)
                        .where(runs.c.run_id > after)
                        .order_by(runs.c.run_id)
                        .limit(100)
                    )
                ).all()
            if not page:
                break
            for run_id, session_id in page:
                after = run_id
                try:
                    yield await self.get_run(run_id)
                except HarnessError as error:
                    if error.code != "REVIEW_RUN_INTEGRITY_ERROR":
                        raise
                    self._quarantine(run_id, session_id)

    async def _write(self, run_id, operation):
        async with self.database.write_lock:
            expected, changed = None, False
            try:
                async with self.database.engine.connect() as connection:
                    await connection.exec_driver_sql("BEGIN IMMEDIATE")
                    expected, changed = await operation(connection)
                    if not changed:
                        await connection.rollback()
                        return expected, False
                    await self.commit_transaction(connection)
            except HarnessError:
                raise
            except Exception:
                # The commit boundary may raise after SQLite already committed.
                try:
                    persisted = await self.get_run(run_id)
                except HarnessError as error:
                    if error.code == "REVIEW_RUN_NOT_FOUND":
                        persisted = None
                    else:
                        raise HarnessError(
                            "REVIEW_RUN_STATE_WRITE_UNCERTAIN",
                            503,
                            "无法确认运行状态写入结果",
                        ) from None
                except Exception:
                    raise HarnessError(
                        "REVIEW_RUN_STATE_WRITE_UNCERTAIN",
                        503,
                        "无法确认运行状态写入结果",
                    ) from None
                if expected is None or not changed or persisted != expected:
                    raise HarnessError(
                        "REVIEW_HARNESS_INTERNAL_ERROR", 500, "运行事务未完成"
                    ) from None
        if changed and self.notify:
            try:
                self.notify(run_id)
            except Exception:
                logger.warning(
                    '{"errorCode":"REVIEW_EVENT_NOTIFY_FAILED","runId":"%s"}', run_id
                )
        if changed and expected.failure:
            logger.warning(
                '{"diagnosticId":"%s","runId":"%s","errorCode":"%s"}',
                expected.diagnostic_id,
                run_id,
                expected.failure_code,
            )
        return expected, changed

    async def create_run(self, payload: dict) -> tuple[Run, bool]:
        request_bytes = canonical(payload)
        fingerprint = sha256(request_bytes)
        run_id = payload["runId"]

        async def create(connection):
            row = (
                (await connection.execute(select(runs).where(runs.c.run_id == run_id)))
                .mappings()
                .first()
            )
            if row:
                current = await self.get_run(run_id)
                if (
                    current.request_fingerprint != fingerprint
                    or current.request_payload != request_bytes
                ):
                    raise HarnessError(
                        "REVIEW_RUN_CONFLICT", 409, "相同 runId 已对应另一份创建请求"
                    )
                return current, False
            await self._require_session_available(
                connection, payload["codingSessionId"]
            )
            now = utc_now()
            values = dict(
                run_id=run_id,
                protocol_version=payload["protocolVersion"],
                request_fingerprint=fingerprint,
                request_payload=request_bytes,
                coding_session_id=payload["codingSessionId"],
                review_thread_id=payload["reviewThreadId"],
                snapshot_id=payload["snapshot"]["snapshotId"],
                manifest_sha256=payload["snapshot"]["manifestSha256"],
                status="PENDING",
                resource_state="NOT_ACQUIRED",
                created_at=now,
                updated_at=now,
                last_event_sequence=1,
            )
            await connection.execute(insert(runs).values(**values))
            row = (
                (await connection.execute(select(runs).where(runs.c.run_id == run_id)))
                .mappings()
                .one()
            )
            run = Run(**row)
            run.validate()
            await self._append_event(connection, run, "ACCEPTED")
            return run, True

        return await self._write(run_id, create)

    async def _require_session_available(self, connection, session_id: str) -> None:
        """Check protection inside the same write transaction as admission/start."""
        if session_id in self.blocked_sessions:
            raise SessionBlocked(
                "REVIEW_RUN_INTEGRITY_ERROR", 500, "关联会话包含损坏的运行事实"
            )
        unsafe = (
            await connection.execute(
                select(runs.c.run_id)
                .where(
                    runs.c.coding_session_id == session_id,
                    runs.c.resource_state == "UNCERTAIN",
                )
                .limit(1)
            )
        ).first()
        if unsafe:
            raise SessionBlocked(
                "REVIEW_HARNESS_NOT_READY", 503, "关联会话尚未确认资源释放"
            )

    async def _append_event(self, connection, run, kind):
        payload = {"status": run.status, "resourceState": run.resource_state}
        if kind == "SUCCEEDED":
            payload["result"] = run.descriptor
        elif kind == "FAILED":
            payload["failure"] = run.failure.wire()
        elif kind == "RESOURCES_RELEASED":
            payload["previousResourceState"] = "UNCERTAIN"
            if run.recovery_code:
                payload["recoveryCode"] = run.recovery_code
        await connection.execute(
            insert(events).values(
                run_id=run.run_id,
                sequence=run.last_event_sequence,
                event_type="REVIEW_RUN_" + kind,
                occurred_at=run.updated_at,
                payload=canonical(payload),
            )
        )

    async def _transition(
        self, run_id, sources, status, resource, kind, failure=None, recovery_code=None
    ):
        async def transition(connection):
            current = await self.get_run(run_id)
            if (current.status, current.resource_state) not in sources:
                return current, False
            if kind == "STARTED":
                await self._require_session_available(
                    connection, current.coding_session_id
                )
            now = max(utc_now(), current.updated_at)
            values = {
                "status": status,
                "resource_state": resource,
                "updated_at": now,
                "last_event_sequence": runs.c.last_event_sequence + 1,
            }
            if status == "RUNNING":
                values["started_at"] = now
            if status in TERMINAL and kind != "RESOURCES_RELEASED":
                values["finished_at"] = now
            if failure:
                failure.validate()
                values.update(
                    failure_code=failure.code,
                    failure_message=failure.message,
                    diagnostic_id=failure.diagnostic_id,
                )
            if recovery_code:
                values["recovery_code"] = recovery_code
            if status == "SUCCEEDED":
                result_id = str(uuid4())
                document = empty_result(current, now, result_id)
                digest = sha256(document)
                await connection.execute(
                    insert(results).values(
                        result_id=result_id,
                        run_id=run_id,
                        snapshot_id=current.snapshot_id,
                        manifest_sha256=current.manifest_sha256,
                        result_version="1.0",
                        document_bytes=document,
                        sha256=digest,
                        created_at=now,
                    )
                )
                values.update(
                    result_id=result_id, result_sha256=digest, result_version="1.0"
                )
            row = (
                (
                    await connection.execute(
                        update(runs)
                        .where(
                            runs.c.run_id == run_id,
                            runs.c.status == current.status,
                            runs.c.resource_state == current.resource_state,
                        )
                        .values(**values)
                        .returning(*runs.c)
                    )
                )
                .mappings()
                .first()
            )
            if row is None:
                raise HarnessError(
                    "REVIEW_RUN_STATE_WRITE_UNCERTAIN", 503, "运行转换条件发生变化"
                )
            run = Run(**row)
            run.validate()
            await self._append_event(connection, run, kind)
            return run, True

        return await self._write(run_id, transition)

    async def start_run(self, run_id):
        return await self._transition(
            run_id, {("PENDING", "NOT_ACQUIRED")}, "RUNNING", "ACTIVE", "STARTED"
        )

    async def fail_before_start(self, run_id, failure):
        return await self._transition(
            run_id,
            {("PENDING", "NOT_ACQUIRED")},
            "FAILED",
            "NOT_ACQUIRED",
            "FAILED",
            failure,
        )

    async def cancel_pending(self, run_id):
        return await self._transition(
            run_id,
            {("PENDING", "NOT_ACQUIRED")},
            "CANCELLED",
            "NOT_ACQUIRED",
            "CANCELLED",
        )

    async def request_cancel(self, run_id):
        return await self._transition(
            run_id, {("RUNNING", "ACTIVE")}, "CANCELLING", "RELEASING", "CANCELLING"
        )

    async def complete_success(self, run_id):
        return await self._transition(
            run_id, {("RUNNING", "ACTIVE")}, "SUCCEEDED", "RELEASED", "SUCCEEDED"
        )

    async def complete_failure_safe(self, run_id, failure, recovery_code=None):
        return await self._transition(
            run_id,
            {("RUNNING", "ACTIVE"), ("CANCELLING", "RELEASING")},
            "FAILED",
            "RELEASED",
            "FAILED",
            failure,
            recovery_code,
        )

    async def complete_cancel(self, run_id, recovery_code=None):
        return await self._transition(
            run_id,
            {("CANCELLING", "RELEASING")},
            "CANCELLED",
            "RELEASED",
            "CANCELLED",
            recovery_code=recovery_code,
        )

    async def fail_cleanup_uncertain(self, run_id, failure):
        return await self._transition(
            run_id,
            {("RUNNING", "ACTIVE"), ("CANCELLING", "RELEASING")},
            "FAILED",
            "UNCERTAIN",
            "FAILED",
            failure,
        )

    async def confirm_resources_released(self, run_id, recovery_code=None):
        return await self._transition(
            run_id,
            {("FAILED", "UNCERTAIN")},
            "FAILED",
            "RELEASED",
            "RESOURCES_RELEASED",
            recovery_code=recovery_code,
        )
