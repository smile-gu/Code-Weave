"""Product pass lifecycle facts, independently confirmed from checkpoints."""

from typing import Literal

from pydantic import Field, model_validator

from review_harness.domain.graph import UUID, Digest, StrictModel
from review_harness.domain.model import valid_time
from review_harness.domain.multipass import (
    PASS_ORDER,
    MultipassPassResult,
    MultipassUsage,
    PassId,
)
from review_harness.domain.review import Timestamp


class ObservedPassUsage(MultipassUsage):
    # Failed calls can exceed the operational window; facts must not be clamped.
    inputTokens: int = Field(ge=0, le=300 * (2**31 - 1))
    unknownCalls: int = Field(default=0, ge=0, le=300)

    @model_validator(mode="after")
    def unknown_count(self):
        if self.unknownCalls > self.modelCalls:
            raise ValueError("Unknown outcomes exceed dispatched calls")
        return self


class PassProgress(StrictModel):
    runId: UUID
    passId: PassId
    ordinal: int = Field(ge=0, le=5)
    selected: bool
    status: Literal["READY", "RUNNING", "COMPLETED", "INCOMPLETE", "SKIPPED"]
    revision: int = Field(ge=1)
    startedAt: Timestamp | None
    finishedAt: Timestamp | None
    reason: str | None = Field(max_length=128, pattern=r"^[A-Z][A-Z0-9_]*$")
    stateRevision: int | None = Field(ge=0)
    stateDigest: Digest | None
    usage: ObservedPassUsage = Field(
        default_factory=lambda: ObservedPassUsage(
            modelCalls=0, inputTokens=0, outputTokens=0
        )
    )

    @model_validator(mode="after")
    def lifecycle(self):
        if PASS_ORDER[self.ordinal] != self.passId:
            raise ValueError("Pass ordinal mismatch")
        if (self.stateRevision is None) != (self.stateDigest is None):
            raise ValueError("Checkpoint reference must be complete")
        for at in (self.startedAt, self.finishedAt):
            if at is not None and not valid_time(at):
                raise ValueError("Invalid pass timestamp")
        if self.startedAt and self.finishedAt and self.finishedAt < self.startedAt:
            raise ValueError("Pass completion precedes start")
        if not self.selected and (
            self.status != "SKIPPED" or self.reason != "USER_NOT_SELECTED"
        ):
            raise ValueError("Unselected pass cannot execute")
        if self.status == "READY" and (
            self.startedAt
            or self.finishedAt
            or self.reason
            or getattr(self, "result", None)
        ):
            raise ValueError("Ready pass has no execution facts")
        if self.status == "RUNNING" and (
            not self.startedAt
            or self.finishedAt
            or self.reason
            or getattr(self, "result", None)
        ):
            raise ValueError("Running pass timestamps mismatch")
        if self.status in {"COMPLETED", "INCOMPLETE"} and (
            not self.startedAt or not self.finishedAt
        ):
            raise ValueError("Executed pass requires start and finish")
        if self.status in {"INCOMPLETE", "SKIPPED"} and not self.reason:
            raise ValueError("Uncompleted pass requires reason")
        return self


class PassFact(PassProgress):
    result: MultipassPassResult | None

    def progress(self):
        return self.model_dump(exclude={"result"})

    @model_validator(mode="after")
    def result_matches(self):
        if self.result and (
            self.result.passId,
            self.result.selected,
            self.result.status,
            self.result.usage.model_dump(),
        ) != (
            self.passId,
            self.selected,
            self.status,
            self.usage.model_dump(exclude={"unknownCalls"}),
        ):
            raise ValueError("Pass result and lifecycle disagree")
        if self.result and self.usage.unknownCalls:
            raise ValueError("Unknown calls cannot deliver a pass result")
        return self
