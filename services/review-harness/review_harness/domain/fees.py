"""Exact CNY management prices and durable budget snapshots, never invoices."""

import re
from decimal import Decimal
from typing import Annotated, Literal

from pydantic import Field, model_validator

from review_harness.domain.graph import UUID, Digest, StrictModel
from review_harness.domain.model import canonical, sha256, valid_time
from review_harness.domain.multipass import ReviewPlan
from review_harness.domain.review import Timestamp

Phase = Literal[
    "correctness",
    "consistency",
    "security",
    "performance",
    "architecture",
    "testing",
    "dedup",
]
Micros = Annotated[int, Field(ge=0, le=9223372036854775807)]
Rate = Annotated[
    str,
    Field(
        max_length=14,
        pattern=re.compile(
            r"^(?:[1-9][0-9]{0,5}(?:\.[0-9]{1,6})?|1000000(?:\.0{1,6})?|0\.(?!0{1,6}$)[0-9]{1,6})$"
        ),
    ),
]


class PriceProfile(StrictModel):
    profileVersion: Literal["1.0"]
    priceProfileId: str = Field(
        min_length=1, max_length=128, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$"
    )
    provider: Literal["DEEPSEEK"]
    model: Literal["deepseek-flash"]
    currency: Literal["CNY"]
    sourceUrl: Literal["https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"]
    sourceDocumentSha256: Digest
    verifiedAt: Timestamp
    expiresAt: Timestamp
    inputCnyPerMillion: Rate
    outputCnyPerMillion: Rate

    @model_validator(mode="after")
    def times(self):
        if (
            not valid_time(self.verifiedAt)
            or not valid_time(self.expiresAt)
            or self.expiresAt <= self.verifiedAt
        ):
            raise ValueError("Invalid verified price interval")
        return self

    @property
    def digest(self):
        return sha256(canonical(self.model_dump()))

    def current_at(self, at):
        return valid_time(at) and self.verifiedAt <= at < self.expiresAt

    def cost_micros(self, inputs, outputs):
        # ceil(tokens * micro-CNY-per-million / one-million), no float anywhere.
        numerator = inputs * int(
            Decimal(self.inputCnyPerMillion) * 1000000
        ) + outputs * int(Decimal(self.outputCnyPerMillion) * 1000000)
        return (numerator + 999999) // 1000000


class PriceProfileView(StrictModel):
    priceProfile: PriceProfile
    digest: Digest

    @model_validator(mode="after")
    def identity(self):
        if self.digest != self.priceProfile.digest:
            raise ValueError("Price profile digest mismatch")
        return self


class FeeBudget(StrictModel):
    json_byte_limit = 16384
    runId: UUID
    reviewPlan: ReviewPlan
    priceProfile: PriceProfile
    runStartedAt: Timestamp
    activePhase: Phase | None
    phaseStartedAt: Timestamp | None
    revision: int = Field(ge=1)
    spentMicros: Micros
    reservedMicros: Micros
    overrunMicros: Micros
    reservedCalls: int = Field(ge=0, le=1)
    modelCalls: int = Field(ge=0, le=2000)
    phaseCalls: int = Field(ge=0, le=300)
    dedupCalls: int = Field(ge=0, le=10)
    stopReason: str | None = Field(pattern=r"^[A-Z][A-Z0-9_]{0,127}$")

    @model_validator(mode="after")
    def facts(self):
        auth = self.reviewPlan.feeAuthorization
        if (auth.priceProfileId, auth.priceProfileDigest) != (
            self.priceProfile.priceProfileId,
            self.priceProfile.digest,
        ):
            raise ValueError("Authorization price snapshot mismatch")
        if not valid_time(self.runStartedAt) or (self.activePhase is None) != (
            self.phaseStartedAt is None
        ):
            raise ValueError("Invalid budget phase time")
        if self.phaseStartedAt is not None and (
            not valid_time(self.phaseStartedAt)
            or self.phaseStartedAt < self.runStartedAt
        ):
            raise ValueError("Invalid phase start")
        if self.reservedCalls and not self.reservedMicros:
            raise ValueError("Pending send requires a reservation")
        if self.phaseCalls > self.modelCalls or self.dedupCalls > self.modelCalls:
            raise ValueError("Budget counter mismatch")
        if self.overrunMicros > self.spentMicros:
            raise ValueError("Overrun cannot exceed actual managed spending")
        return self


class CallReservation(StrictModel):
    json_byte_limit = 16384
    runId: UUID
    modelCallId: UUID
    passId: Phase
    state: Literal["RESERVED", "DISPATCHING", "SETTLED", "UNKNOWN", "REFUNDED"]
    reservedMicros: Annotated[int, Field(gt=0, le=9223372036854775807)]
    actualMicros: Micros | None
    overrunMicros: Micros
    revision: int = Field(ge=1)

    @model_validator(mode="after")
    def settlement(self):
        if self.state in {"RESERVED", "DISPATCHING", "UNKNOWN"} and (
            self.actualMicros is not None or self.overrunMicros
        ):
            raise ValueError("Unknown/unsent calls cannot invent costs")
        if self.state == "SETTLED" and (
            self.actualMicros is None
            or self.overrunMicros != max(0, self.actualMicros - self.reservedMicros)
        ):
            raise ValueError("Invalid exact settlement")
        if self.state == "REFUNDED" and (self.actualMicros != 0 or self.overrunMicros):
            raise ValueError("Only confirmed unsent calls refund")
        return self
