import asyncio
import json
import os
import shutil
import subprocess
from uuid import uuid4

import pytest

from review_harness.config import Settings
from review_harness.infrastructure.persistence.store import RunStore
from review_harness.infrastructure.persistence.workspaces import WorkspaceStore
from review_harness.infrastructure.workspaces import WorkspaceManager
from tests.snapshot_fixture import publish_snapshot


@pytest.mark.parametrize("directory_to_file", [True, False])
async def test_snapshot_file_directory_transitions_keep_deleted_paths_as_history(
    database, tmp_path, payload, directory_to_file
):
    from tests.snapshot_fixture import digest

    settings = workspace_settings(database, tmp_path)
    added, deleted = ("a", "a/b.txt") if directory_to_file else ("a/b.txt", "a")
    payload["snapshot"]["snapshotId"] = str(uuid4())
    source = publish_snapshot(
        settings.snapshot_root, payload, {added: b"final content\n"}
    )
    manifest_path = source / "manifest.json"
    manifest = json.loads(manifest_path.read_bytes())
    manifest["targetPaths"] = ["a", "a/b.txt"]
    manifest["changes"]["files"] = sorted(
        [
            {
                "path": added,
                "oldPath": None,
                "changeType": "ADDED",
                "binary": False,
                "gitMode": "100644",
            },
            {
                "path": deleted,
                "oldPath": None,
                "changeType": "DELETED",
                "binary": False,
                "gitMode": "000000",
            },
        ],
        key=lambda change: change["path"],
    )
    raw = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    manifest_path.write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = digest(raw)
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    manager = WorkspaceManager(settings, WorkspaceStore(database))
    try:
        context = await manager.prepare(run, asyncio.Event())
        assert (
            context.workspace_root / "content" / added
        ).read_bytes() == b"final content\n"
        await manager.verify(context, asyncio.Event())
        await manager.cleanup(run)
        assert (await manager.resources.get(run)).state == "REMOVED"
        assert (source / "content" / added).read_bytes() == b"final content\n"
    finally:
        await manager.close(1)


async def test_shared_contract_fixture_is_valid_and_copied_without_shared_inode(
    database, tmp_path, payload
):
    from pathlib import Path

    settings = workspace_settings(database, tmp_path)
    fixture_root = (
        Path(__file__).resolve().parents[4] / "contracts/fixtures/review-snapshot-v1"
    )
    snapshot_id = "22222222-2222-4222-8222-222222222222"
    source = settings.snapshot_root / snapshot_id
    shutil.copytree(fixture_root / snapshot_id, source)
    payload["codingSessionId"] = "11111111-1111-4111-8111-111111111111"
    payload["snapshot"] = {
        "snapshotId": snapshot_id,
        "manifestSha256": (fixture_root / "manifest-sha256.txt").read_text().strip(),
    }
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    manager = WorkspaceManager(settings, WorkspaceStore(database))
    try:
        context = await manager.prepare(run, asyncio.Event())
        original = source / "content/context.bin"
        copied = context.workspace_root / "content/context.bin"
        assert copied.read_bytes() == bytes([0, 255, 10])
        assert copied.stat().st_ino != original.stat().st_ino
        await manager.verify(context, asyncio.Event())
        await manager.cleanup(run)
        assert original.read_bytes() == bytes([0, 255, 10])
    finally:
        await manager.close(1)


@pytest.mark.skipif(os.name != "nt", reason="Windows junction regression")
async def test_replaced_content_junction_is_neither_read_nor_recursively_deleted(
    database, tmp_path, payload
):
    from review_harness.domain.snapshot import WorkspaceError

    settings = workspace_settings(database, tmp_path)
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    resources = WorkspaceStore(database)
    manager = WorkspaceManager(settings, resources)
    foreign = tmp_path / "foreign"
    foreign.mkdir()
    sentinel = foreign / "preserve.txt"
    sentinel.write_text("outside execution root")
    try:
        context = await manager.prepare(run, asyncio.Event())
        content = context.workspace_root / "content"
        assert content.resolve().is_relative_to(settings.execution_root.resolve())
        shutil.rmtree(content)
        created = subprocess.run(
            ["cmd", "/c", "mklink", "/J", str(content), str(foreign)],
            capture_output=True,
            check=False,
        )
        assert created.returncode == 0, "Windows junction creation failed"
        try:
            with pytest.raises(WorkspaceError):
                await manager.verify(context, asyncio.Event())
            with pytest.raises(WorkspaceError):
                await manager.cleanup(run)
            assert (await resources.get(run)).state == "RETAINED"
            assert sentinel.read_text() == "outside execution root"
        finally:
            # Remove only the junction itself, after proving its fixed parent.
            assert content.parent == context.workspace_root
            os.rmdir(content)
    finally:
        await manager.close(1)


