import asyncio
import concurrent.futures
import os
import stat
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from review_harness.domain.model import canonical, integrity_error
from review_harness.domain.snapshot import RuntimeContext, WorkCancelled, WorkspaceError
from review_harness.infrastructure.paths import (
    link_or_reparse,
    open_source,
    safe_directory,
    safe_file,
    strict_json,
    visible_files,
)
from review_harness.infrastructure.snapshot import Manifest


@dataclass(frozen=True)
class FileJob:
    future: concurrent.futures.Future
    stop: threading.Event


# An App recreation in the same process must not mistake a live old copy thread
# for a completed process restart. No durable row is replaced by this registry.
_live_jobs: dict[tuple[str, str], FileJob] = {}
_jobs_lock = threading.Lock()
# Per execution root, including jobs left by an earlier App instance.
# Admission equals worker capacity: no hidden executor backlog is accepted.
_MAX_FILE_JOBS = 2


def consume_completion(future):
    if not future.cancelled():
        future.exception()


@dataclass(frozen=True)
class FileBudget:
    stop: threading.Event
    deadline: float

    def check(self):
        if self.stop.is_set():
            raise WorkCancelled()
        if time.monotonic() >= self.deadline:
            raise WorkspaceError("SNAPSHOT_LIMIT_EXCEEDED")


