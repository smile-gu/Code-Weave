"""One SQLite transaction owns each call intent and its exact fee reservation."""

from datetime import datetime, timedelta

from sqlalchemy import case, func, insert, select, update

from review_harness.domain.fees import CallReservation, FeeBudget, PriceProfile
from review_harness.domain.graph import graph_error
from review_harness.domain.model import HarnessError, Run, canonical, sha256, utc_now
from review_harness.domain.model_call import FeeModelCall
from review_harness.domain.provider import ProviderUsage, strict_json
from review_harness.infrastructure.persistence.store import commit
from review_harness.infrastructure.persistence.tables import (
    model_call_reservations as reservations,
)
from review_harness.infrastructure.persistence.tables import (
    model_calls,
    runs,
)
from review_harness.infrastructure.persistence.tables import (
    run_fee_budget as budgets,
)


class FeeLedger:
    def __init__(
        self, database, commit_transaction=commit, clock=utc_now, *, price_catalog=None
    ):
        self.database = database
        self.commit_transaction = commit_transaction
        self.clock = clock
        self.price_catalog = price_catalog

    def _projection(self, table):
        return select(
            *[
                case((func.length(c) <= 16384, c), else_=None).label(c.name)
                if c.name == "record_bytes"
                else c
                for c in table.c
            ]
        )

    def _decode(self, row, model):
        try:
            raw = bytes(row["record_bytes"])
            value = model.model_validate(strict_json(raw, limit=16384))
            if (
                sha256(raw) != row["sha256"]
                or canonical(value.model_dump()) != raw
                or value.runId != row["run_id"]
            ):
                raise ValueError("Fee record mismatch")
            if (
                isinstance(value, FeeBudget)
                and value.reviewPlan.feeAuthorization.authorizationId
                != row["authorization_id"]
            ):
                raise ValueError("Authorization owner mismatch")
            if isinstance(value, CallReservation) and (
                value.modelCallId,
                value.state,
            ) != (row["model_call_id"], row["state"]):
                raise ValueError("Reservation projection mismatch")
            return value
        except (ValueError, TypeError, KeyError):
            raise graph_error("FEE_LEDGER_INTEGRITY_FAILED") from None

    async def _load(self, connection, table, run_id, call_id=None):
        query = self._projection(table).where(table.c.run_id == run_id)
        if call_id is not None:
            query = query.where(table.c.model_call_id == call_id)
        row = (await connection.execute(query)).mappings().first()
        return (
            None
            if row is None
            else self._decode(row, FeeBudget if table is budgets else CallReservation)
        )

    async def get(self, run_id):
        async with self.database.engine.connect() as connection:
            value = await self._load(connection, budgets, run_id)
        if value is None:
            raise graph_error("FEE_AUTHORIZATION_REQUIRED")
        return value

    async def reservation(self, run_id, call_id):
        async with self.database.engine.connect() as connection:
            value = await self._load(connection, reservations, run_id, call_id)
        if value is None:
            raise graph_error("FEE_RESERVATION_NOT_FOUND")
        return value

    async def exists(self, run_id):
        async with self.database.engine.connect() as connection:
            return (
                await connection.execute(
                    select(budgets.c.run_id).where(budgets.c.run_id == run_id)
                )
            ).first() is not None

    async def _call(self, connection, run_id, call_id):
        row = (
            (
                await connection.execute(
                    self._projection(model_calls).where(
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
        from review_harness.infrastructure.persistence.model_calls import ModelCallStore

        value = ModelCallStore(self.database)._read(row)
        if not isinstance(value, FeeModelCall):
            raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
        return value

    async def _run(self, connection, run_id, active=False):
        row = (
            (await connection.execute(select(runs).where(runs.c.run_id == run_id)))
            .mappings()
            .first()
        )
        if row is None:
            raise graph_error("REVIEW_RUN_NOT_FOUND")
        run = Run(**row)
        run.validate()
        if active:
            if (run.status, run.resource_state) != ("RUNNING", "ACTIVE"):
                raise graph_error("CONTEXT_RUN_NOT_ACTIVE")
            blocked = (
                await connection.execute(
                    select(runs.c.run_id)
                    .where(
                        runs.c.coding_session_id == run.coding_session_id,
                        runs.c.resource_state == "UNCERTAIN",
                    )
                    .limit(1)
                )
            ).first()
            if blocked:
                raise graph_error("REVIEW_SESSION_RESOURCE_UNCERTAIN")
        return run

    def _changed(self, value, **changes):
        return type(value).model_validate(
            {**value.model_dump(), "revision": value.revision + 1, **changes}
        )

    async def _persist(self, connection, table, value, previous=None):
        raw = canonical(value.model_dump())
        fields = {"run_id": value.runId, "record_bytes": raw, "sha256": sha256(raw)}
        key = table.c.run_id
        if isinstance(value, FeeBudget):
            fields["authorization_id"] = (
                value.reviewPlan.feeAuthorization.authorizationId
            )
        else:
            fields.update(model_call_id=value.modelCallId, state=value.state)
            key = table.c.model_call_id
        if previous is None:
            await connection.execute(insert(table).values(**fields))
        else:
            identity = value.runId if table is budgets else value.modelCallId
            result = await connection.execute(
                update(table)
                .where(
                    key == identity,
                    table.c.record_bytes == canonical(previous.model_dump()),
                )
                .values(**fields)
            )
            if result.rowcount != 1:
                raise graph_error("FEE_LEDGER_STATE_CONFLICT")

    async def _persist_call(self, connection, value, previous=None):
        raw = value.record_bytes()
        fields = dict(
            model_call_id=value.modelCallId,
            run_id=value.runId,
            snapshot_id=value.snapshotId,
            status=value.status,
            record_bytes=raw,
            sha256=sha256(raw),
        )
        if previous is None:
            await connection.execute(insert(model_calls).values(**fields))
        else:
            result = await connection.execute(
                update(model_calls)
                .where(
                    model_calls.c.model_call_id == value.modelCallId,
                    model_calls.c.record_bytes == previous.record_bytes(),
                )
                .values(**fields)
            )
            if result.rowcount != 1:
                raise graph_error("PROVIDER_CALL_ALREADY_DISPATCHED")

    async def _write(self, run_id, operation):
        # The expected tuple proves all transaction participants, not just ModelCall.
        expected = None
        async with self.database.write_lock:
            try:
                async with self.database.engine.connect() as connection:
                    await connection.exec_driver_sql("BEGIN IMMEDIATE")
                    expected = await operation(connection)
                    await self.commit_transaction(connection)
            except Exception as error:
                if expected is None:
                    if isinstance(error, HarnessError):
                        raise
                    raise graph_error("FEE_LEDGER_STATE_WRITE_UNCERTAIN") from None
                if not await self._confirmed(run_id, expected):
                    raise graph_error("FEE_LEDGER_STATE_WRITE_UNCERTAIN") from None
            if not await self._confirmed(run_id, expected):
                raise graph_error("FEE_LEDGER_STATE_WRITE_UNCERTAIN")
        return expected

    async def _confirmed(self, run_id, expected):
        try:
            budget, call, reservation = expected
            async with self.database.engine.connect() as connection:
                await connection.exec_driver_sql("BEGIN")
                if await self._load(connection, budgets, run_id) != budget:
                    return False
                if (
                    call is not None
                    and await self._call(connection, run_id, call.modelCallId) != call
                ):
                    return False
                if (
                    reservation is not None
                    and await self._load(
                        connection, reservations, run_id, reservation.modelCallId
                    )
                    != reservation
                ):
                    return False
            return True
        except Exception:
            return False

    async def initialize(self, run_id, profile):
        profile = PriceProfile.model_validate(profile.model_dump())

        async def operation(connection):
            run = await self._run(connection, run_id, active=True)
            if run.protocol_version != "1.1" or run.execution_contract != {
                "executionMode": "REAL",
                "resultVersion": "3.0",
            }:
                raise graph_error("FEE_AUTHORIZATION_REQUIRED")
            from review_harness.api.dto import CreateRunV11

            plan = CreateRunV11.model_validate(
                strict_json(run.request_payload, limit=65536)
            ).reviewPlan
            auth = plan.feeAuthorization
            if (auth.priceProfileId, auth.priceProfileDigest) != (
                profile.priceProfileId,
                profile.digest,
            ):
                raise graph_error("PRICE_PROFILE_MISMATCH")
            if not profile.current_at(self.clock()) or auth.confirmedAt > self.clock():
                raise graph_error("PRICE_PROFILE_EXPIRED")
            authorized_owner = (
                await connection.execute(
                    select(budgets.c.run_id).where(
                        budgets.c.authorization_id == auth.authorizationId
                    )
                )
            ).scalar_one_or_none()
            if authorized_owner is not None and authorized_owner != run_id:
                raise graph_error("FEE_AUTHORIZATION_CONFLICT")
            old = await self._load(connection, budgets, run_id)
            if old is not None:
                if old.reviewPlan != plan or old.priceProfile != profile:
                    raise graph_error("FEE_AUTHORIZATION_CONFLICT")
                return old, None, None
            if (
                await connection.execute(
                    select(model_calls.c.model_call_id)
                    .where(model_calls.c.run_id == run_id)
                    .limit(1)
                )
            ).first():
                raise graph_error("FEE_AUTHORIZATION_TOO_LATE")
            budget = FeeBudget(
                runId=run_id,
                reviewPlan=plan,
                priceProfile=profile,
                runStartedAt=run.started_at,
                activePhase=None,
                phaseStartedAt=None,
                revision=1,
                spentMicros=0,
                reservedMicros=0,
                overrunMicros=0,
                reservedCalls=0,
                modelCalls=0,
                phaseCalls=0,
                dedupCalls=0,
                stopReason=None,
            )
            await self._persist(connection, budgets, budget)
            return budget, None, None

        return (await self._write(run_id, operation))[0]

    def _deadline(self, budget, *, phase=True):
        now = datetime.fromisoformat(self.clock())
        values = budget.reviewPlan.executionBudget
        if now >= datetime.fromisoformat(budget.runStartedAt) + timedelta(
            seconds=values.runTimeoutSeconds
        ):
            raise graph_error("RUN_TIME_BUDGET_EXHAUSTED")
        if phase and budget.phaseStartedAt:
            seconds = (
                values.summaryTimeoutSeconds
                if budget.activePhase == "dedup"
                else values.passTimeoutSeconds
            )
            if now >= datetime.fromisoformat(budget.phaseStartedAt) + timedelta(
                seconds=seconds
            ):
                raise graph_error(
                    "SUMMARY_TIME_BUDGET_EXHAUSTED"
                    if budget.activePhase == "dedup"
                    else "PASS_TIME_BUDGET_EXHAUSTED"
                )

    def _check_price(self, budget):
        if not budget.priceProfile.current_at(self.clock()):
            raise graph_error("PRICE_PROFILE_EXPIRED")
        if (
            self.price_catalog is not None
            and self.price_catalog.current().digest != budget.priceProfile.digest
        ):
            raise graph_error("PRICE_PROFILE_CHANGED")

    def _available(self, budget, pending=False):
        if budget.stopReason:
            raise graph_error(budget.stopReason)
        self._check_price(budget)
        self._deadline(budget)
        if budget.activePhase is None:
            raise graph_error("PASS_NOT_STARTED")
        if not pending and budget.reservedMicros:
            raise graph_error("FEE_CALL_ALREADY_ACTIVE")
        if budget.modelCalls >= 2000:
            raise graph_error("RUN_CALL_BUDGET_EXHAUSTED")
        if budget.activePhase == "dedup":
            if budget.dedupCalls >= 10:
                raise graph_error("DEDUP_CALL_BUDGET_EXHAUSTED")
        elif budget.phaseCalls >= 300:
            raise graph_error("PASS_CALL_BUDGET_EXHAUSTED")

    async def begin_phase(self, run_id, phase):
        async def operation(connection):
            await self._run(connection, run_id, active=True)
            old = await self._load(connection, budgets, run_id)
            if old is None:
                raise graph_error("FEE_AUTHORIZATION_REQUIRED")
            if old.stopReason and old.stopReason not in {
                "PASS_TIME_BUDGET_EXHAUSTED",
                "PASS_CALL_BUDGET_EXHAUSTED",
            }:
                raise graph_error(old.stopReason)
            self._deadline(old, phase=False)
            if old.reservedMicros:
                raise graph_error("FEE_CALL_ALREADY_ACTIVE")
            sequence = [*old.reviewPlan.selectedPasses, "dedup"]
            expected = (
                sequence[0]
                if old.activePhase is None
                else (
                    sequence[sequence.index(old.activePhase) + 1]
                    if old.activePhase != "dedup"
                    else None
                )
            )
            if phase != expected:
                raise graph_error("PASS_ORDER_INVALID")
            new = self._changed(
                old,
                activePhase=phase,
                phaseStartedAt=self.clock(),
                phaseCalls=0,
                stopReason=None,
            )
            await self._persist(connection, budgets, new, old)
            return new, None, None

        return (await self._write(run_id, operation))[0]

    async def prepare(self, value):
        # Bind the phase from trusted platform state, never from model output.
        async def operation(connection):
            run = await self._run(connection, value.runId, active=True)
            old = await self._load(connection, budgets, value.runId)
            if old is None:
                raise graph_error("FEE_AUTHORIZATION_REQUIRED")
            self._available(old)
            if isinstance(value, FeeModelCall) and value.passId != old.activePhase:
                raise graph_error("PASS_NOT_ACTIVE")
            call = FeeModelCall.model_validate(
                {**value.record_dict(), "callVersion": "2.0", "passId": old.activePhase}
            )
            if call.status != "PREPARED" or call.snapshotId != run.snapshot_id:
                raise graph_error("PROVIDER_CALL_STATE_INVALID")
            amount = old.priceProfile.cost_micros(
                call.conservativeInputTokens, call.reservedOutputTokens
            )
            if (
                old.spentMicros + old.reservedMicros + amount
                > old.reviewPlan.feeAuthorization.amount_micros
            ):
                raise graph_error("FEE_BUDGET_EXHAUSTED")
            record = CallReservation(
                runId=call.runId,
                modelCallId=call.modelCallId,
                passId=call.passId,
                state="RESERVED",
                reservedMicros=amount,
                actualMicros=None,
                overrunMicros=0,
                revision=1,
            )
            new = self._changed(
                old,
                reservedMicros=old.reservedMicros + amount,
                reservedCalls=old.reservedCalls + 1,
            )
            await self._persist_call(connection, call)
            await self._persist(connection, reservations, record)
            await self._persist(connection, budgets, new, old)
            return new, call, record

        return (await self._write(value.runId, operation))[1]

    async def _pair(self, connection, run_id, call_id):
        budget = await self._load(connection, budgets, run_id)
        reservation = await self._load(connection, reservations, run_id, call_id)
        if budget is None or reservation is None:
            raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
        call = await self._call(connection, run_id, call_id)
        expected = {
            "PREPARED": {"RESERVED"},
            "DISPATCHING": {"DISPATCHING"},
            "RETURNED": {"SETTLED"},
            "LOCAL_CLOSED": {"REFUNDED", "UNKNOWN", "SETTLED"},
        }
        if (
            reservation.passId != call.passId
            or reservation.state not in expected[call.status]
        ):
            raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
        if reservation.reservedMicros != budget.priceProfile.cost_micros(
            call.conservativeInputTokens, call.reservedOutputTokens
        ):
            raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
        if (
            call.usage is not None
            and reservation.actualMicros
            != budget.priceProfile.cost_micros(
                call.usage.prompt_tokens, call.usage.completion_tokens
            )
        ):
            raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
        if call.remoteOutcomeUnknown != (
            reservation.state in {"DISPATCHING", "UNKNOWN"}
        ):
            raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
        return budget, call, reservation

    def _call_changed(self, old, **changes):
        return FeeModelCall.model_validate(
            {**old.record_dict(), "updatedAt": utc_now(), **changes}
        )

    async def dispatch(self, run_id, call_id):
        async def operation(connection):
            await self._run(connection, run_id, active=True)
            old, previous, reservation = await self._pair(connection, run_id, call_id)
            if previous.status != "PREPARED":
                raise graph_error("PROVIDER_CALL_ALREADY_DISPATCHED")
            self._available(old, pending=True)
            if previous.passId != old.activePhase:
                raise graph_error("PASS_NOT_ACTIVE")
            call = self._call_changed(
                previous, status="DISPATCHING", remoteOutcomeUnknown=True
            )
            record = self._changed(reservation, state="DISPATCHING")
            new = self._changed(
                old,
                reservedCalls=old.reservedCalls - 1,
                modelCalls=old.modelCalls + 1,
                phaseCalls=old.phaseCalls + 1,
                dedupCalls=old.dedupCalls + (1 if old.activePhase == "dedup" else 0),
            )
            await self._persist_call(connection, call, previous)
            await self._persist(connection, reservations, record, reservation)
            await self._persist(connection, budgets, new, old)
            return new, call, record

        return (await self._write(run_id, operation))[1]

    async def returned(self, run_id, call_id, usage, warning=None):
        usage = ProviderUsage.model_validate(usage.model_dump())

        async def operation(connection):
            old, previous, reservation = await self._pair(connection, run_id, call_id)
            if (
                previous.status in {"RETURNED", "LOCAL_CLOSED"}
                and reservation.state == "SETTLED"
            ):
                if previous.usage != usage:
                    raise graph_error("PROVIDER_CALL_STATE_INVALID")
                return old, previous, reservation
            if previous.status != "DISPATCHING":
                raise graph_error("PROVIDER_CALL_STATE_INVALID")
            actual = old.priceProfile.cost_micros(
                usage.prompt_tokens, usage.completion_tokens
            )
            overrun = max(0, actual - reservation.reservedMicros)
            call = self._call_changed(
                previous,
                status="RETURNED",
                usage=usage.model_dump(),
                remoteOutcomeUnknown=False,
                **({"inputEstimateWarning": warning} if warning else {}),
            )
            record = self._changed(
                reservation, state="SETTLED", actualMicros=actual, overrunMicros=overrun
            )
            new = self._changed(
                old,
                spentMicros=old.spentMicros + actual,
                reservedMicros=old.reservedMicros - reservation.reservedMicros,
                overrunMicros=old.overrunMicros + overrun,
                stopReason="FEE_RESERVATION_OVERRUN" if overrun else old.stopReason,
            )
            await self._persist_call(connection, call, previous)
            await self._persist(connection, reservations, record, reservation)
            await self._persist(connection, budgets, new, old)
            return new, call, record

        new, call, record = await self._write(run_id, operation)
        if record.overrunMicros:
            raise graph_error("FEE_RESERVATION_OVERRUN")
        return call

    async def close(self, run_id, call_id, failure_code=None):
        async def operation(connection):
            old, previous, reservation = await self._pair(connection, run_id, call_id)
            if previous.status == "LOCAL_CLOSED":
                return old, previous, reservation
            changes = {}
            state = reservation.state
            if previous.status == "PREPARED":
                state = "REFUNDED"
                changes.update(
                    reservedMicros=old.reservedMicros - reservation.reservedMicros,
                    reservedCalls=old.reservedCalls - 1,
                    stopReason=failure_code
                    if failure_code
                    in {
                        "PASS_TIME_BUDGET_EXHAUSTED",
                        "PASS_CALL_BUDGET_EXHAUSTED",
                        "RUN_TIME_BUDGET_EXHAUSTED",
                        "RUN_CALL_BUDGET_EXHAUSTED",
                        "FEE_BUDGET_EXHAUSTED",
                    }
                    else "PROVIDER_CALL_NOT_DISPATCHED",
                )
            elif previous.status == "DISPATCHING":
                state = "UNKNOWN"
                changes["stopReason"] = "FEE_REMOTE_OUTCOME_UNKNOWN"
            record = self._changed(
                reservation,
                state=state,
                **({"actualMicros": 0} if state == "REFUNDED" else {}),
            )
            call = self._call_changed(
                previous, status="LOCAL_CLOSED", failureCode=failure_code
            )
            new = self._changed(old, **changes)
            await self._persist_call(connection, call, previous)
            await self._persist(connection, reservations, record, reservation)
            await self._persist(connection, budgets, new, old)
            return new, call, record

        return (await self._write(run_id, operation))[1]

    async def request_timeout(self, run_id):
        budget = await self.get(run_id)
        return min(
            budget.reviewPlan.executionBudget.requestTimeoutSeconds,
            await self.phase_timeout(run_id),
        )

    async def phase_timeout(self, run_id):
        budget = await self.get(run_id)
        self._check_price(budget)
        self._deadline(budget)
        now = datetime.fromisoformat(self.clock())
        policy = budget.reviewPlan.executionBudget
        run_left = (
            datetime.fromisoformat(budget.runStartedAt)
            + timedelta(seconds=policy.runTimeoutSeconds)
            - now
        ).total_seconds()
        if budget.phaseStartedAt is None:
            raise graph_error("PASS_NOT_STARTED")
        phase_seconds = (
            policy.summaryTimeoutSeconds
            if budget.activePhase == "dedup"
            else policy.passTimeoutSeconds
        )
        phase_left = (
            datetime.fromisoformat(budget.phaseStartedAt)
            + timedelta(seconds=phase_seconds)
            - now
        ).total_seconds()
        return min(run_left, phase_left)

    async def audit(self):
        # Closed fee calls are also audited: recovery must not ignore an orphan.
        from sqlalchemy import text

        async with self.database.engine.connect() as connection:
            orphan = (
                await connection.execute(
                    text(
                        "SELECT c.model_call_id FROM review_model_calls c LEFT JOIN run_fee_budget b ON c.run_id=b.run_id "
                        "WHERE json_extract(CAST(c.record_bytes AS TEXT),'$.callVersion')='2.0' AND b.run_id IS NULL LIMIT 1"
                    )
                )
            ).first()
            if orphan:
                raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
        cursor = ""
        while True:
            async with self.database.engine.connect() as connection:
                ids = (
                    (
                        await connection.execute(
                            select(budgets.c.run_id)
                            .where(budgets.c.run_id > cursor)
                            .order_by(budgets.c.run_id)
                            .limit(32)
                        )
                    )
                    .scalars()
                    .all()
                )
            if not ids:
                return
            for run_id in ids:
                cursor = run_id
                async with self.database.engine.connect() as connection:
                    await connection.exec_driver_sql("BEGIN")
                    budget = await self._load(connection, budgets, run_id)
                    run = await self._run(connection, run_id)
                    from review_harness.api.dto import CreateRunV11

                    plan = CreateRunV11.model_validate(
                        strict_json(run.request_payload, limit=65536)
                    ).reviewPlan
                    if budget.reviewPlan != plan:
                        raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
                    rows = (
                        (
                            await connection.execute(
                                select(reservations.c.model_call_id)
                                .where(reservations.c.run_id == run_id)
                                .limit(2001)
                            )
                        )
                        .scalars()
                        .all()
                    )
                    all_calls = (
                        (
                            await connection.execute(
                                select(model_calls.c.model_call_id)
                                .where(model_calls.c.run_id == run_id)
                                .limit(2001)
                            )
                        )
                        .scalars()
                        .all()
                    )
                    if len(rows) > 2000 or set(rows) != set(all_calls):
                        raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
                    amounts = dict(
                        spent=0,
                        reserved=0,
                        overrun=0,
                        calls=0,
                        pending=0,
                        phase=0,
                        dedup=0,
                    )
                    for call_id in rows:
                        _, call, record = await self._pair(connection, run_id, call_id)
                        amounts["spent"] += record.actualMicros or 0
                        amounts["overrun"] += record.overrunMicros
                        if record.state in {"RESERVED", "DISPATCHING", "UNKNOWN"}:
                            amounts["reserved"] += record.reservedMicros
                        if record.state == "RESERVED":
                            amounts["pending"] += 1
                        if record.state not in {"RESERVED", "REFUNDED"}:
                            amounts["calls"] += 1
                            amounts["phase"] += call.passId == budget.activePhase
                            amounts["dedup"] += call.passId == "dedup"
                    if (
                        budget.spentMicros,
                        budget.reservedMicros,
                        budget.overrunMicros,
                        budget.modelCalls,
                        budget.reservedCalls,
                        budget.phaseCalls,
                        budget.dedupCalls,
                    ) != tuple(
                        amounts[k]
                        for k in (
                            "spent",
                            "reserved",
                            "overrun",
                            "calls",
                            "pending",
                            "phase",
                            "dedup",
                        )
                    ):
                        raise graph_error("FEE_LEDGER_INTEGRITY_FAILED")
