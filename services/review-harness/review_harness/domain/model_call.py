"""Durable local send intent; remote acceptance is deliberately not inferred."""

from typing import Annotated, Literal

from pydantic import ConfigDict, Field, model_validator

from review_harness.domain.budget import (
    CURRENT_INPUT_BUDGET,
    BudgetPolicyVersion,
    input_budget_for,
)
from review_harness.domain.graph import UUID, ArtifactRef, Digest, StrictModel
from review_harness.domain.model import canonical, valid_time
from review_harness.domain.provider import ProviderUsage
from review_harness.domain.review import Timestamp

AnnotatedCode = Annotated[str, Field(pattern=r"^[A-Z][A-Z0-9_]{0,127}$")]


class InputEstimateWarning(StrictModel):
    code: Literal["INPUT_ESTIMATE_UNDERCOUNT", "INPUT_ESTIMATE_RESERVE_EXCEEDED"]
    estimatedInputTokens: int = Field(ge=0)
    conservativeInputTokens: int = Field(ge=0)
    uncertaintyReserveTokens: int = Field(ge=1024)
    actualInputTokens: int = Field(ge=0)
    differenceTokens: int = Field(gt=0)
    policyVersion: BudgetPolicyVersion


class ModelCall(StrictModel):
    modelCallId: UUID
    runId: UUID
    snapshotId: UUID
    purpose: Literal["INVESTIGATION", "COMPACTION", "VALIDATION"]
    provider: Literal["DEEPSEEK"]
    model: Literal["deepseek-flash"]
    requestDigest: Digest
    manifestRef: ArtifactRef
    meterVersion: Literal["v4.1-2cba9e42-v1"]
    estimatedInputTokens: int = Field(ge=0, le=CURRENT_INPUT_BUDGET)
    reservedOutputTokens: Literal[4096]
    status: Literal["PREPARED", "DISPATCHING", "RETURNED", "LOCAL_CLOSED"]
    createdAt: Timestamp
    updatedAt: Timestamp
    usage: ProviderUsage | None
    failureCode: str | None = Field(default=None, pattern=r"^[A-Z][A-Z0-9_]{0,127}$")
    remoteOutcomeUnknown: bool
    policyVersion: BudgetPolicyVersion | None = None
    conservativeInputTokens: int | None = Field(
        default=None, ge=0, le=CURRENT_INPUT_BUDGET
    )
    uncertaintyReserveTokens: int | None = Field(default=None, ge=1024)
    inputEstimateWarning: InputEstimateWarning | None = None

    def record_dict(self):
        value = self.model_dump()
        for field in (
            "policyVersion",
            "conservativeInputTokens",
            "uncertaintyReserveTokens",
            "inputEstimateWarning",
        ):
            if field not in self.model_fields_set:
                value.pop(field)
        return value

    def record_bytes(self):
        return canonical(self.record_dict())

    @model_validator(mode="after")
    def facts(self):
        limit = input_budget_for(self.policyVersion)
        if self.estimatedInputTokens > limit:
            raise ValueError("Estimate exceeds recorded policy budget")
        budget = (
            self.policyVersion,
            self.conservativeInputTokens,
            self.uncertaintyReserveTokens,
        )
        if any(v is not None for v in budget):
            reserve = max(1024, (self.estimatedInputTokens + 9) // 10)
            if budget != (
                self.policyVersion,
                self.estimatedInputTokens + reserve,
                reserve,
            ):
                raise ValueError("Invalid call budget policy")
            if self.policyVersion is None or self.conservativeInputTokens > limit:
                raise ValueError("Conservative estimate exceeds recorded policy budget")
        warning = self.inputEstimateWarning
        if warning:
            if (
                self.usage is None
                or warning.actualInputTokens > limit
                or warning.estimatedInputTokens != self.estimatedInputTokens
                or warning.actualInputTokens != self.usage.prompt_tokens
                or warning.conservativeInputTokens != self.conservativeInputTokens
                or warning.uncertaintyReserveTokens != self.uncertaintyReserveTokens
                or warning.differenceTokens
                != warning.actualInputTokens - warning.estimatedInputTokens
                or warning.policyVersion != self.policyVersion
                or warning.code
                != (
                    "INPUT_ESTIMATE_UNDERCOUNT"
                    if warning.actualInputTokens <= warning.conservativeInputTokens
                    else "INPUT_ESTIMATE_RESERVE_EXCEEDED"
                )
            ):
                raise ValueError("Warning does not match call facts")
        if (
            not valid_time(self.createdAt)
            or not valid_time(self.updatedAt)
            or self.updatedAt < self.createdAt
        ):
            raise ValueError("Invalid intent timestamps")
        if self.manifestRef.coverage != "FULL":
            raise ValueError("Intent requires complete manifest")
        if self.status in {"PREPARED", "DISPATCHING"} and (
            self.usage or self.failureCode
        ):
            raise ValueError("Unsent/unreturned intent has response facts")
        if self.status == "PREPARED" and self.remoteOutcomeUnknown:
            raise ValueError("Prepared intent has not dispatched")
        if self.status == "RETURNED" and (
            self.usage is None or self.remoteOutcomeUnknown
        ):
            raise ValueError("Returned intent requires received usage")
        return self


class ExecutionContract(StrictModel):
    model_config = ConfigDict(
        json_schema_extra={
            "oneOf": [
                {
                    "properties": {
                        "executionMode": {"const": "SIMULATED"},
                        "resultVersion": {"const": "1.0"},
                    }
                },
                {
                    "properties": {
                        "executionMode": {"const": "REAL"},
                        "resultVersion": {"const": "2.0"},
                    }
                },
            ]
        }
    )
    executionMode: Literal["SIMULATED", "REAL"]
    resultVersion: Literal["1.0", "2.0"]

    @model_validator(mode="after")
    def supported_pair(self):
        if (self.executionMode, self.resultVersion) not in {
            ("SIMULATED", "1.0"),
            ("REAL", "2.0"),
        }:
            raise ValueError("Execution/result version mismatch")
        return self


class ReviewCapabilities(StrictModel):
    protocolVersion: Literal["1.0"]
    configuredContract: ExecutionContract
    status: Literal["READY", "NOT_READY"]
    provider: Literal["DEEPSEEK"] | None
    model: Literal["deepseek-flash"] | None
    meterVersion: Literal["v4.1-2cba9e42-v1"] | None
    failureCodes: list[AnnotatedCode] = Field(max_length=16)


class FeeModelCall(ModelCall):
    callVersion: Literal["2.0"]
    passId: Literal[
        "correctness",
        "consistency",
        "security",
        "performance",
        "architecture",
        "testing",
        "dedup",
    ]
    purpose: Literal["INVESTIGATION", "COMPACTION", "VALIDATION", "DEDUP"]
    policyVersion: Literal["deepseek-compatible-budget-v2"]
    conservativeInputTokens: int = Field(ge=0, le=CURRENT_INPUT_BUDGET)
    uncertaintyReserveTokens: int = Field(ge=1024)

    @model_validator(mode="after")
    def phase(self):
        if (self.passId == "dedup") != (self.purpose == "DEDUP"):
            raise ValueError("Dedup has a separate phase quota")
        return self


def parse_call(value):
    return (
        FeeModelCall if value.get("callVersion") == "2.0" else ModelCall
    ).model_validate(value)
