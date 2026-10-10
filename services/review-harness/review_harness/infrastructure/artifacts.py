import asyncio
import json
import logging
import os
import threading
import time
from pathlib import Path
from uuid import uuid4
from weakref import WeakValueDictionary

from sqlalchemy import select

from review_harness.application.redaction import redact, redact_structure
from review_harness.domain.artifact import Artifact, ContentPage, artifact_error
from review_harness.domain.model import (
    HarnessError,
    canonical,
    sha256,
    utc_now,
    valid_uuid,
)
from review_harness.domain.snapshot import WorkspaceError
from review_harness.infrastructure.cursors import CursorCodec
from review_harness.infrastructure.paths import open_source, safe_directory, safe_file
from review_harness.infrastructure.persistence.artifacts import ArtifactStore
from review_harness.infrastructure.persistence.tables import artifacts

_publication_locks = WeakValueDictionary()
_locks_guard = threading.Lock()


class ArtifactService:
    """Immutable file publication plus independently confirmed SQLite metadata.

    The publication lock includes in-flight writes. Failed metadata leaves disk
    bytes/count charged to the Run, across service recreation as well.
    """

    def __init__(
        self, settings, database, runs, jobs, *, store=None, publish_hook=None
    ):
        self.settings, self.runs, self.jobs = settings, runs, jobs
        self.store = store or ArtifactStore(database)
        self.publish_hook = publish_hook
        self.cursors = CursorCodec(settings.internal_bearer_token.get_secret_value())

    async def audit(self):
        """Bounded diagnostics only: retain orphans and isolate bad material.

        Never treat one corrupt Artifact as proof that unrelated Runs are unsafe.
        Request-time verification remains authoritative for every future page.
        """
        async with self.store.database.engine.connect() as connection:
            rows = (
                await connection.execute(
                    select(artifacts.c.run_id, artifacts.c.artifact_id)
                    .order_by(artifacts.c.artifact_id)
                    .limit(64)
                )
            ).all()
        deadline = time.monotonic() + 10
        for run_id, identity in rows:
            if time.monotonic() >= deadline:
                break
            try:
                value = await self.store.get(run_id, identity)
                await self._verified(value, seconds=min(2, deadline - time.monotonic()))
            except Exception:
                logging.getLogger("review_harness").warning(
                    '{"artifactId":"%s","errorCode":"ARTIFACT_INTEGRITY_FAILED"}',
                    identity if valid_uuid(identity) else "invalid",
                )

    async def publish(
        self,
        context,
        text,
        kind,
        producer,
        signal,
        *,
        call_id=None,
        coverage="FULL",
        media_type="text/plain",
        seconds=None,
        prior_redaction="NONE",
        producer_type="TOOL",
    ):
        deadline = time.monotonic() + (seconds or self.settings.tool_timeout_seconds)
        run = await self.runs.get_run(context.run.run_id)
        if signal.is_set() or run.status == "CANCELLING":
            from review_harness.domain.snapshot import WorkCancelled

            raise WorkCancelled()
        if run.snapshot_id != context.run.snapshot_id or run.status != "RUNNING":
            raise artifact_error("ARTIFACT_ACCESS_DENIED", 409)
        identity = str(uuid4())
        producer_id = producer + ":" + (call_id or str(uuid4()))
        # Encoding/capture bounds precede redaction; the whole bounded text is
        # processed at once so chunks and long lines cannot evade patterns.
        raw = text.encode("utf-8", "strict")
        partial = len(raw) > self.settings.artifact_max_bytes
        if partial:
            raw = raw[: self.settings.artifact_max_bytes]
            text = raw.decode("utf-8", "ignore")
            if not text:
                raise artifact_error("ARTIFACT_LIMIT_EXCEEDED")
        if media_type == "application/json" and not partial:
            sanitized, redaction = redact_structure(json.loads(text))
            text = canonical(sanitized).decode()
        else:
            text, redaction = redact(text)
            if partial:
                media_type = "text/plain"
        raw = text.encode("utf-8")
        if prior_redaction == "APPLIED":
            redaction = "APPLIED"
        if len(raw) > self.settings.artifact_max_bytes:
            raw = raw[: self.settings.artifact_max_bytes]
            raw = raw.decode("utf-8", "ignore").encode()
            partial = True
        if partial:
            media_type = "text/plain"
        value = Artifact(
            artifact_id=identity,
            run_id=run.run_id,
            snapshot_id=run.snapshot_id,
            kind=kind,
            producer_type=producer_type,
            producer_id=producer_id,
            media_type=media_type,
            encoding="UTF-8",
            byte_size=len(raw),
            sha256=sha256(raw),
            coverage="PARTIAL" if partial else coverage,
            redaction_state=redaction,
            created_at=utc_now(),
            integrity_state="READY",
        )
        try:
            await self.jobs.run_file_job(
                run,
                lambda budget: self._publish(value, raw, budget),
                signal,
                max(0.000001, deadline - time.monotonic()),
            )
        except WorkspaceError as error:
            if error.code == "EXECUTION_WORKSPACE_BUSY":
                # Admission rejected before this publication acquired a job.
                # A still-live owned job remains in the common registry.
                raise artifact_error("ARTIFACT_LIMIT_EXCEEDED") from None
            raise
        await self.store.save(value)
        # Even a commit acknowledgement failure must verify the same bytes.
        await self._verified(value, signal, max(0.000001, deadline - time.monotonic()))
        if signal.is_set():
            from review_harness.domain.snapshot import WorkCancelled

            raise WorkCancelled()
        return value

    def _quota(self, directory, new_size, budget):
        count, total = 0, 0
        with os.scandir(directory) as children:
            for child in children:
                budget.check()
                if not valid_uuid(child.name):
                    raise artifact_error("ARTIFACT_WRITE_FAILED")
                location = safe_directory(Path(child.path))
                count += 1
                # Both staging and published orphan bytes remain charged.
                for name in ("content", "staging"):
                    path = location / name
                    if os.path.lexists(path):
                        total += safe_file(location, name).stat().st_size
                if count >= self.settings.artifact_run_count:
                    raise artifact_error("ARTIFACT_LIMIT_EXCEEDED", 409)
        if total + new_size > self.settings.artifact_run_bytes:
            raise artifact_error("ARTIFACT_LIMIT_EXCEEDED", 409)

    def _publish(self, value, raw, budget):
        # Quotas belong to a Run, not the entire root. A retained writer must
        # not serialize unrelated Sessions behind its mutex. Weak references
        # release idle locks after all owners/waiters drop their strong refs.
        key = os.path.normcase(str(self.settings.artifact_root)), value.run_id
        with _locks_guard:
            publication_lock = _publication_locks.get(key)
            if publication_lock is None:
                publication_lock = threading.Lock()
                _publication_locks[key] = publication_lock
        try:
            # Waiting for the mutex remains cancellable, not hidden pool backlog.
            while not publication_lock.acquire(timeout=0.05):
                budget.check()
            try:
                root = safe_directory(self.settings.artifact_root)
                run_root = root / value.run_id
                safe_directory(run_root, must_exist=False)
                run_root.mkdir(exist_ok=True)
                self._quota(run_root, len(raw), budget)
                directory = run_root / value.artifact_id
                directory.mkdir()  # CREATE_NEW identity; a collision never replaces.
                staging = directory / "staging"
                with staging.open("xb") as stream:
                    for offset in range(0, len(raw), 65536):
                        budget.check()
                        stream.write(raw[offset : offset + 65536])
                    stream.flush()
                    os.fsync(stream.fileno())
                budget.check()
                if self.publish_hook:
                    self.publish_hook(directory)
                budget.check()
                safe_directory(directory)
                safe_file(directory, "staging")
                # Atomic no-replace file publication, same volume. Artifact
                # hardlink is transient and never shared with Snapshot/workspace.
                os.link(staging, directory / "content", follow_symlinks=False)
                staging.unlink()
            finally:
                publication_lock.release()
        except (OSError, WorkspaceError):
            raise artifact_error("ARTIFACT_WRITE_FAILED") from None

    def _read(self, value, budget):
        directory = (
            safe_directory(self.settings.artifact_root)
            / value.run_id
            / value.artifact_id
        )
        with open_source(directory, "content") as stream:
            raw = bytearray()
            while True:
                budget.check()
                chunk = stream.read(65536)
                if not chunk:
                    break
                raw.extend(chunk)
                if len(raw) > self.settings.artifact_max_bytes:
                    raise artifact_error("ARTIFACT_INTEGRITY_FAILED")
            if len(raw) != value.byte_size or sha256(raw) != value.sha256:
                raise artifact_error("ARTIFACT_INTEGRITY_FAILED")
            return bytes(raw)

    async def _verified(self, value, signal=None, seconds=None):
        run = await self.runs.get_run(value.run_id)
        try:
            if value.integrity_state != "READY":
                raise artifact_error("ARTIFACT_INTEGRITY_FAILED")
            return await self.jobs.run_file_job(
                run,
                lambda budget: self._read(value, budget),
                signal or asyncio.Event(),
                seconds or self.settings.tool_timeout_seconds,
            )
        except (OSError, WorkspaceError, HarnessError) as error:
            if isinstance(error, WorkspaceError):
                if error.code in {
                    "EXECUTION_WORKSPACE_BUSY",
                    "SNAPSHOT_LIMIT_EXCEEDED",
                }:
                    raise artifact_error("ARTIFACT_LIMIT_EXCEEDED") from None
                if error.code == "EXECUTION_WORKSPACE_CLEANUP_FAILED":
                    raise
            await self.store.corrupt(value)
            raise artifact_error("ARTIFACT_INTEGRITY_FAILED") from None

    async def metadata(self, run_id, identity):
        if not valid_uuid(identity):
            raise artifact_error("ARTIFACT_NOT_FOUND", 404)
        value = await self.store.get(run_id, identity)
        await self._verified(value)
        return value

    async def content(
        self,
        run_id,
        identity,
        cursor=None,
        *,
        signal=None,
        page_budget=None,
        seconds=None,
    ):
        value = await self.store.get(run_id, identity)
        raw = await self._verified(value, signal, seconds)
        if value.encoding != "UTF-8":
            raise artifact_error("ARTIFACT_CONTENT_UNSUPPORTED", 415)
        try:
            text = raw.decode("utf-8", "strict")
        except UnicodeError:
            await self.store.corrupt(value)
            raise artifact_error("ARTIFACT_CONTENT_UNSUPPORTED", 415) from None
        offset = self.cursors.decode(cursor, run_id, identity, value.sha256)
        if offset > len(raw):
            raise artifact_error("ARTIFACT_CURSOR_INVALID", 422)
        try:
            prefix = raw[:offset].decode("utf-8", "strict")
        except UnicodeError:
            raise artifact_error("ARTIFACT_CURSOR_INVALID", 422) from None
        remaining = text[len(prefix) :]

        def page(length):
            displayed = remaining[:length]
            next_offset = offset + len(displayed.encode())
            more = next_offset < len(raw)
            return ContentPage(
                artifactId=identity,
                sha256=value.sha256,
                text=displayed,
                coverage=value.coverage,
                redactionState=value.redaction_state,
                nextCursor=self.cursors.encode(
                    run_id, identity, value.sha256, next_offset
                )
                if more
                else None,
                hasMore=more,
            )

        limit = page_budget or self.settings.tool_output_bytes
        low, high = 0, min(len(remaining), limit)
        while low < high:
            middle = (low + high + 1) // 2
            if len(canonical(page(middle).model_dump())) <= limit:
                low = middle
            else:
                high = middle - 1
        if remaining and low == 0:
            raise artifact_error("ARTIFACT_LIMIT_EXCEEDED")
        return page(low)
