import asyncio
import copy
from uuid import uuid4

import pytest
from sqlalchemy import event, text

from review_harness.application.service import RunService
from review_harness.infrastructure.persistence.store import RunStore
from review_harness.runtime.executor import ExecutionCancelled
from review_harness.runtime.supervisor import Supervisor


class ControlledExecutor:
    def __init__(self, failure=False, cleanup_failure=False):
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        self.finished = asyncio.Event()
        self.calls = 0
        self.failure, self.cleanup_failure = failure, cleanup_failure

    async def execute(self, run, cancel):
        self.calls += 1
        self.entered.set()
        release = asyncio.create_task(self.release.wait())
        cancelled = asyncio.create_task(cancel.wait())
        try:
            await asyncio.wait(
                {release, cancelled}, return_when=asyncio.FIRST_COMPLETED
            )
            if cancel.is_set():
                raise ExecutionCancelled()
            if self.failure:
                raise RuntimeError("private executor failure")
        finally:
            release.cancel()
            cancelled.cancel()
            await asyncio.gather(release, cancelled, return_exceptions=True)

    async def cleanup(self, run):
        if self.cleanup_failure:
            raise RuntimeError("private cleanup failure")


async def test_queue_is_bounded_and_duplicate_submission_executes_once(
    database, payload
):
    store = RunStore(database)
    executor = ControlledExecutor()
    worker = Supervisor(store, executor, capacity=1)
    service = RunService(store, worker)
    worker.start()
    try:
        first, _ = await service.create(payload)
        await asyncio.wait_for(executor.entered.wait(), 2)
        assert (await service.create(payload))[1] == 200
        next_payload = copy.deepcopy(payload)
        next_payload["runId"] = str(uuid4())
        second, _ = await service.create(next_payload)
        next_payload["runId"] = str(uuid4())
        rejected, _ = await service.create(next_payload)
        assert (
            rejected.status == "FAILED"
            and rejected.failure_code == "REVIEW_EXECUTOR_REJECTED"
        )
        assert rejected.resource_state == "NOT_ACQUIRED"
        await service.cancel(second.run_id)
        executor.release.set()
        await asyncio.wait_for(worker.join(), 2)
        assert executor.calls == 1
        assert (await store.get_run(first.run_id)).status == "SUCCEEDED"
    finally:
        await worker.stop(1)


async def test_running_save_failure_never_calls_executor(database, payload):
    store = RunStore(database)
    executor = ControlledExecutor()
    worker = Supervisor(store, executor, capacity=1)
    await store.create_run(payload)

    def reject_start(_, __, statement, *args):
        if statement.startswith("UPDATE review_runs"):
            raise RuntimeError("write failure")

    event.listen(database.engine.sync_engine, "before_cursor_execute", reject_start)
    worker.start()
    try:
        worker.ensure_submitted(payload["runId"])
        await asyncio.wait_for(worker.join(), 2)
        assert executor.calls == 0 and not worker.accepting
    finally:
        event.remove(database.engine.sync_engine, "before_cursor_execute", reject_start)
        await worker.stop(1)


@pytest.mark.parametrize(
    "failure,cleanup_failure,expected,resource",
    [
        (False, False, "CANCELLED", "RELEASED"),
        (True, False, "FAILED", "RELEASED"),
        (False, True, "FAILED", "UNCERTAIN"),
    ],
)
async def test_executor_converges_only_after_cleanup(
    database, payload, failure, cleanup_failure, expected, resource
):
    store = RunStore(database)
    executor = ControlledExecutor(failure=failure, cleanup_failure=cleanup_failure)
    worker = Supervisor(store, executor, capacity=1)
    service = RunService(store, worker)
    worker.start()
    try:
        run, _ = await service.create(payload)
        await asyncio.wait_for(executor.entered.wait(), 2)
        if not failure:
            cancelled, status = await service.cancel(run.run_id)
            assert status == 202 and cancelled.status == "CANCELLING"
        else:
            executor.release.set()
        await asyncio.wait_for(worker.join(), 2)
        final = await store.get_run(run.run_id)
        assert (final.status, final.resource_state) == (expected, resource)
    finally:
        await worker.stop(1)


async def test_uncertain_create_stops_admission_and_never_schedules(database, payload):
    fail_confirmation = False

    async def commit_then_lose_confirmation(connection):
        nonlocal fail_confirmation
        await connection.commit()
        fail_confirmation = True
        raise OSError("lost commit acknowledgement")

    def reject_confirmation(_, __, statement, *args):
        if fail_confirmation and statement.startswith("SELECT review_runs"):
            raise OSError("database temporarily unavailable")

    from review_harness.domain.model import HarnessError

    store = RunStore(database, commit_transaction=commit_then_lose_confirmation)
    executor = ControlledExecutor()
    worker = Supervisor(store, executor, capacity=1)
    service = RunService(store, worker)
    worker.start()
    event.listen(
        database.engine.sync_engine, "before_cursor_execute", reject_confirmation
    )
    try:
        with pytest.raises(HarnessError) as error:
            await service.create(payload)
        assert error.value.code == "REVIEW_RUN_STATE_WRITE_UNCERTAIN"
        assert not worker.accepting and executor.calls == 0
    finally:
        event.remove(
            database.engine.sync_engine, "before_cursor_execute", reject_confirmation
        )
        await worker.stop(1)


async def test_start_commit_unknown_never_enters_executor(database, payload):
    store = RunStore(database)
    await store.create_run(payload)
    fail_confirmation = False

    async def lose_confirmation(connection):
        nonlocal fail_confirmation
        await connection.commit()
        fail_confirmation = True
        raise OSError("commit acknowledgement lost")

    def reject_confirmation(_, __, statement, *args):
        if fail_confirmation and statement.startswith("SELECT review_runs"):
            raise OSError("confirmation unavailable")

    store.commit_transaction = lose_confirmation
    executor = ControlledExecutor()
    worker = Supervisor(store, executor, capacity=1)
    event.listen(
        database.engine.sync_engine, "before_cursor_execute", reject_confirmation
    )
    worker.start()
    try:
        worker.ensure_submitted(payload["runId"])
        await asyncio.wait_for(worker.join(), 2)
        assert executor.calls == 0 and not worker.accepting
    finally:
        event.remove(
            database.engine.sync_engine, "before_cursor_execute", reject_confirmation
        )
        await worker.stop(1)


async def test_worker_skips_single_corrupt_pending_run_and_serves_next_session(
    database, payload
):
    store = RunStore(database)
    await store.create_run(payload)
    valid = copy.deepcopy(payload)
    valid["runId"] = str(uuid4())
    valid["codingSessionId"] = str(uuid4())
    await store.create_run(valid)
    async with database.engine.begin() as connection:
        await connection.execute(
            text("UPDATE review_runs SET created_at='invalid' WHERE run_id=:id"),
            {"id": payload["runId"]},
        )
    executor = ControlledExecutor()
    executor.release.set()
    worker = Supervisor(store, executor, capacity=2)
    worker.ensure_submitted(payload["runId"])
    worker.ensure_submitted(valid["runId"])
    worker.start()
    try:
        await asyncio.wait_for(worker.join(), 2)
        assert worker.accepting and executor.calls == 1
        assert (await store.get_run(valid["runId"])).status == "SUCCEEDED"
    finally:
        await worker.stop(1)
