import asyncio
import json

import pytest
from sqlalchemy import event

from review_harness.domain.model import Failure, HarnessError
from review_harness.infrastructure.persistence.store import RunStore


async def test_concurrent_create_is_one_acceptance_and_conflicts_on_changed_input(
    database, payload
):
    store = RunStore(database)
    barrier = asyncio.Barrier(2)

    async def create():
        await barrier.wait()
        return await store.create_run(payload)

    outcomes = await asyncio.gather(create(), create())
    assert sorted(created for _, created in outcomes) == [False, True]
    assert [
        e["eventType"] for e in await store.read_events(payload["runId"], 0, 10)
    ] == ["REVIEW_RUN_ACCEPTED"]
    payload["input"]["message"] = "另一请求"
    with pytest.raises(HarnessError) as error:
        await store.create_run(payload)
    assert error.value.code == "REVIEW_RUN_CONFLICT"


@pytest.mark.parametrize(
    "operation,expected,resource",
    [
        ("start_run", "RUNNING", "ACTIVE"),
        ("cancel_pending", "CANCELLED", "NOT_ACQUIRED"),
        ("fail_before_start", "FAILED", "NOT_ACQUIRED"),
    ],
)
async def test_pending_transitions_are_atomic_and_idempotent(
    database, payload, operation, expected, resource
):
    store = RunStore(database)
    await store.create_run(payload)
    args = (
        (Failure.make("REVIEW_EXECUTOR_REJECTED"),)
        if operation == "fail_before_start"
        else ()
    )
    first, changed = await getattr(store, operation)(payload["runId"], *args)
    second, repeated = await getattr(store, operation)(payload["runId"], *args)
    assert changed and not repeated
    assert first == second
    assert (first.status, first.resource_state, first.last_event_sequence) == (
        expected,
        resource,
        2,
    )


async def test_success_and_cancel_compete_for_one_transition(database, payload):
    store = RunStore(database)
    await store.create_run(payload)
    await store.start_run(payload["runId"])
    barrier = asyncio.Barrier(2)

    async def command(operation):
        await barrier.wait()
        return await operation(payload["runId"])

    await asyncio.gather(command(store.complete_success), command(store.request_cancel))
    run = await store.get_run(payload["runId"])
    if run.status == "CANCELLING":
        await store.complete_cancel(run.run_id)
    terminal_events = [
        e
        for e in await store.read_events(run.run_id, 0, 10)
        if e["eventType"]
        in {"REVIEW_RUN_SUCCEEDED", "REVIEW_RUN_FAILED", "REVIEW_RUN_CANCELLED"}
    ]
    assert len(terminal_events) == 1


async def test_result_is_immutable_original_bytes_with_matching_identity(
    database, payload
):
    store = RunStore(database)
    await store.create_run(payload)
    await store.start_run(payload["runId"])
    run, changed = await store.complete_success(payload["runId"])
    original, digest = await store.get_result(run.run_id)
    await store.complete_success(run.run_id)
    assert changed and await store.get_result(run.run_id) == (original, digest)
    document = json.loads(original)
    assert document["runId"] == run.run_id
    assert document["snapshotId"] == payload["snapshot"]["snapshotId"]
    assert document["reportDraft"] == {"markdown": "", "byteSize": 0}
    assert document["generatedAt"] == run.finished_at


@pytest.mark.parametrize("table", ["review_runs", "review_run_events"])
async def test_failed_acceptance_has_no_half_run(database, payload, table):
    store = RunStore(database)

    def reject(_, __, statement, *args):
        if statement.startswith("INSERT INTO " + table):
            raise RuntimeError("injected statement failure")

    event.listen(database.engine.sync_engine, "before_cursor_execute", reject)
    with pytest.raises(HarnessError):
        await store.create_run(payload)
    event.remove(database.engine.sync_engine, "before_cursor_execute", reject)
    with pytest.raises(HarnessError) as error:
        await store.get_run(payload["runId"])
    assert error.value.code == "REVIEW_RUN_NOT_FOUND"


@pytest.mark.parametrize(
    "statement_prefix",
    [
        "INSERT INTO review_results",
        "UPDATE review_runs",
        "INSERT INTO review_run_events",
    ],
)
async def test_success_transaction_rolls_back_every_component(
    database, payload, statement_prefix
):
    store = RunStore(database)
    await store.create_run(payload)
    await store.start_run(payload["runId"])

    def reject(_, __, statement, *args):
        if statement.startswith(statement_prefix):
            raise RuntimeError("injected statement failure")

    event.listen(database.engine.sync_engine, "before_cursor_execute", reject)
    with pytest.raises(HarnessError):
        await store.complete_success(payload["runId"])
    event.remove(database.engine.sync_engine, "before_cursor_execute", reject)
    assert (await store.get_run(payload["runId"])).status == "RUNNING"
    assert len(await store.read_events(payload["runId"], 0, 10)) == 2
    with pytest.raises(HarnessError) as error:
        await store.get_result(payload["runId"])
    assert error.value.code == "REVIEW_RESULT_NOT_READY"


@pytest.mark.parametrize(
    "cancel_first,operation,resource",
    [
        (False, "complete_failure_safe", "RELEASED"),
        (True, "complete_failure_safe", "RELEASED"),
        (False, "fail_cleanup_uncertain", "UNCERTAIN"),
        (True, "fail_cleanup_uncertain", "UNCERTAIN"),
        (True, "complete_cancel", "RELEASED"),
    ],
)
async def test_running_and_cancelling_failure_boundaries(
    database, payload, cancel_first, operation, resource
):
    store = RunStore(database)
    await store.create_run(payload)
    await store.start_run(payload["runId"])
    if cancel_first:
        await store.request_cancel(payload["runId"])
    args = (
        ()
        if operation == "complete_cancel"
        else (Failure.make("REVIEW_EXECUTION_FAILED"),)
    )
    run, changed = await getattr(store, operation)(payload["runId"], *args)
    assert changed and run.resource_state == resource
    if resource == "UNCERTAIN":
        released, changed = await store.confirm_resources_released(
            run.run_id, "REVIEW_RESOURCES_CONFIRMED"
        )
        assert (
            changed
            and released.status == "FAILED"
            and released.resource_state == "RELEASED"
        )
        _, repeated = await store.confirm_resources_released(run.run_id)
        assert not repeated


@pytest.mark.parametrize("operation", ["create_run", "start_run", "complete_success"])
async def test_commit_acknowledgement_loss_is_reconciled_without_duplicate_facts(
    database, payload, operation
):
    async def commit_then_raise(connection):
        await connection.commit()
        raise OSError("commit acknowledgement lost")

    store = RunStore(database)
    if operation != "create_run":
        await store.create_run(payload)
    if operation == "complete_success":
        await store.start_run(payload["runId"])
    store.commit_transaction = commit_then_raise
    argument = payload if operation == "create_run" else payload["runId"]
    run, changed = await getattr(store, operation)(argument)
    assert changed
    _, repeated = await getattr(store, operation)(argument)
    assert not repeated
    assert (
        len(await store.read_events(run.run_id, 0, 10))
        == {"create_run": 1, "start_run": 2, "complete_success": 3}[operation]
    )


async def test_notification_failure_cannot_undo_committed_facts(database, payload):
    def broken_notify(_):
        raise OSError("lost wakeup")

    store = RunStore(database, notify=broken_notify)
    run, _ = await store.create_run(payload)
    assert (await store.get_run(run.run_id)).status == "PENDING"
    assert len(await store.read_events(run.run_id, 0, 10)) == 1