def workspace_settings(database, tmp_path):
    for name in ("snapshots", "executions", "artifacts"):
        (tmp_path / name).mkdir(exist_ok=True)
    return Settings(
        internal_bearer_token="test-only-credential",
        database_url=str(database.engine.url),
        snapshot_root=tmp_path / "snapshots",
        execution_root=tmp_path / "executions",
        artifact_root=tmp_path / "artifacts",
    )


@pytest.mark.parametrize(
    "setting",
    [
        "snapshot_max_files",
        "snapshot_max_file_bytes",
        "snapshot_max_total_bytes",
        "snapshot_max_manifest_bytes",
        "snapshot_max_diff_bytes",
        "workspace_prepare_seconds",
    ],
)
async def test_each_runtime_budget_rejects_without_publishing_execution_copy(
    database, tmp_path, payload, setting
):
    from review_harness.domain.snapshot import WorkspaceError

    settings = workspace_settings(database, tmp_path).model_copy(
        update={setting: 0.000001 if setting == "workspace_prepare_seconds" else 1}
    )
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    manager = WorkspaceManager(settings, WorkspaceStore(database))
    try:
        with pytest.raises(WorkspaceError):
            await manager.prepare(run, asyncio.Event())
        await manager.cleanup(run)
        assert not manager.has_directories(run)
        assert (settings.snapshot_root / run.snapshot_id / "manifest.json").exists()
    finally:
        await manager.close(1)


async def test_preparation_requires_durable_running_and_intent_before_directories(
    database, tmp_path, payload
):
    settings = workspace_settings(database, tmp_path)
    source = publish_snapshot(settings.snapshot_root, payload)
    store = RunStore(database)
    run, _ = await store.create_run(payload)
    resources = WorkspaceStore(database)
    manager = WorkspaceManager(settings, resources)
    try:
        with pytest.raises(Exception):
            await manager.prepare(run, asyncio.Event())
        assert list(settings.execution_root.iterdir()) == []
        run, _ = await store.start_run(run.run_id)
        context = await manager.prepare(run, asyncio.Event())
        assert (await resources.get(run)).state == "READY"
        assert context.workspace_root != source
        assert (context.workspace_root / "content/src/Demo.java").read_bytes() == (
            source / "content/src/Demo.java"
        ).read_bytes()
        await manager.verify(context, asyncio.Event())
        await manager.cleanup(run)
        assert (await resources.get(run)).state == "REMOVED"
        assert not context.workspace_root.exists()
        assert source.exists()
    finally:
        await manager.close(1)


@pytest.mark.parametrize(
    "damage",
    [
        "content",
        "extra",
        "missing",
        "unknown",
        "identity",
        "duplicate",
        "float",
        "path",
        "prefix_collision",
        "case_collision",
        "unicode_collision",
    ],
)
async def test_snapshot_damage_is_rejected_before_simulator(
    database, tmp_path, payload, damage
):
    from review_harness.domain.snapshot import WorkspaceError

    settings = workspace_settings(database, tmp_path)
    source = publish_snapshot(settings.snapshot_root, payload)
    manifest_path = source / "manifest.json"
    value = json.loads(manifest_path.read_bytes())
    if damage == "content":
        (source / "content/context.txt").write_bytes(b"tampered")
    elif damage == "extra":
        (source / "content/extra.txt").write_bytes(b"extra")
    elif damage == "missing":
        (source / "content/context.txt").unlink()
    else:
        if damage == "identity":
            value["codingSessionId"] = str(uuid4())
        if damage == "unknown":
            value["extra"] = True
        if damage == "float":
            value["entries"][0]["byteSize"] = 1.5
        if damage == "path":
            value["entries"][0]["path"] = "../escape"
        if damage in {"prefix_collision", "case_collision", "unicode_collision"}:
            first = value["entries"][0]
            other = dict(first)
            if damage == "prefix_collision":
                first["path"], other["path"] = "a", "a/b.txt"
            elif damage == "case_collision":
                first["path"], other["path"] = "a", "A"
            else:
                first["path"], other["path"] = "é", "e\u0301"
            value["entries"].append(other)
            value["entries"].sort(key=lambda entry: entry["path"])
        raw = json.dumps(value).encode()
        if damage == "duplicate":
            raw = raw.replace(
                b'"branch": "main"', b'"branch": "main", "branch": "other"'
            )
        manifest_path.write_bytes(raw)
        from tests.snapshot_fixture import digest

        payload["snapshot"]["manifestSha256"] = digest(raw)
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    manager = WorkspaceManager(settings, WorkspaceStore(database))
    try:
        with pytest.raises(WorkspaceError):
            await manager.prepare(run, asyncio.Event())
        await manager.cleanup(run)
        assert source.exists()
    finally:
        await manager.close(1)


