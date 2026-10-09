import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import StrEnum
from uuid import UUID, uuid4


class Status(StrEnum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    CANCELLING = "CANCELLING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class ResourceState(StrEnum):
    NOT_ACQUIRED = "NOT_ACQUIRED"
    ACTIVE = "ACTIVE"
    RELEASING = "RELEASING"
    RELEASED = "RELEASED"
    UNCERTAIN = "UNCERTAIN"


TERMINAL = {Status.SUCCEEDED, Status.FAILED, Status.CANCELLED}
COMBINATIONS = {
    Status.PENDING: {ResourceState.NOT_ACQUIRED},
    Status.RUNNING: {ResourceState.ACTIVE},
    Status.CANCELLING: {ResourceState.RELEASING},
    Status.SUCCEEDED: {ResourceState.RELEASED},
    Status.FAILED: {
        ResourceState.NOT_ACQUIRED,
        ResourceState.RELEASED,
        ResourceState.UNCERTAIN,
    },
    Status.CANCELLED: {ResourceState.NOT_ACQUIRED, ResourceState.RELEASED},
}


def canonical(value: dict) -> bytes:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")


def sha256(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def utc_now() -> str:
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )


def valid_uuid(value: str) -> bool:
    try:
        return str(UUID(value)) == value
    except (ValueError, TypeError, AttributeError):
        return False


def valid_time(value: str) -> bool:
    if not isinstance(value, str) or not re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z", value
    ):
        return False
    try:
        datetime.fromisoformat(value)
        return True
    except ValueError:
        return False


class HarnessError(Exception):
    def __init__(self, code: str, status: int, detail: str):
        super().__init__(code)
        self.code, self.status, self.detail = code, status, detail


class SessionBlocked(HarnessError):
    """A valid Run cannot acquire execution rights in a protected Session."""


def _require(condition: bool) -> None:
    if not condition:
        raise integrity_error()


def integrity_error() -> HarnessError:
    return HarnessError(
        "REVIEW_RUN_INTEGRITY_ERROR", 500, "运行持久事实未通过完整性校验"
    )


@dataclass(frozen=True)
class Failure:
    code: str
    message: str
    diagnostic_id: str

    @classmethod
    def make(cls, code: str) -> "Failure":
        return cls(
            code,
            "Review 运行未能安全完成" if "CLEANUP" in code else "Review 运行未能完成",
            str(uuid4()),
        )

    def wire(self) -> dict:
        return {
            "code": self.code,
            "message": self.message,
            "diagnosticId": self.diagnostic_id,
        }

    def validate(self):
        if (
            not re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", self.code)
            or not 1 <= len(self.message) <= 1024
            or not valid_uuid(self.diagnostic_id)
        ):
            raise integrity_error()


