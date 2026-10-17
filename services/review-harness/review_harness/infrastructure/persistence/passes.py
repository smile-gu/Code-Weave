"""Pass facts and ordered Run events share one SQLite transaction."""

from sqlalchemy import case, func, insert, select, update

from review_harness.api.dto import CreateRunV11
from review_harness.domain.graph import graph_error
from review_harness.domain.model import canonical, sha256, utc_now
from review_harness.domain.multipass import PASS_ORDER
from review_harness.domain.pass_fact import PassFact
from review_harness.domain.provider import strict_json
from review_harness.infrastructure.persistence.store import commit
from review_harness.infrastructure.persistence.tables import events, review_passes, runs


class PassStore:
    def __init__(self, database, run_store, commit_transaction=commit):
        self.database, self.runs = database, run_store
        self.commit_transaction = commit_transaction

    async def _list(self, connection, run_id):
        query = select(
            review_passes.c.run_id,
            review_passes.c.pass_id,
            review_passes.c.sha256,
            case(
                (
                    func.length(review_passes.c.record_bytes) <= 65536,
                    review_passes.c.record_bytes,
                ),
                else_=None,
            ).label("record_bytes"),
        ).where(review_passes.c.run_id == run_id)
        result = []
        for row in (await connection.execute(query)).mappings():
            try:
                raw = bytes(row["record_bytes"])
                fact = PassFact.model_validate(strict_json(raw, limit=65536))
                if (
                    fact.runId,
                    fact.passId,
                    sha256(raw),
                    canonical(fact.model_dump()),
                ) != (run_id, row["pass_id"], row["sha256"], raw):
                    raise ValueError()
            except (ValueError, TypeError):
                raise graph_error("PASS_FACT_INTEGRITY_FAILED") from None
            result.append(fact)
        result.sort(key=lambda f: f.ordinal)
        if result and [f.passId for f in result] != list(PASS_ORDER):
            raise graph_error("PASS_FACT_INTEGRITY_FAILED")
        return result

    async def list_for_run(self, run_id):
        async with self.database.engine.connect() as connection:
            return await self._list(connection, run_id)

    async def _write(self, run_id, operation):
        expected = None
        async with self.database.write_lock:
            try:
                async with self.database.engine.connect() as connection:
                    await connection.exec_driver_sql("BEGIN IMMEDIATE")
                    run = await self.runs.get_run(run_id)
                    if run.protocol_version != "1.1" or run.status not in {
                        "RUNNING",
                        "CANCELLING",
                    }:
                        raise graph_error("PASS_RUN_NOT_ACTIVE")
                    old = await self._list(connection, run_id)
                    facts = await operation(connection, run, old)
                    if facts == old:
                        return facts
                    for fact in facts:
                        raw = canonical(fact.model_dump())
                        if len(raw) > 65536:
                            raise graph_error("PASS_FACT_LIMIT_EXCEEDED")
                        fields = dict(record_bytes=raw, sha256=sha256(raw))
                        if old:
                            await connection.execute(
                                update(review_passes)
                                .where(
                                    review_passes.c.run_id == run_id,
                                    review_passes.c.pass_id == fact.passId,
                                )
                                .values(**fields)
                            )
                        else:
                            await connection.execute(
                                insert(review_passes).values(
                                    run_id=run_id, pass_id=fact.passId, **fields
                                )
                            )
                    at = utc_now()
                    await connection.execute(
                        update(runs)
                        .where(runs.c.run_id == run_id)
                        .values(
                            last_event_sequence=run.last_event_sequence + 1,
                            updated_at=at,
                        )
                    )
                    payload = canonical(
                        {
                            "status": run.status,
                            "resourceState": run.resource_state,
                            "passes": [f.progress() for f in facts],
                        }
                    )
                    await connection.execute(
                        insert(events).values(
                            run_id=run_id,
                            sequence=run.last_event_sequence + 1,
                            event_type="REVIEW_PASS_UPDATED",
                            occurred_at=at,
                            payload=payload,
                        )
                    )
                    expected = facts, run.last_event_sequence + 1, at, payload
                    await self.commit_transaction(connection)
            except Exception:
                if expected is None:
                    raise
                if not await self._confirmed(run_id, expected):
                    raise graph_error("PASS_FACT_WRITE_UNCERTAIN") from None
            if not await self._confirmed(run_id, expected):
                raise graph_error("PASS_FACT_WRITE_UNCERTAIN")
        if self.runs.notify:
            try:
                self.runs.notify(run_id)
            except Exception:
                pass  # Durable ordered events remain available for reconciliation.
        return expected[0]

    async def _confirmed(self, run_id, expected):
        try:
            facts, sequence, at, payload = expected
            async with self.database.engine.connect() as connection:
                await connection.exec_driver_sql("BEGIN")
                if await self._list(connection, run_id) != facts:
                    return False
                row = (
                    (
                        await connection.execute(
                            select(runs).where(runs.c.run_id == run_id)
                        )
                    )
                    .mappings()
                    .one()
                )
                event = (
                    (
                        await connection.execute(
                            select(events).where(
                                events.c.run_id == run_id, events.c.sequence == sequence
                            )
                        )
                    )
                    .mappings()
                    .one()
                )
                return (
                    row["last_event_sequence"],
                    row["updated_at"],
                    event["event_type"],
                    event["occurred_at"],
                    bytes(event["payload"]),
                ) == (sequence, at, "REVIEW_PASS_UPDATED", at, payload)
        except Exception:
            return False

    async def initialize(self, run_id):
        async def operation(connection, run, old):
            plan = CreateRunV11.model_validate(
                strict_json(run.request_payload, limit=65536)
            ).reviewPlan
            if old:
                if any(f.selected != (f.passId in plan.selectedPasses) for f in old):
                    raise graph_error("PASS_FACT_INTEGRITY_FAILED")
                return old
            return [
                PassFact(
                    runId=run_id,
                    passId=p,
                    ordinal=i,
                    selected=p in plan.selectedPasses,
                    status="READY" if p in plan.selectedPasses else "SKIPPED",
                    revision=1,
                    startedAt=None,
                    finishedAt=None,
                    reason=None if p in plan.selectedPasses else "USER_NOT_SELECTED",
                    stateRevision=None,
                    stateDigest=None,
                    result=None,
                )
                for i, p in enumerate(PASS_ORDER)
            ]

        return await self._write(run_id, operation)

    async def begin(self, run_id, pass_id, revision, digest):
        async def operation(connection, run, old):
            if run.status != "RUNNING" or not old:
                raise graph_error("PASS_RUN_NOT_ACTIVE")
            ready = next((f for f in old if f.status in {"READY", "RUNNING"}), None)
            if ready is None or ready.passId != pass_id or ready.status != "READY":
                raise graph_error("PASS_ORDER_INVALID")
            changed = PassFact.model_validate(
                {
                    **ready.model_dump(),
                    "status": "RUNNING",
                    "startedAt": utc_now(),
                    "revision": ready.revision + 1,
                    "stateRevision": revision,
                    "stateDigest": digest,
                }
            )
            return [changed if f.passId == pass_id else f for f in old]

        return await self._write(run_id, operation)

    async def finish(self, run_id, result, revision, digest):
        async def operation(connection, run, old):
            original = next((f for f in old if f.passId == result.passId), None)
            if original is None or original.status != "RUNNING":
                raise graph_error("PASS_STATE_CONFLICT")
            changed = PassFact.model_validate(
                {
                    **original.model_dump(),
                    "status": result.status,
                    "finishedAt": utc_now(),
                    "reason": result.incompleteReasons[0]
                    if result.status == "INCOMPLETE"
                    else None,
                    "revision": original.revision + 1,
                    "stateRevision": revision,
                    "stateDigest": digest,
                    "result": result.model_dump(),
                    "usage": result.usage.model_dump(),
                }
            )
            return [changed if f.passId == result.passId else f for f in old]

        return await self._write(run_id, operation)

    async def progress(self, state):
        async def operation(connection, run, old):
            usage = await self._usage(connection, state.runId, state.activePass)
            current = next((f for f in old if f.passId == state.activePass), None)
            if (
                current is None
                or current.status != "RUNNING"
                or current.usage.modelCalls > usage.modelCalls
            ):
                raise graph_error("PASS_STATE_CONFLICT")
            changed = PassFact.model_validate(
                {
                    **current.model_dump(),
                    "revision": current.revision + 1,
                    "usage": usage.model_dump(),
                    "stateRevision": state.revision,
                    "stateDigest": state.digest(),
                }
            )
            return [changed if f.passId == current.passId else f for f in old]

        return await self._write(state.runId, operation)

    async def _usage(self, connection, run_id, pass_id):
        from review_harness.domain.model_call import FeeModelCall
        from review_harness.domain.pass_fact import ObservedPassUsage
        from review_harness.infrastructure.persistence.model_calls import ModelCallStore
        from review_harness.infrastructure.persistence.tables import model_calls

        store = ModelCallStore(self.database)
        rows = (
            (
                await connection.execute(
                    store._projection()
                    .where(model_calls.c.run_id == run_id)
                    .limit(2001)
                )
            )
            .mappings()
            .all()
        )
        if len(rows) > 2000:
            raise graph_error("PASS_FACT_INTEGRITY_FAILED")
        calls = []
        for row in rows:
            call = store._read(row)
            if not isinstance(call, FeeModelCall):
                raise graph_error("PASS_FACT_INTEGRITY_FAILED")
            if call.passId == pass_id and (
                call.usage is not None or call.remoteOutcomeUnknown
            ):
                calls.append(call)
        return ObservedPassUsage(
            modelCalls=len(calls),
            unknownCalls=sum(c.remoteOutcomeUnknown for c in calls),
            inputTokens=sum(c.usage.prompt_tokens for c in calls if c.usage),
            outputTokens=sum(c.usage.completion_tokens for c in calls if c.usage),
        )

    async def interrupt(self, run_id, reason="REVIEW_RUN_INTERRUPTED"):
        async def operation(connection, run, old):
            at = utc_now()
            usage = {
                f.passId: await self._usage(connection, run_id, f.passId)
                for f in old
                if f.status == "RUNNING"
            }
            return [
                PassFact.model_validate(
                    {
                        **f.model_dump(),
                        "revision": f.revision + 1,
                        "status": "INCOMPLETE" if f.status == "RUNNING" else "SKIPPED",
                        "finishedAt": at,
                        "reason": reason,
                        "usage": usage[f.passId].model_dump()
                        if f.status == "RUNNING"
                        else f.usage.model_dump(),
                    }
                )
                if f.status in {"READY", "RUNNING"}
                else f
                for f in old
            ]

        return await self._write(run_id, operation)

    async def skip_remaining(self, run_id):
        return await self.interrupt(run_id, "RUN_BUDGET_EXHAUSTED")