async def test_failed_preparation_intent_never_creates_a_directory(
    database, tmp_path, payload
):
    settings = workspace_settings(database, tmp_path)

    async def reject_commit(connection):
        raise OSError("injected before commit")

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    resources = WorkspaceStore(database, commit_transaction=reject_commit)
    manager = WorkspaceManager(settings, resources)
    try:
        with pytest.raises(Exception):
            await manager.prepare(run, asyncio.Event())
        assert not manager.has_directories(run)
        assert await resources.get(run) is None
    finally:
        await manager.close(1)


async def test_cancellation_after_intent_commit_never_starts_file_work(
    database, tmp_path, payload
):
    from review_harness.domain.snapshot import WorkCancelled

    settings = workspace_settings(database, tmp_path)
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    signal = asyncio.Event()
    touched = []

    async def cancel_after_commit(connection):
        await connection.commit()
        signal.set()

    resources = WorkspaceStore(database, commit_transaction=cancel_after_commit)
    manager = WorkspaceManager(
        settings, resources, copy_hook=lambda run, budget: touched.append(run.run_id)
    )
    try:
        with pytest.raises(WorkCancelled):
            await manager.prepare(run, signal)
        assert touched == []
        assert not manager.has_directories(run)
        assert (await resources.get(run)).state == "PREPARING"
        await manager.cleanup(run)
        assert (await resources.get(run)).state == "REMOVED"
    finally:
        await manager.close(1)