@dataclass(frozen=True)
class Run:
    run_id: str
    protocol_version: str
    request_fingerprint: str
    request_payload: bytes
    coding_session_id: str
    review_thread_id: str
    snapshot_id: str
    manifest_sha256: str
    status: str
    resource_state: str
    created_at: str
    started_at: str | None
    finished_at: str | None
    updated_at: str
    last_event_sequence: int
    result_id: str | None
    result_sha256: str | None
    result_version: str | None
    failure_code: str | None
    failure_message: str | None
    diagnostic_id: str | None
    recovery_code: str | None

    @property
    def failure(self) -> Failure | None:
        if self.failure_code:
            return Failure(self.failure_code, self.failure_message, self.diagnostic_id)
        return None

    @property
    def descriptor(self) -> dict | None:
        if self.result_id:
            return {
                "resultId": self.result_id,
                "resultVersion": self.result_version,
                "resultSha256": self.result_sha256,
            }
        return None

    def wire(self) -> dict:
        return {
            "protocolVersion": self.protocol_version,
            "runId": self.run_id,
            "status": self.status,
            "resourceState": self.resource_state,
            "createdAt": self.created_at,
            "startedAt": self.started_at,
            "finishedAt": self.finished_at,
            "updatedAt": self.updated_at,
            "lastEventSequence": self.last_event_sequence,
            "result": self.descriptor,
            "failure": self.failure.wire() if self.failure else None,
            "recoveryCode": self.recovery_code,
        }

    def validate(self) -> None:
        try:
            status, resource = Status(self.status), ResourceState(self.resource_state)
            _require(resource in COMBINATIONS[status])
            _require(
                all(
                    valid_uuid(v)
                    for v in (
                        self.run_id,
                        self.coding_session_id,
                        self.review_thread_id,
                        self.snapshot_id,
                    )
                )
            )
            _require(self.protocol_version == "1.0")
            _require(re.fullmatch(r"sha256:[0-9a-f]{64}", self.manifest_sha256))
            _require(self.last_event_sequence >= 1)
            _require(
                valid_time(self.created_at)
                and valid_time(self.updated_at)
                and self.created_at <= self.updated_at
            )
            _require((self.finished_at is not None) == (status in TERMINAL))
            if self.finished_at:
                _require(
                    valid_time(self.finished_at)
                    and self.created_at <= self.finished_at <= self.updated_at
                )
            if self.started_at:
                _require(
                    valid_time(self.started_at)
                    and self.created_at
                    <= self.started_at
                    <= (self.finished_at or self.updated_at)
                )
            _require(
                (self.started_at is not None)
                == (resource != ResourceState.NOT_ACQUIRED)
            )
            result_fields = (self.result_id, self.result_sha256, self.result_version)
            failure_fields = (
                self.failure_code,
                self.failure_message,
                self.diagnostic_id,
            )
            _require(
                all(v is not None for v in result_fields)
                if status == Status.SUCCEEDED
                else all(v is None for v in result_fields)
            )
            _require(
                all(v is not None for v in failure_fields)
                if status == Status.FAILED
                else all(v is None for v in failure_fields)
            )
            if self.descriptor:
                _require(valid_uuid(self.result_id) and self.result_version == "1.0")
                _require(re.fullmatch(r"sha256:[0-9a-f]{64}", self.result_sha256))
            if self.failure:
                self.failure.validate()
            if self.recovery_code is not None:
                _require(re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", self.recovery_code))
            request = json.loads(self.request_payload)
            _require(
                canonical(request) == self.request_payload
                and sha256(self.request_payload) == self.request_fingerprint
            )
            _require(
                set(request)
                == {
                    "protocolVersion",
                    "runId",
                    "codingSessionId",
                    "reviewThreadId",
                    "snapshot",
                    "input",
                }
            )
            _require(
                request["protocolVersion"] == self.protocol_version
                and request["runId"] == self.run_id
            )
            _require(
                request["codingSessionId"] == self.coding_session_id
                and request["reviewThreadId"] == self.review_thread_id
            )
            _require(
                request["snapshot"]
                == {
                    "snapshotId": self.snapshot_id,
                    "manifestSha256": self.manifest_sha256,
                }
            )
            _require(set(request["input"]) == {"message"})
            message = request["input"]["message"]
            _require(
                isinstance(message, str)
                and message.strip()
                and len(message.encode("utf-8")) <= 32768
            )
        except (ValueError, TypeError, KeyError, UnicodeError):
            raise integrity_error() from None


def empty_result(run: Run, at: str, result_id: str) -> bytes:
    return canonical(
        {
            "resultVersion": "1.0",
            "resultId": result_id,
            "runId": run.run_id,
            "snapshotId": run.snapshot_id,
            "manifestSha256": run.manifest_sha256,
            "generatedAt": at,
            "passResults": [],
            "evidence": [],
            "findings": [],
            "reportDraft": {"markdown": "", "byteSize": 0},
            "diagnostics": {"contextManifestArtifactIds": []},
        }
    )
