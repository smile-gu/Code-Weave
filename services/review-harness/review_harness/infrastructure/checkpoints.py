"""LangGraph's real SQLite saver, with exact-state confirmation and JSON-only serde."""

import asyncio
import json
import os
from pathlib import Path
from uuid import uuid4

import aiosqlite
from langgraph.checkpoint.base import empty_checkpoint
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from review_harness.domain.graph import ReviewGraphState, graph_error
from review_harness.domain.model import canonical, sha256, valid_uuid
from review_harness.infrastructure.paths import safe_directory, safe_file


class JsonSerializer:
    def dumps_typed(self, value):
        return "json", canonical(value)

    def loads_typed(self, value):
        kind, raw = value
        if kind != "json" or len(raw) > 512 * 1024:
            raise ValueError("Unsafe checkpoint type or size")
        return json.loads(
            raw, parse_constant=lambda _: (_ for _ in ()).throw(ValueError())
        )


class Checkpoints:
    def __init__(self, path, *, after_commit=None):
        self.path = Path(path)
        self.after_commit = after_commit
        self.connection = self.saver = None
        self.lock = asyncio.Lock()
        self.file_identity = None

    async def open(self):
        try:
            safe_directory(self.path.parent)
            if os.path.lexists(self.path):
                safe_file(self.path.parent, self.path.name)
            self.connection = await aiosqlite.connect(self.path, timeout=5)
            self.saver = AsyncSqliteSaver(self.connection, serde=JsonSerializer())
            await self.saver.setup()
            info = safe_file(self.path.parent, self.path.name).stat()
            self.file_identity = (info.st_dev, info.st_ino)
            # Check selected implementation's setup shape, not an invented Alembic revision.
            for table, columns in {
                "checkpoints": {
                    "thread_id",
                    "checkpoint_ns",
                    "checkpoint_id",
                    "type",
                    "checkpoint",
                    "metadata",
                },
                "writes": {"task_id", "value", "channel"},
            }.items():
                async with self.connection.execute(
                    f"PRAGMA table_info({table})"
                ) as cursor:
                    present = {row[1] for row in await cursor.fetchall()}
                if not columns <= present:
                    raise ValueError("Incompatible SQLite saver schema")
        except Exception:
            await self.close()
            raise graph_error("CHECKPOINT_UNAVAILABLE") from None

    async def close(self):
        if self.connection:
            await self.connection.close()
        self.connection = self.saver = None

    async def ready(self):
        if self.connection is None:
            return False
        try:
            self.assert_file()
            async with self.lock, aiosqlite.connect(self.path, timeout=1) as connection:
                await connection.execute(
                    "SELECT thread_id,checkpoint_ns,checkpoint_id,type,checkpoint,metadata FROM checkpoints LIMIT 0"
                )
                await connection.execute(
                    "SELECT thread_id,checkpoint_ns,checkpoint_id,task_id,channel,type,value FROM writes LIMIT 0"
                )
                await connection.execute("BEGIN IMMEDIATE")
                await connection.rollback()
            return True
        except Exception:
            return False

    def config(self, state, identity=None):
        value = {"thread_id": state.reviewThreadId, "checkpoint_ns": state.snapshotId}
        if identity is not None:
            value["checkpoint_id"] = identity
        return {"configurable": value}

    async def assert_fresh(self, state):
        if self.connection is None:
            raise graph_error("CHECKPOINT_UNAVAILABLE")
        self.assert_file()
        async with self.connection.execute(
            "SELECT checkpoint_id,type,CASE WHEN length(checkpoint)<=524288 THEN checkpoint END,CASE WHEN length(metadata)<=16384 THEN metadata END FROM checkpoints WHERE thread_id=? AND checkpoint_ns=?",
            (state.reviewThreadId, state.snapshotId),
        ) as cursor:
            async for row in cursor:
                try:
                    metadata = json.loads(row[3])
                    owner = metadata.get("runId")
                    if not valid_uuid(owner):
                        raise ValueError("Checkpoint ownership unknown")
                    if owner != state.runId:
                        # No latest-state inheritance: another Run's damaged
                        # payload cannot poison this fresh Run. For valid JSON,
                        # catch an ownership mismatch without object construction.
                        if row[1] == "json" and row[2] is not None:
                            try:
                                raw_owner = json.loads(row[2])["channel_values"][
                                    "state"
                                ]["runId"]
                            except (ValueError, TypeError, KeyError):
                                continue
                            if raw_owner == state.runId:
                                raise graph_error("CHECKPOINT_INTEGRITY_FAILED")
                        continue
                    raw = JsonSerializer().loads_typed((row[1], row[2]))
                    previous = ReviewGraphState.model_validate(
                        raw["channel_values"]["state"]
                    )
                    if previous.runId != state.runId:
                        raise ValueError("Checkpoint ownership mismatch")
                    await self.read(previous, row[0])
                    if owner == state.runId:
                        raise graph_error("CHECKPOINT_REPLAY_FORBIDDEN")
                except (ValueError, TypeError, KeyError):
                    raise graph_error("CHECKPOINT_INTEGRITY_FAILED") from None

    async def save(self, state):
        state = ReviewGraphState.model_validate(state.model_dump())
        if self.saver is None:
            raise graph_error("CHECKPOINT_UNAVAILABLE")
        self.assert_file()
        checkpoint = empty_checkpoint()
        checkpoint["id"] = str(uuid4())
        checkpoint["channel_values"] = {"state": state.model_dump()}
        checkpoint["channel_versions"] = {"state": state.revision + 1}
        metadata = {
            **state.identity(),
            "revision": state.revision,
            "stateDigest": state.digest(),
            "identityDigest": sha256(canonical(state.identity())),
        }
        identity = checkpoint["id"]
        async with self.lock:
            try:
                await self.saver.aput(
                    self.config(state),
                    checkpoint,
                    metadata,
                    checkpoint["channel_versions"],
                )
                if self.after_commit:
                    await self.after_commit()
            except Exception:
                # A fresh connection, exact ID, complete state and metadata confirm commit.
                try:
                    confirmed = await self.read(state, identity)
                except Exception:
                    raise graph_error("CHECKPOINT_STATE_WRITE_UNCERTAIN") from None
                if confirmed != state:
                    raise graph_error("CHECKPOINT_STATE_WRITE_UNCERTAIN") from None
            else:
                if await self.read(state, identity) != state:
                    raise graph_error("CHECKPOINT_STATE_WRITE_UNCERTAIN")
        return identity

    def assert_file(self):
        info = safe_file(self.path.parent, self.path.name).stat()
        if (info.st_dev, info.st_ino) != self.file_identity:
            raise graph_error("CHECKPOINT_INTEGRITY_FAILED")

    async def read(self, expected, identity):
        try:
            self.assert_file()
            async with aiosqlite.connect(self.path, timeout=5) as connection:
                # The selected saver calls setup() while reading. Validate first
                # so it cannot silently recreate a table removed after startup.
                await connection.execute(
                    "SELECT thread_id,checkpoint_ns,checkpoint_id,task_id,channel,type,value FROM writes LIMIT 0"
                )
                async with connection.execute(
                    "SELECT length(checkpoint),length(metadata) FROM checkpoints WHERE thread_id=? AND checkpoint_ns=? AND checkpoint_id=?",
                    (expected.reviewThreadId, expected.snapshotId, identity),
                ) as cursor:
                    sizes = await cursor.fetchone()
                if sizes and (sizes[0] > 512 * 1024 or sizes[1] > 16384):
                    raise graph_error("CHECKPOINT_INTEGRITY_FAILED")
                saver = AsyncSqliteSaver(connection, serde=JsonSerializer())
                value = await saver.aget_tuple(self.config(expected, identity))
                if value is None:
                    raise graph_error("CHECKPOINT_NOT_FOUND")
                state = ReviewGraphState.model_validate(
                    value.checkpoint["channel_values"]["state"]
                )
                metadata = value.metadata
                if state.identity() != expected.identity():
                    raise graph_error("CHECKPOINT_IDENTITY_MISMATCH")
                if (
                    metadata.get("stateDigest") != state.digest()
                    or metadata.get("identityDigest")
                    != sha256(canonical(state.identity()))
                    or metadata.get("revision") != state.revision
                    or metadata.get("runId") != state.runId
                    or any(
                        metadata.get(key) != value
                        for key, value in state.identity().items()
                    )
                ):
                    raise graph_error("CHECKPOINT_INTEGRITY_FAILED")
                if value.pending_writes:
                    raise graph_error("CHECKPOINT_INTEGRITY_FAILED")
                return state
        except Exception as error:
            if getattr(error, "code", None):
                raise
            raise graph_error("CHECKPOINT_INTEGRITY_FAILED") from None
