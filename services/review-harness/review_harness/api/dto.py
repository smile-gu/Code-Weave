from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, StringConstraints, field_validator

from review_harness.domain.model_call import ExecutionContract
from review_harness.domain.multipass import MultipassExecutionContract, ReviewPlan

UUIDString = Annotated[
    str,
    StringConstraints(
        pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    ),
]
Digest = Annotated[str, StringConstraints(pattern=r"^sha256:[0-9a-f]{64}$")]


class StrictDTO(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Snapshot(StrictDTO):
    snapshotId: UUIDString
    manifestSha256: Digest


class Input(StrictDTO):
    message: str

    @field_validator("message")
    @classmethod
    def bounded_message(cls, value):
        if not value.strip() or len(value.encode("utf-8")) > 32768:
            raise ValueError("message must contain text within the UTF-8 byte limit")
        return value


class CreateRun(StrictDTO):
    protocolVersion: str
    runId: UUIDString
    codingSessionId: UUIDString
    reviewThreadId: UUIDString
    snapshot: Snapshot
    input: Input
    executionContract: ExecutionContract | None = None


class CreateRunV11(StrictDTO):
    protocolVersion: Literal["1.1"]
    runId: UUIDString
    codingSessionId: UUIDString
    reviewThreadId: UUIDString
    snapshot: Snapshot
    input: Input
    executionContract: MultipassExecutionContract
    reviewPlan: ReviewPlan
