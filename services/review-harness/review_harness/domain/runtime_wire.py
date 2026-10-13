"""Explicit 1.1 lifecycle wire shapes; old event/status schemas are untouched."""

from typing import Literal

from pydantic import Field, model_validator

from review_harness.domain.graph import UUID, Digest, StrictModel
from review_harness.domain.model import valid_time
from review_harness.domain.pass_fact import PassProgress
from review_harness.domain.review import Timestamp

Status = Literal["PENDING", "RUNNING", "CANCELLING", "SUCCEEDED", "FAILED", "CANCELLED"]
Resource = Literal["NOT_ACQUIRED", "ACTIVE", "RELEASING", "RELEASED", "UNCERTAIN"]
Code = str


class ResultDescriptor(StrictModel):
    resultId: UUID
    resultVersion: Literal["3.0"]
    resultSha256: Digest


class FailureInfo(StrictModel):
    code: str = Field(pattern=r"^[A-Z][A-Z0-9_]{0,127}$")
    message: str = Field(min_length=1, max_length=1024)
    diagnosticId: UUID


class RunStatusV11(StrictModel):
    protocolVersion: Literal["1.1"]
    runId: UUID
    status: Status
    resourceState: Resource
    createdAt: Timestamp
    startedAt: Timestamp | None
    finishedAt: Timestamp | None
    updatedAt: Timestamp
    lastEventSequence: int = Field(ge=1)
    result: ResultDescriptor | None
    failure: FailureInfo | None
    recoveryCode: str | None = Field(pattern=r"^[A-Z][A-Z0-9_]{0,127}$")

    @model_validator(mode="after")
    def combinations(self):
        from review_harness.domain.model import COMBINATIONS, ResourceState
        from review_harness.domain.model import Status as RunStatus

        if (
            ResourceState(self.resourceState)
            not in COMBINATIONS[RunStatus(self.status)]
        ):
            raise ValueError("Invalid status/resource pair")
        if (self.result is not None) != (self.status == "SUCCEEDED") or (
            self.failure is not None
        ) != (self.status == "FAILED"):
            raise ValueError("Terminal payload mismatch")
        for value in (self.createdAt, self.startedAt, self.finishedAt, self.updatedAt):
            if value is not None and not valid_time(value):
                raise ValueError("Invalid lifecycle time")
        return self


class BasePayload(StrictModel):
    status: Status
    resourceState: Resource


class SuccessPayload(BasePayload):
    result: ResultDescriptor


class FailedPayload(BasePayload):
    failure: FailureInfo


class ReleasePayload(BasePayload):
    previousResourceState: Literal["UNCERTAIN"]
    recoveryCode: str | None = Field(default=None, pattern=r"^[A-Z][A-Z0-9_]{0,127}$")


class PassPayload(BasePayload):
    passes: list[PassProgress] = Field(min_length=6, max_length=6)


class RunEventV11(StrictModel):
    protocolVersion: Literal["1.1"]
    runId: UUID
    sequence: int = Field(ge=1)
    eventType: Literal[
        "REVIEW_RUN_ACCEPTED",
        "REVIEW_RUN_STARTED",
        "REVIEW_RUN_CANCELLING",
        "REVIEW_RUN_SUCCEEDED",
        "REVIEW_RUN_FAILED",
        "REVIEW_RUN_CANCELLED",
        "REVIEW_RUN_RESOURCES_RELEASED",
        "REVIEW_PASS_UPDATED",
    ]
    occurredAt: Timestamp
    payload: PassPayload | SuccessPayload | FailedPayload | ReleasePayload | BasePayload

    @model_validator(mode="after")
    def event_payload(self):
        from review_harness.domain.multipass import PASS_ORDER

        expected = {
            "REVIEW_PASS_UPDATED": PassPayload,
            "REVIEW_RUN_SUCCEEDED": SuccessPayload,
            "REVIEW_RUN_FAILED": FailedPayload,
            "REVIEW_RUN_RESOURCES_RELEASED": ReleasePayload,
        }.get(self.eventType, BasePayload)
        if type(self.payload) is not expected or not valid_time(self.occurredAt):
            raise ValueError("Event payload does not match its type")
        if isinstance(self.payload, PassPayload) and (
            [p.passId for p in self.payload.passes] != list(PASS_ORDER)
            or any(p.runId != self.runId for p in self.payload.passes)
        ):
            raise ValueError("Pass projection identity mismatch")
        return self
