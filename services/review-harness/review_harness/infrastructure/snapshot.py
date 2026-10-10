from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError

from review_harness.domain.model import valid_time
from review_harness.domain.snapshot import WorkspaceError
from review_harness.infrastructure.paths import distinct, portable, strict_json

Uuid = Annotated[
    str,
    StringConstraints(
        pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    ),
]
Hash = Annotated[str, StringConstraints(pattern=r"^sha256:[0-9a-f]{64}$")]
Oid = Annotated[str, StringConstraints(pattern=r"^([0-9a-f]{40}|[0-9a-f]{64})$")]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Entry(StrictModel):
    path: str
    type: Literal["REGULAR_FILE", "SYMLINK"]
    tracked: bool
    gitMode: Literal["100644", "100755", "120000"]
    byteSize: int = Field(ge=0)
    contentSha256: Hash


class Change(StrictModel):
    path: str
    oldPath: str | None
    changeType: Literal["ADDED", "MODIFIED", "DELETED", "RENAMED"]
    binary: bool
    gitMode: Literal["000000", "100644", "100755", "120000"]


class Diff(StrictModel):
    path: Literal["artifacts/changes.diff"]
    byteSize: int = Field(ge=0)
    diffSha256: Hash


class Changes(StrictModel):
    files: list[Change] = Field(min_length=1)
    diff: Diff


class Manifest(StrictModel):
    snapshotFormatVersion: Literal["1.0"]
    snapshotId: Uuid
    codingSessionId: Uuid
    baseCommit: Oid
    capturedHeadCommit: Oid
    branch: str = Field(min_length=1)
    workspaceFingerprint: Hash
    createdAt: str
    targetPaths: list[str] = Field(min_length=1)
    excludedChangedPaths: list[str]
    entries: list[Entry]
    changes: Changes

    @classmethod
    def parse(cls, raw, run, settings):
        try:
            value = cls.model_validate(strict_json(raw))
        except ValidationError:
            raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID") from None
        if (
            value.snapshotId != run.snapshot_id
            or value.codingSessionId != run.coding_session_id
        ):
            raise WorkspaceError("SNAPSHOT_IDENTITY_MISMATCH")
        if not valid_time(value.createdAt):
            raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID")
        paths = [e.path for e in value.entries]
        changes = [c.path for c in value.changes.files]
        # Only current entries coexist physically. Deleted changes and range
        # identities may be ancestors/descendants of newly created files.
        distinct(paths)
        for sequence in (paths, changes, value.targetPaths, value.excludedChangedPaths):
            distinct(sequence, check_prefixes=False)
            if sequence != sorted(sequence):
                raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID")
        if set(value.targetPaths) & set(value.excludedChangedPaths) or set(
            value.targetPaths
        ) | set(value.excludedChangedPaths) != set(changes):
            raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID")
        entries = {e.path: e for e in value.entries}
        for entry in value.entries:
            if (entry.type == "SYMLINK") != (entry.gitMode == "120000"):
                raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID")
        for change in value.changes.files:
            if (change.changeType == "DELETED") != (change.gitMode == "000000"):
                raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID")
            if (change.changeType == "DELETED") == (change.path in entries):
                raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID")
            if (
                change.path in entries
                and entries[change.path].gitMode != change.gitMode
            ):
                raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID")
            if change.changeType == "RENAMED":
                if change.oldPath is None:
                    raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID")
                portable(change.oldPath)
            elif change.oldPath is not None:
                raise WorkspaceError("SNAPSHOT_MANIFEST_INVALID")
        if (
            len(paths) > settings.snapshot_max_files
            or any(e.byteSize > settings.snapshot_max_file_bytes for e in value.entries)
            or sum(e.byteSize for e in value.entries)
            > settings.snapshot_max_total_bytes
            or value.changes.diff.byteSize > settings.snapshot_max_diff_bytes
        ):
            raise WorkspaceError("SNAPSHOT_LIMIT_EXCEEDED")
        return value