class WorkspaceManager:
    """Durable intention → tracked cooperative file job → proven cleanup."""

    def __init__(self, settings, resources, *, copy_hook=None, remove_hook=None):
        self.settings, self.resources = settings, resources
        self.copy_hook, self.remove_hook = copy_hook, remove_hook
        self._pool = concurrent.futures.ThreadPoolExecutor(
            max_workers=_MAX_FILE_JOBS, thread_name_prefix="review-files"
        )
        self._own_jobs: list[FileJob] = []

    def _key(self, run):
        return os.path.normcase(str(self.settings.execution_root)), run.run_id

    def _owner(self, run):
        return {
            "ownerVersion": "1.0",
            "runId": run.run_id,
            "snapshotId": run.snapshot_id,
            "manifestSha256": run.manifest_sha256,
        }

    def _locations(self, run):
        root = safe_directory(self.settings.execution_root)
        safe_directory(root / ".staging", must_exist=False)
        return root / ".staging" / run.run_id, root / run.run_id

    def has_directories(self, run):
        return any(os.path.lexists(path) for path in self._locations(run))

    async def audit_ownership(self):
        root = safe_directory(self.settings.execution_root)

        def identities():
            for directory in (root, root / ".staging"):
                if not os.path.lexists(directory):
                    continue
                safe_directory(directory)
                with os.scandir(directory) as entries:
                    for entry in entries:
                        if directory == root and entry.name == ".staging":
                            continue
                        # Ownership remains identifiable even if a known Run's
                        # directory was replaced. Its Session is isolated later.
                        yield entry.name

        await self.resources.assert_attributable(identities())

    async def _thread(self, run, operation, signal, seconds):
        if signal.is_set():
            raise WorkCancelled()
        stop = threading.Event()
        budget = FileBudget(stop, time.monotonic() + seconds)
        key = self._key(run)
        with _jobs_lock:
            previous = _live_jobs.get(key)
            if previous and not previous.future.done():
                raise WorkspaceError("EXECUTION_WORKSPACE_CLEANUP_FAILED")
            active = sum(
                other_key[0] == key[0] and not other.future.done()
                for other_key, other in _live_jobs.items()
            )
            if active >= _MAX_FILE_JOBS:
                raise WorkspaceError("EXECUTION_WORKSPACE_BUSY")
            future = self._pool.submit(operation, budget)
            job = FileJob(future, stop)
            _live_jobs[key] = job
        self._own_jobs = [item for item in self._own_jobs if not item.future.done()] + [
            job
        ]

        def finished(_):
            with _jobs_lock:
                if _live_jobs.get(key) is job:
                    _live_jobs.pop(key, None)

        future.add_done_callback(finished)
        wrapped = asyncio.wrap_future(future)
        wrapped.add_done_callback(consume_completion)
        cancellation = asyncio.create_task(signal.wait())
        try:
            done, _ = await asyncio.wait(
                {wrapped, cancellation},
                timeout=seconds,
                return_when=asyncio.FIRST_COMPLETED,
            )
            if wrapped in done:
                return wrapped.result()
            stop.set()
            # A successfully cancelled concurrent Future has never executed.
            # Do not confuse cancellation of its asyncio wrapper with this proof.
            if future.cancel():
                if signal.is_set():
                    raise WorkCancelled()
                raise WorkspaceError("SNAPSHOT_LIMIT_EXCEEDED")
            try:
                await asyncio.wait_for(
                    asyncio.shield(wrapped), self.settings.workspace_cleanup_seconds
                )
            except TimeoutError:
                raise WorkspaceError("EXECUTION_WORKSPACE_CLEANUP_FAILED") from None
            except (WorkCancelled, WorkspaceError):
                pass
            if signal.is_set():
                raise WorkCancelled()
            raise WorkspaceError("SNAPSHOT_LIMIT_EXCEEDED")
        except asyncio.CancelledError:
            stop.set()
            future.cancel()
            # The thread remains tracked; cancelling the awaiting task is not cleanup.
            raise
        finally:
            cancellation.cancel()
            await asyncio.gather(cancellation, return_exceptions=True)

    async def prepare(self, run, signal):
        if signal.is_set():
            raise WorkCancelled()
        await self.resources.prepare_intent(run)
        context = await self._thread(
            run,
            lambda budget: self._prepare(run, budget),
            signal,
            self.settings.workspace_prepare_seconds,
        )
        await self.resources.prepared(run)
        return context

    def _read(self, root, relative, limit, budget, destination=None, *, collect=False):
        import hashlib

        digest = hashlib.sha256()
        size = 0
        saved = bytearray() if collect else None
        output = None
        try:
            if destination is not None:
                safe_directory(destination.parent, must_exist=False)
                destination.parent.mkdir(parents=True, exist_ok=True)
                output = destination.open("xb")
            with open_source(root, relative) as stream:
                while True:
                    budget.check()
                    data = stream.read(64 * 1024)
                    if not data:
                        break
                    size += len(data)
                    if size > limit:
                        raise WorkspaceError("SNAPSHOT_LIMIT_EXCEEDED")
                    digest.update(data)
                    if output is not None:
                        output.write(data)
                    elif saved is not None:
                        saved.extend(data)
            if output is not None:
                output.flush()
                os.fsync(output.fileno())
            return (
                size,
                "sha256:" + digest.hexdigest(),
                bytes(saved) if saved is not None else None,
            )
        finally:
            if output is not None:
                output.close()

    def _snapshot(self, run, budget):
        root = safe_directory(self.settings.snapshot_root) / run.snapshot_id
        safe_directory(root)
        size, digest, raw = self._read(
            root,
            "manifest.json",
            self.settings.snapshot_max_manifest_bytes,
            budget,
            collect=True,
        )
        if digest != run.manifest_sha256:
            raise WorkspaceError("SNAPSHOT_MANIFEST_MISMATCH")
        manifest = Manifest.parse(raw, run, self.settings)
        if {p.name for p in root.iterdir()} != {
            "manifest.json",
            "content",
            "artifacts",
        }:
            raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH")
        self._verify_files(root, manifest, budget)
        return root, manifest

    def _verify_files(self, root, manifest, budget, *, execution=False):
        if visible_files(
            root / "content",
            max_files=self.settings.snapshot_max_files,
            check=budget.check,
        ) != [entry.path for entry in manifest.entries]:
            raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH")
        if visible_files(root / "artifacts", max_files=1, check=budget.check) != [
            "changes.diff"
        ]:
            raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH")
        for entry in manifest.entries:
            if execution and os.name != "nt":
                mode = safe_file(root / "content", entry.path).stat().st_mode
                expected_executable = entry.gitMode == "100755"
                if bool(mode & 0o111) != expected_executable:
                    raise WorkspaceError("EXECUTION_WORKSPACE_CONTAMINATED")
            size, digest, _ = self._read(
                root / "content",
                entry.path,
                self.settings.snapshot_max_file_bytes,
                budget,
            )
            if (size, digest) != (entry.byteSize, entry.contentSha256):
                raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH")
        size, digest, _ = self._read(
            root,
            "artifacts/changes.diff",
            self.settings.snapshot_max_diff_bytes,
            budget,
        )
        if (size, digest) != (
            manifest.changes.diff.byteSize,
            manifest.changes.diff.diffSha256,
        ):
            raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH")

    def _prepare(self, run, budget):
        try:
            source, manifest = self._snapshot(run, budget)
            staging, target = self._locations(run)
            budget.check()
            if staging.exists() or target.exists():
                raise WorkspaceError("EXECUTION_WORKSPACE_CREATE_FAILED")
            staging.parent.mkdir(exist_ok=True)
            safe_directory(staging.parent)
            staging.mkdir()
            with (staging / "owner.json").open("xb") as owner:
                owner.write(canonical(self._owner(run)))
                owner.flush()
                os.fsync(owner.fileno())
            (staging / "content").mkdir()
            (staging / "artifacts").mkdir()
            if self.copy_hook:
                self.copy_hook(run, budget)
            for entry in manifest.entries:
                budget.check()
                size, digest, _ = self._read(
                    source / "content",
                    entry.path,
                    self.settings.snapshot_max_file_bytes,
                    budget,
                    staging / "content" / entry.path,
                )
                if (size, digest) != (entry.byteSize, entry.contentSha256):
                    raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH")
                if os.name != "nt":
                    destination = safe_file(staging / "content", entry.path)
                    destination.chmod(0o755 if entry.gitMode == "100755" else 0o644)
            self._read(
                source,
                "artifacts/changes.diff",
                self.settings.snapshot_max_diff_bytes,
                budget,
                staging / "artifacts/changes.diff",
            )
            # Revalidate the source reference and the complete written destination.
            self._snapshot(run, budget)
            self._verify_files(staging, manifest, budget, execution=True)
            budget.check()
            safe_directory(staging)
            if os.path.lexists(target):
                raise WorkspaceError("EXECUTION_WORKSPACE_CREATE_FAILED")
            os.rename(staging, target)
            return RuntimeContext(run, target, manifest.model_dump())
        except (FileNotFoundError, PermissionError):
            raise WorkspaceError("SNAPSHOT_NOT_FOUND") from None
        except OSError:
            raise WorkspaceError("EXECUTION_WORKSPACE_CREATE_FAILED") from None

    async def verify(self, context, signal):
        def verify(budget):
            try:
                root = safe_directory(context.workspace_root)
                self._verify_owner(root, context.run, budget)
                if {p.name for p in root.iterdir()} != {
                    "owner.json",
                    "content",
                    "artifacts",
                }:
                    raise WorkspaceError("EXECUTION_WORKSPACE_CONTAMINATED")
                _, manifest = self._snapshot(context.run, budget)
                self._verify_files(root, manifest, budget, execution=True)
            except (WorkspaceError, OSError):
                raise WorkspaceError("EXECUTION_WORKSPACE_CONTAMINATED") from None

        await self._thread(
            context.run, verify, signal, self.settings.workspace_prepare_seconds
        )

    def _verify_owner(self, root, run, budget):
        _, _, raw = self._read(root, "owner.json", 4096, budget, collect=True)
        if strict_json(raw) != self._owner(run):
            raise WorkspaceError("EXECUTION_WORKSPACE_CLEANUP_FAILED")

    async def cleanup(self, run):
        record = await self.resources.get(run)
        with _jobs_lock:
            live = _live_jobs.get(self._key(run))
        if live and not live.future.done():
            live.stop.set()
            if not live.future.cancel():
                if record:
                    await self.resources.retain(
                        run, "EXECUTION_WORKSPACE_CLEANUP_FAILED"
                    )
                raise WorkspaceError("EXECUTION_WORKSPACE_CLEANUP_FAILED")
        try:
            staging, target = self._locations(run)
            if record is None:
                if any(os.path.lexists(path) for path in (staging, target)):
                    raise integrity_error()
                return
            if record.state == "REMOVED" and not any(
                os.path.lexists(path) for path in (staging, target)
            ):
                return
            await self.resources.begin_cleanup(run)
            if not any(os.path.lexists(path) for path in (staging, target)):
                # No live/queued job and no directory: cleanup requires no slot.
                # This also closes a rejected preparation intent under backpressure.
                await self.resources.removed(run)
                return
            await self._thread(
                run,
                lambda budget: self._cleanup(run, budget),
                asyncio.Event(),
                self.settings.workspace_cleanup_seconds,
            )
            await self.resources.removed(run)
        except (WorkspaceError, OSError):
            if record:
                await self.resources.retain(run, "EXECUTION_WORKSPACE_CLEANUP_FAILED")
            raise WorkspaceError("EXECUTION_WORKSPACE_CLEANUP_FAILED") from None

    def _cleanup(self, run, budget):
        staging, target = self._locations(run)
        existing = [path for path in (staging, target) if os.path.lexists(path)]
        # Validate every candidate owner before deleting anything.
        for path in existing:
            self._verify_owner(safe_directory(path), run, budget)
        for path in existing:
            if self.remove_hook:
                self.remove_hook(path)
            self._remove(path, budget)
        if any(os.path.lexists(path) for path in (staging, target)):
            raise WorkspaceError("EXECUTION_WORKSPACE_CLEANUP_FAILED")

    def _remove(self, path, budget):
        root = safe_directory(path)
        members = []
        for directory, folders, files in os.walk(root, followlinks=False):
            budget.check()
            safe_directory(Path(directory))
            members.append(Path(directory))
            for folder in folders:
                safe_directory(Path(directory) / folder)
            for name in files:
                file = safe_file(
                    root, (Path(directory) / name).relative_to(root).as_posix()
                )
                members.append(file)
            if len(members) > self.settings.snapshot_max_files * 256:
                raise WorkspaceError("EXECUTION_WORKSPACE_CLEANUP_FAILED")
        for member in reversed(members):
            budget.check()
            info = member.lstat()
            if link_or_reparse(info):
                raise WorkspaceError("EXECUTION_WORKSPACE_CLEANUP_FAILED")
            if stat.S_ISDIR(info.st_mode):
                safe_directory(member).rmdir()
            else:
                safe_file(root, member.relative_to(root).as_posix()).unlink()

    async def close(self, grace):
        for job in self._own_jobs:
            job.stop.set()
            job.future.cancel()
        futures = [
            asyncio.wrap_future(job.future)
            for job in self._own_jobs
            if not job.future.done()
        ]
        if futures:
            for future in futures:
                future.add_done_callback(consume_completion)
            await asyncio.wait(futures, timeout=grace)
        self._pool.shutdown(wait=False, cancel_futures=True)
