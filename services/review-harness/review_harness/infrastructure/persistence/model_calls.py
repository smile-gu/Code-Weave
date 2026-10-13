"""Monotonic local call facts with exact independent commit confirmation."""

from sqlalchemy import case, func, insert, select, text, update

from review_harness.domain.graph import graph_error
from review_harness.domain.model import sha256, utc_now
from review_harness.domain.model_call import FeeModelCall, ModelCall, parse_call
from review_harness.domain.provider import strict_json
from review_harness.infrastructure.persistence.store import commit
from review_harness.infrastructure.persistence.tables import model_calls, runs


class ModelCallStore:
    def __init__(self, database, commit_transaction=commit, *, price_catalog=None):
        self.database, self.commit_transaction = database, commit_transaction
        self.price_catalog = price_catalog

    def _fees(self):
        from review_harness.infrastructure.persistence.fees import FeeLedger

        return FeeLedger(
            self.database, self.commit_transaction, price_catalog=self.price_catalog
        )

    async def has_meter_drift(self):
        # Historical diagnostic query only; never a readiness gate.
        async with self.database.engine.connect() as connection:
            row = (
                await connection.execute(
                    text(
                        "SELECT model_call_id FROM review_model_calls WHERE json_extract(CAST(record_bytes AS TEXT),'$.failureCode')='PROVIDER_TOKEN_METER_DRIFT' OR json_extract(CAST(record_bytes AS TEXT),'$.usage.prompt_tokens') > json_extract(CAST(record_bytes AS TEXT),'$.estimatedInputTokens') LIMIT 1"
                    )
                )
            ).first()
        return row is not None

    def _projection(self):
        # Even a damaged database cannot allocate an unbounded record before
        # the JSON byte guard runs. Metadata remains available for diagnostics.
        return select(
            *[
                case((func.length(column) <= 16384, column), else_=None).label(
                    column.name
                )
                if column.name == "record_bytes"
                else column
                for column in model_calls.c
            ]
        )

    def _read(self, row):
        try:
            raw = bytes(row["record_bytes"])
            value = parse_call(strict_json(raw, limit=16384))
            if (
                value.modelCallId,
                value.runId,
                value.snapshotId,
                value.status,
                sha256(raw),
            ) != (
                row["model_call_id"],
                row["run_id"],
                row["snapshot_id"],
                row["status"],
                row["sha256"],
            ) or value.record_bytes() != raw:
                raise ValueError("Intent integrity mismatch")
            return value
        except (ValueError, TypeError, KeyError):
            raise graph_error("PROVIDER_CALL_INTEGRITY_FAILED") from None

    async def get(self, run_id, call_id):
        async with self.database.engine.connect() as connection:
            row = (
                (
                    await connection.execute(
                        self._projection().where(
                            model_calls.c.run_id == run_id,
                            model_calls.c.model_call_id == call_id,
                        )
                    )
                )
                .mappings()
                .first()
            )
        if row is None:
            raise graph_error("PROVIDER_CALL_NOT_FOUND")
        return self._read(row)

    async def list_for_run(self, run_id):
        async with self.database.engine.connect() as connection:
            version = (
                await connection.execute(
                    select(runs.c.protocol_version).where(runs.c.run_id == run_id)
                )
            ).scalar_one_or_none()
        if version not in {"1.0", "1.1"}:
            raise graph_error("PROVIDER_CALL_INTEGRITY_FAILED")
        limit = 2000 if version == "1.1" else 16
        async with self.database.engine.connect() as connection:
            rows = (
                (
                    await connection.execute(
                        self._projection()
                        .where(model_calls.c.run_id == run_id)
                        .order_by(model_calls.c.model_call_id)
                        .limit(limit + 1)
                    )
                )
                .mappings()
                .all()
            )
        if len(rows) > limit:
            raise graph_error("PROVIDER_CALL_LIMIT_EXCEEDED")
        values = [self._read(row) for row in rows]
        if any(
            (version == "1.1") != isinstance(value, FeeModelCall) for value in values
        ):
            raise graph_error("PROVIDER_CALL_INTEGRITY_FAILED")
        return values

    async def _save(self, value, previous=None):
        from review_harness.infrastructure.persistence.fees import FeeLedger

        async with self.database.engine.connect() as connection:
            owner_version = (
                await connection.execute(
                    select(runs.c.protocol_version).where(runs.c.run_id == value.runId)
                )
            ).scalar_one_or_none()
        if (
            owner_version == "1.1"
            or isinstance(value, FeeModelCall)
            or await FeeLedger(self.database).exists(value.runId)
        ):
            raise graph_error("FEE_GUARD_REQUIRED")
        raw = value.record_bytes()
        if len(raw) > 16384:
            raise graph_error("PROVIDER_CALL_LIMIT_EXCEEDED")
        fields = {
            "model_call_id": value.modelCallId,
            "run_id": value.runId,
            "snapshot_id": value.snapshotId,
            "status": value.status,
            "record_bytes": raw,
            "sha256": sha256(raw),
        }
        async with self.database.write_lock:
            mutated = False
            try:
                async with self.database.engine.connect() as connection:
                    await connection.exec_driver_sql("BEGIN IMMEDIATE")
                    if previous is None:
                        run = (
                            (
                                await connection.execute(
                                    select(runs).where(runs.c.run_id == value.runId)
                                )
                            )
                            .mappings()
                            .first()
                        )
                        if run is None or (
                            run["snapshot_id"],
                            run["status"],
                            run["resource_state"],
                        ) != (value.snapshotId, "RUNNING", "ACTIVE"):
                            raise graph_error("CONTEXT_RUN_NOT_ACTIVE")
                        await connection.execute(insert(model_calls).values(**fields))
                        mutated = True
                    else:
                        result = await connection.execute(
                            update(model_calls)
                            .where(
                                model_calls.c.model_call_id == value.modelCallId,
                                model_calls.c.run_id == value.runId,
                                model_calls.c.record_bytes == previous.record_bytes(),
                            )
                            .values(**fields)
                        )
                        if result.rowcount != 1:
                            raise graph_error("PROVIDER_CALL_ALREADY_DISPATCHED")
                        mutated = True
                    await self.commit_transaction(connection)
            except Exception as error:
                if not mutated:
                    if getattr(error, "code", None):
                        raise
                    raise graph_error("PROVIDER_CALL_STATE_WRITE_UNCERTAIN") from None
                # Fresh connection, exact expected bytes. Lost acknowledgements
                # do not create a second intent or authorize an unconfirmed send.
                try:
                    if await self.get(value.runId, value.modelCallId) == value:
                        return value
                except Exception:
                    pass
                raise graph_error("PROVIDER_CALL_STATE_WRITE_UNCERTAIN") from None
            if await self.get(value.runId, value.modelCallId) != value:
                raise graph_error("PROVIDER_CALL_STATE_WRITE_UNCERTAIN")
        return value

    async def prepare(self, value):

        ledger = self._fees()
        if await ledger.exists(value.runId):
            return await ledger.prepare(value)
        async with self.database.engine.connect() as connection:
            version = (
                await connection.execute(
                    select(runs.c.protocol_version).where(runs.c.run_id == value.runId)
                )
            ).scalar_one_or_none()
        if isinstance(value, FeeModelCall) or version == "1.1":
            raise graph_error("FEE_AUTHORIZATION_REQUIRED")
        if value.status != "PREPARED":
            raise graph_error("PROVIDER_CALL_STATE_INVALID")
        return await self._save(value)

    async def dispatch(self, run_id, call_id):
        previous = await self.get(run_id, call_id)
        if isinstance(previous, FeeModelCall):
            return await self._fees().dispatch(run_id, call_id)
        if previous.status != "PREPARED":
            raise graph_error("PROVIDER_CALL_ALREADY_DISPATCHED")
        value = ModelCall.model_validate(
            {
                **previous.record_dict(),
                "status": "DISPATCHING",
                "updatedAt": utc_now(),
                "remoteOutcomeUnknown": True,
            }
        )
        return await self._save(value, previous)

    async def returned(self, run_id, call_id, usage, warning=None):
        previous = await self.get(run_id, call_id)
        if isinstance(previous, FeeModelCall):
            return await self._fees().returned(run_id, call_id, usage, warning)
        if previous.status != "DISPATCHING":
            raise graph_error("PROVIDER_CALL_STATE_INVALID")
        value = ModelCall.model_validate(
            {
                **previous.record_dict(),
                "status": "RETURNED",
                "updatedAt": utc_now(),
                "usage": usage.model_dump(),
                "remoteOutcomeUnknown": False,
                **({"inputEstimateWarning": warning} if warning else {}),
            }
        )
        return await self._save(value, previous)

    async def close(self, run_id, call_id, failure_code=None):
        previous = await self.get(run_id, call_id)
        if isinstance(previous, FeeModelCall):
            return await self._fees().close(run_id, call_id, failure_code)
        if previous.status == "LOCAL_CLOSED":
            return previous
        value = ModelCall.model_validate(
            {
                **previous.record_dict(),
                "status": "LOCAL_CLOSED",
                "updatedAt": utc_now(),
                "failureCode": failure_code,
            }
        )
        return await self._save(value, previous)

    async def recover(self, *, local_resources_released):
        # Startup records local closure only. No adapter, connection or replay.
        cursor = ""
        while True:
            async with self.database.engine.connect() as connection:
                rows = (
                    (
                        await connection.execute(
                            self._projection()
                            .where(
                                model_calls.c.status != "LOCAL_CLOSED",
                                model_calls.c.model_call_id > cursor,
                            )
                            .order_by(model_calls.c.model_call_id)
                            .limit(32)
                        )
                    )
                    .mappings()
                    .all()
                )
            if not rows:
                return
            for row in rows:
                cursor = row["model_call_id"]
                value = self._read(row)
                if local_resources_released(value.runId):
                    await self.close(
                        value.runId, value.modelCallId, "PROVIDER_CALL_INTERRUPTED"
                    )

    async def request_timeout(self, run_id):

        return await self._fees().request_timeout(run_id)