async def test_queued_busy_preparation_is_cancelled_before_worker_start(
    database, tmp_path, payload
):
    import copy
    import threading

    from review_harness.domain.snapshot import WorkCancelled

    settings = workspace_settings(database, tmp_path)
    entered, release = threading.Event(), threading.Event()

    def block(run, budget):
        entered.set()
        release.wait()
        budget.check()

    store = RunStore(database)
    first = copy.deepcopy(payload)
    first["runId"], first["codingSessionId"] = str(uuid4()), str(uuid4())
    first["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(settings.snapshot_root, first)
    second = copy.deepcopy(payload)
    second["runId"], second["codingSessionId"] = str(uuid4()), str(uuid4())
    second["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(settings.snapshot_root, second)
    await store.create_run(first)
    await store.create_run(second)
    first_run, _ = await store.start_run(first["runId"])
    second_run, _ = await store.start_run(second["runId"])
    second_signal = asyncio.Event()

    async def commit_and_cancel_second(connection):
        await connection.commit()
        # First has already reached its copy hook; this cancels the second
        # precisely after its durable intent, before any file job admission.
        if entered.is_set():
            second_signal.set()

    manager = WorkspaceManager(
        settings,
        WorkspaceStore(database, commit_transaction=commit_and_cancel_second),
        copy_hook=block,
    )
    try:
        first_signal = asyncio.Event()
        first_task = asyncio.create_task(manager.prepare(first_run, first_signal))
        assert await asyncio.wait_for(asyncio.to_thread(entered.wait), 2)
        second_task = asyncio.create_task(manager.prepare(second_run, second_signal))
        with pytest.raises(WorkCancelled):
            await second_task
        assert not manager.has_directories(second_run)
        first_signal.set()
        release.set()
        with pytest.raises(WorkCancelled):
            await first_task
    finally:
        first_signal.set()
        release.set()
        await manager.close(1)


async def test_commit_acknowledgement_loss_is_confirmed_before_file_preparation(
    database, tmp_path, payload
):
    settings = workspace_settings(database, tmp_path)

    async def committed_but_raise(connection):
        await connection.commit()
        raise OSError("lost acknowledgement")

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    resources = WorkspaceStore(database, commit_transaction=committed_but_raise)
    manager = WorkspaceManager(settings, resources)
    try:
        context = await manager.prepare(run, asyncio.Event())
        assert (await resources.get(run)).state == "READY"
        await manager.cleanup(run)
        assert not context.workspace_root.exists()
        assert (await resources.get(run)).state == "REMOVED"
    finally:
        await manager.close(1)


async def test_cancelled_copy_cannot_clean_while_thread_is_still_live(
    database, tmp_path, payload
):
    import threading

    from review_harness.domain.snapshot import WorkspaceError

    settings = workspace_settings(database, tmp_path).model_copy(
        update={"workspace_cleanup_seconds": 0.5}
    )
    entered, release = threading.Event(), threading.Event()

    def block(run, budget):
        entered.set()
        release.wait()
        budget.check()

    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    resources = WorkspaceStore(database)
    manager = WorkspaceManager(settings, resources, copy_hook=block)
    signal = asyncio.Event()
    task = asyncio.create_task(manager.prepare(run, signal))
    try:
        assert await asyncio.wait_for(asyncio.to_thread(entered.wait), 2)
        signal.set()
        with pytest.raises(WorkspaceError):
            await task
        with pytest.raises(WorkspaceError):
            await manager.cleanup(run)
        assert (await resources.get(run)).state == "RETAINED"
        assert manager.has_directories(run)
        release.set()
        await manager.close(1)

        # A fresh manager in the same process still checks the tracked completion proof.
        recovered = WorkspaceManager(settings, resources)
        try:
            await recovered.cleanup(run)
            assert (await resources.get(run)).state == "REMOVED"
            assert not recovered.has_directories(run)
        finally:
            await recovered.close(1)
    finally:
        release.set()
        await manager.close(1)


async def test_owner_mismatch_retains_resources_and_does_not_delete_foreign_data(
    database, tmp_path, payload
):
    from review_harness.domain.snapshot import WorkspaceError

    settings = workspace_settings(database, tmp_path)
    store = RunStore(database)
    await store.create_run(payload)
    run, _ = await store.start_run(payload["runId"])
    resources = WorkspaceStore(database)
    manager = WorkspaceManager(settings, resources)
    try:
        context = await manager.prepare(run, asyncio.Event())
        (context.workspace_root / "owner.json").write_bytes(b"{}")
        with pytest.raises(WorkspaceError):
            await manager.cleanup(run)
        assert (await resources.get(run)).state == "RETAINED"
        assert (context.workspace_root / "content/src/Demo.java").exists()
    finally:
        await manager.close(1)


async def test_simulator_contamination_prevents_success_result(
    database, tmp_path, payload
):
    from review_harness.runtime.supervisor import Supervisor

    settings = workspace_settings(database, tmp_path)
    store = RunStore(database)

    class ContaminatingExecutor:
        async def execute(self, context, cancel):
            (context.workspace_root / "content/src/Demo.java").write_bytes(
                b"contaminated"
            )

        async def cleanup(self, context):
            pass

    resources = WorkspaceStore(database)
    manager = WorkspaceManager(settings, resources)
    worker = Supervisor(store, ContaminatingExecutor(), 1, manager)
    await store.create_run(payload)
    worker.start()
    try:
        worker.ensure_submitted(payload["runId"])
        await asyncio.wait_for(worker.join(), 2)
        run = await store.get_run(payload["runId"])
        assert run.status == "FAILED" and run.resource_state == "RELEASED"
        assert (
            run.failure_code == "EXECUTION_WORKSPACE_CONTAMINATED"
            and run.result_id is None
        )
        assert not manager.has_directories(run)
        assert (
            settings.snapshot_root / run.snapshot_id / "content/src/Demo.java"
        ).read_bytes() == b"class Demo {}\n"
    finally:
        await worker.stop(1)
        await manager.close(1)


async def test_supervisor_retains_uncertain_copy_resources_and_serves_other_session(
    database, tmp_path, payload
):
    import copy
    import threading

    from review_harness.runtime.executor import DeterministicExecutor
    from review_harness.runtime.supervisor import Supervisor

    settings = workspace_settings(database, tmp_path).model_copy(
        update={"workspace_cleanup_seconds": 0.5, "workspace_prepare_seconds": 0.5}
    )
    entered, release = threading.Event(), threading.Event()

    def block(run, budget):
        if run.run_id == payload["runId"]:
            entered.set()
            release.wait()
            budget.check()

    store = RunStore(database)
    await store.create_run(payload)
    manager = WorkspaceManager(settings, WorkspaceStore(database), copy_hook=block)
    worker = Supervisor(store, DeterministicExecutor(), 2, manager)
    worker.start()
    try:
        worker.ensure_submitted(payload["runId"])
        assert await asyncio.wait_for(asyncio.to_thread(entered.wait), 2)
        await store.request_cancel(payload["runId"])
        worker.signal_cancel(payload["runId"])
        await asyncio.wait_for(worker.join(), 2)
        failed = await store.get_run(payload["runId"])
        assert (failed.status, failed.resource_state) == ("FAILED", "UNCERTAIN")
        assert (await manager.resources.get(failed)).state == "RETAINED"
        assert manager.has_directories(failed)
        # A remains blocked in the actual copy thread. Use the same manager.
        assert not release.is_set()
        other = copy.deepcopy(payload)
        other["runId"], other["codingSessionId"] = str(uuid4()), str(uuid4())
        other["snapshot"]["snapshotId"] = str(uuid4())
        publish_snapshot(settings.snapshot_root, other)
        await store.create_run(other)
        worker.ensure_submitted(other["runId"])
        await asyncio.wait_for(worker.join(), 2)
        assert (await store.get_run(other["runId"])).status == "SUCCEEDED"
        assert (await store.get_run(failed.run_id)).resource_state == "UNCERTAIN"
    finally:
        release.set()
        await worker.stop(1)
        await manager.close(1)


async def test_exhausted_file_slots_reject_without_late_work_or_false_uncertainty(
    database, tmp_path, payload
):
    import copy
    import threading

    from review_harness.runtime.executor import DeterministicExecutor
    from review_harness.runtime.supervisor import Supervisor

    settings = workspace_settings(database, tmp_path).model_copy(
        update={"workspace_cleanup_seconds": 0.05}
    )
    store = RunStore(database)
    blocked_ids = set()
    entered = {}
    release = threading.Event()
    copied_ids = set()

    def block(run, budget):
        copied_ids.add(run.run_id)
        if run.run_id in blocked_ids:
            entered[run.run_id].set()
            release.wait()
            budget.check()

    manager = WorkspaceManager(settings, WorkspaceStore(database), copy_hook=block)
    worker = Supervisor(store, DeterministicExecutor(), 3, manager)
    worker.start()
    runs = []
    try:
        # Occupy both real file workers with jobs that remain live after cancellation.
        for _ in range(2):
            request = copy.deepcopy(payload)
            request["runId"], request["codingSessionId"] = str(uuid4()), str(uuid4())
            request["snapshot"]["snapshotId"] = str(uuid4())
            publish_snapshot(settings.snapshot_root, request)
            await store.create_run(request)
            blocked_ids.add(request["runId"])
            entered[request["runId"]] = threading.Event()
            runs.append(request)
            worker.ensure_submitted(request["runId"])
            assert await asyncio.wait_for(
                asyncio.to_thread(entered[request["runId"]].wait), 2
            )
            await store.request_cancel(request["runId"])
            worker.signal_cancel(request["runId"])
            await asyncio.wait_for(worker.join(), 2)
            retained = await store.get_run(request["runId"])
            assert (retained.status, retained.resource_state) == ("FAILED", "UNCERTAIN")

        await store.create_run(payload)
        worker.ensure_submitted(payload["runId"])
        await asyncio.wait_for(worker.join(), 2)
        rejected = await store.get_run(payload["runId"])
        assert (rejected.status, rejected.resource_state) == ("FAILED", "RELEASED")
        assert rejected.failure_code == "EXECUTION_WORKSPACE_BUSY"
        assert not manager.has_directories(rejected)
        assert (await manager.resources.get(rejected)).state == "REMOVED"
        assert payload["runId"] not in copied_ids

        # Capacity and completion evidence also apply after App/manager recreation.
        fresh = WorkspaceManager(settings, WorkspaceStore(database), copy_hook=block)
        worker.workspaces = fresh
        request = copy.deepcopy(payload)
        request["runId"] = str(uuid4())
        await store.create_run(request)
        try:
            worker.ensure_submitted(request["runId"])
            await asyncio.wait_for(worker.join(), 2)
            rejected_again = await store.get_run(request["runId"])
            assert rejected_again.failure_code == "EXECUTION_WORKSPACE_BUSY"
            assert rejected_again.resource_state == "RELEASED"
            assert not fresh.has_directories(rejected_again)
        finally:
            await fresh.close(1)

        release.set()
        await manager.close(1)
        # Rejected work was never queued; freeing slots cannot start it later.
        assert copied_ids == blocked_ids
        recovered = WorkspaceManager(
            workspace_settings(database, tmp_path), WorkspaceStore(database)
        )
        try:
            for request in runs:
                retained = await store.get_run(request["runId"])
                await recovered.cleanup(retained)
                assert (
                    await store.get_run(retained.run_id)
                ).resource_state == "UNCERTAIN"
        finally:
            await recovered.close(1)
    finally:
        release.set()
        await worker.stop(1)
        await manager.close(1)
