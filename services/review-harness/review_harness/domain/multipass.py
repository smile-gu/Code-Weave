"""Batch 6 wire contracts; historical single-pass models remain unchanged."""

import re
from decimal import Decimal
from typing import Annotated, Literal

from pydantic import Field, field_validator, model_validator

from review_harness.domain.graph import UUID, Digest, StrictModel
from review_harness.domain.model import valid_time
from review_harness.domain.review import (
    CodeReference,
    Coverage,
    EvidenceRecord,
    Finding,
    ReportDraft,
    Text,
    Timestamp,
)
from review_harness.infrastructure.paths import portable

PassId = Literal[
    "correctness", "consistency", "security", "performance", "architecture", "testing"
]
PASS_ORDER = (
    "correctness",
    "consistency",
    "security",
    "performance",
    "architecture",
    "testing",
)


class ExecutionBudget(StrictModel):
    maxModelCallsPerPass: Literal[300]
    maxModelCallsPerRun: Literal[2000]
    maxDedupCalls: Literal[10]
    requestTimeoutSeconds: Literal[120]
    passTimeoutSeconds: Literal[1800]
    runTimeoutSeconds: Literal[10800]
    summaryTimeoutSeconds: Literal[600]


class FeeAuthorization(StrictModel):
    authorizationId: UUID
    currency: Literal["CNY"]
    maxAmount: Annotated[
        str,
        Field(
            max_length=20,
            pattern=re.compile(
                r"^(?:[1-9][0-9]{0,11}(?:\.[0-9]{1,6})?|0\.(?!0{1,6}$)[0-9]{1,6})$"
            ),
        ),
    ]
    confirmedAt: Timestamp
    priceProfileId: str = Field(
        min_length=1, max_length=128, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$"
    )
    priceProfileDigest: Digest

    @field_validator("confirmedAt")
    @classmethod
    def valid_timestamp(cls, value):
        if not valid_time(value):
            raise ValueError("Invalid authorization timestamp")
        return value

    @property
    def amount_micros(self):
        return int(Decimal(self.maxAmount) * 1000000)


class ReviewPlan(StrictModel):
    planVersion: Literal["1.0"]
    selectedPasses: list[PassId] = Field(
        min_length=1, max_length=6, json_schema_extra={"uniqueItems": True}
    )
    executionBudget: ExecutionBudget
    feeAuthorization: FeeAuthorization

    @field_validator("selectedPasses")
    @classmethod
    def canonical_selection(cls, value):
        if value != [p for p in PASS_ORDER if p in value]:
            raise ValueError("Selection must be unique and in canonical order")
        return value


class MultipassUsage(StrictModel):
    modelCalls: int = Field(ge=0, le=300)
    inputTokens: int = Field(ge=0, le=300 * 262144)
    outputTokens: int = Field(ge=0, le=300 * 4096)

    @model_validator(mode="after")
    def zero_calls(self):
        if self.modelCalls == 0 and (self.inputTokens or self.outputTokens):
            raise ValueError("No calls cannot consume tokens")
        return self


class MultipassPassResult(StrictModel):
    passId: PassId
    selected: bool
    status: Literal["COMPLETED", "INCOMPLETE", "SKIPPED"]
    scopeKind: Literal["FINAL_FILES", "FIXED_DIFF"]
    checkedScope: list[Annotated[str, Field(min_length=1, max_length=1024)]] = Field(
        max_length=128
    )
    coverage: Coverage
    limitations: list[Text] = Field(min_length=1, max_length=32)
    incompleteReasons: list[Text] = Field(max_length=32)
    skipReason: Literal["USER_NOT_SELECTED", "RUN_BUDGET_EXHAUSTED"] | None
    unvalidatedCandidates: int = Field(ge=0, le=32)
    findingIds: list[UUID] = Field(max_length=32)
    usage: MultipassUsage

    @model_validator(mode="after")
    def facts(self):
        if len(set(self.checkedScope)) != len(self.checkedScope) or len(
            set(self.findingIds)
        ) != len(self.findingIds):
            raise ValueError("Repeated scope or finding")
        if not self.checkedScope and self.coverage == "FULL":
            raise ValueError("No inspected material cannot claim full coverage")
        for path in self.checkedScope:
            portable(path)
        if self.status == "SKIPPED":
            expected = "RUN_BUDGET_EXHAUSTED" if self.selected else "USER_NOT_SELECTED"
            if (
                self.skipReason != expected
                or self.checkedScope
                or self.findingIds
                or self.unvalidatedCandidates
                or self.usage.modelCalls
                or self.coverage == "FULL"
                or self.incompleteReasons
            ):
                raise ValueError("Skipped pass cannot claim inspection or calls")
        elif not self.selected or self.skipReason is not None:
            raise ValueError("Only selected passes execute")
        if self.status == "COMPLETED" and (
            not self.checkedScope
            or self.coverage != "FULL"
            or self.incompleteReasons
            or self.unvalidatedCandidates
        ):
            raise ValueError("Completed pass requires inspected complete finite scope")
        if self.status == "INCOMPLETE" and not self.incompleteReasons:
            raise ValueError("Incomplete pass requires reasons, including no material")
        return self


class FindingContribution(StrictModel):
    passId: PassId
    candidateId: UUID
    evidenceIds: list[UUID] = Field(
        min_length=1, max_length=16, json_schema_extra={"uniqueItems": True}
    )
    codeReferences: list[CodeReference] = Field(max_length=16)
    disposition: Literal["VERIFIED", "RISK", "SUGGESTION"]
    validationModelCallId: UUID

    @model_validator(mode="after")
    def unique_evidence(self):
        if len(set(self.evidenceIds)) != len(self.evidenceIds):
            raise ValueError("Repeated contributing evidence")
        return self


class MultipassFinding(Finding):
    evidenceIds: list[UUID] = Field(
        min_length=1, max_length=16, json_schema_extra={"uniqueItems": True}
    )
    passId: PassId
    contributions: list[FindingContribution] = Field(min_length=1, max_length=32)

    @model_validator(mode="after")
    def provenance(self):
        if len(set(self.evidenceIds)) != len(self.evidenceIds):
            raise ValueError("Repeated finding evidence")
        primary = min(
            self.contributions,
            key=lambda c: (PASS_ORDER.index(c.passId), c.candidateId),
        )
        if len({c.candidateId for c in self.contributions}) != len(self.contributions):
            raise ValueError("Repeated contributing candidate")
        if (
            self.candidateId,
            self.passId,
            self.disposition,
            self.validationModelCallId,
        ) != (
            primary.candidateId,
            primary.passId,
            primary.disposition,
            primary.validationModelCallId,
        ):
            raise ValueError("Finding must preserve deterministic primary judgment")
        if set(self.evidenceIds) != {
            e for c in self.contributions for e in c.evidenceIds
        }:
            raise ValueError("Merged finding must preserve all contributing evidence")
        if any(
            ref not in self.codeReferences
            for c in self.contributions
            for ref in c.codeReferences
        ) or any(
            ref not in [r for c in self.contributions for r in c.codeReferences]
            for ref in self.codeReferences
        ):
            raise ValueError("Merged finding source provenance mismatch")
        return self


class FeeSummary(StrictModel):
    authorizationId: UUID
    currency: Literal["CNY"]
    maxAmountMicros: int = Field(ge=1, le=999999999999999999)
    spentMicros: int = Field(ge=0, le=9223372036854775807)
    reservedMicros: int = Field(ge=0, le=9223372036854775807)
    overrunMicros: int = Field(ge=0, le=9223372036854775807)
    unknownCalls: int = Field(ge=0, le=2000)

    @model_validator(mode="after")
    def overrun(self):
        if (
            not max(0, self.spentMicros + self.reservedMicros - self.maxAmountMicros)
            <= self.overrunMicros
            <= self.spentMicros
        ):
            raise ValueError(
                "Fee overrun must retain reservation excess and cover budget excess"
            )
        if self.unknownCalls and not self.reservedMicros:
            raise ValueError("Unknown dispatch retains a fee reservation")
        return self


class MultipassDiagnostics(StrictModel):
    executionMode: Literal["REAL", "SIMULATED"]
    contextManifestArtifactIds: list[UUID] = Field(max_length=2000)
    modelCallIds: list[UUID] = Field(max_length=2000)
    meterVersion: Literal["v4.1-2cba9e42-v1"] | None
    limitations: list[Text] = Field(min_length=1, max_length=32)
    dedupUsage: MultipassUsage
    feeSummary: FeeSummary

    @model_validator(mode="after")
    def mode(self):
        if self.dedupUsage.modelCalls > 10:
            raise ValueError("Dedup cannot borrow unused pass quota")
        if self.executionMode == "REAL" and self.meterVersion is None:
            raise ValueError("Real results require a meter")
        if self.executionMode == "SIMULATED" and (
            self.modelCallIds
            or self.feeSummary.spentMicros
            or self.feeSummary.reservedMicros
        ):
            raise ValueError("Simulated execution cannot pretend to have paid calls")
        return self


class ReviewResultV3(StrictModel):
    json_byte_limit = 1024 * 1024
    resultVersion: Literal["3.0"]
    resultId: UUID
    runId: UUID
    snapshotId: UUID
    manifestSha256: Digest
    generatedAt: Timestamp
    reviewPlan: ReviewPlan
    coverageStatus: Literal["SELECTED_COMPLETE", "SELECTED_INCOMPLETE"]
    passResults: list[MultipassPassResult] = Field(min_length=6, max_length=6)
    evidence: list[EvidenceRecord] = Field(max_length=128)
    findings: list[MultipassFinding] = Field(max_length=32)
    reportDraft: ReportDraft
    diagnostics: MultipassDiagnostics

    @model_validator(mode="after")
    def references(self):
        if (
            not valid_time(self.generatedAt)
            or self.generatedAt < self.reviewPlan.feeAuthorization.confirmedAt
        ):
            raise ValueError("Result time must follow fee confirmation")
        if [p.passId for p in self.passResults] != list(PASS_ORDER):
            raise ValueError("Six pass dimensions require canonical order")
        selected = set(self.reviewPlan.selectedPasses)
        if any(p.selected != (p.passId in selected) for p in self.passResults):
            raise ValueError("Pass selection must match original plan")
        complete = all(p.status == "COMPLETED" for p in self.passResults if p.selected)
        if (self.coverageStatus == "SELECTED_COMPLETE") != complete:
            raise ValueError("Overall coverage must describe selected passes")
        auth = self.reviewPlan.feeAuthorization
        fee = self.diagnostics.feeSummary
        if (
            fee.authorizationId != auth.authorizationId
            or fee.maxAmountMicros != auth.amount_micros
        ):
            raise ValueError("Fee summary must match original authorization")
        calls = set(self.diagnostics.modelCallIds)
        if len(calls) != len(self.diagnostics.modelCallIds) or len(
            set(self.diagnostics.contextManifestArtifactIds)
        ) != len(self.diagnostics.contextManifestArtifactIds):
            raise ValueError("Repeated diagnostic identity")
        usages = [p.usage for p in self.passResults] + [self.diagnostics.dedupUsage]
        if sum(u.modelCalls for u in usages) != len(calls) or len(calls) > 2000:
            raise ValueError("Run call count must match all pass and dedup usage")
        if fee.unknownCalls > len(calls):
            raise ValueError("Unknown fee calls cannot exceed recorded calls")
        evidence = {e.evidenceId: e for e in self.evidence}
        if len(evidence) != len(self.evidence):
            raise ValueError("Repeated evidence")
        for e in self.evidence:
            if (
                e.runId != self.runId
                or e.snapshotId != self.snapshotId
                or e.producerId not in calls
                or e.capturedAt > self.generatedAt
            ):
                raise ValueError("Evidence owner, producer or time mismatch")
        if len({f.findingId for f in self.findings}) != len(self.findings):
            raise ValueError("Repeated finding")
        candidate_ids = [c.candidateId for f in self.findings for c in f.contributions]
        if (
            len(set(candidate_ids)) != len(candidate_ids)
            or len(candidate_ids)
            + sum(p.unvalidatedCandidates for p in self.passResults)
            > 32
        ):
            raise ValueError("Global candidate quota/provenance violated")
        passes = {p.passId: p for p in self.passResults}
        for p in self.passResults:
            expected = {
                f.findingId
                for f in self.findings
                if any(c.passId == p.passId for c in f.contributions)
            }
            if set(p.findingIds) != expected:
                raise ValueError("Pass finding lineage mismatch")
        for f in self.findings:
            for contribution in f.contributions:
                p = passes[contribution.passId]
                if (
                    not p.selected
                    or p.status == "SKIPPED"
                    or contribution.validationModelCallId not in calls
                ):
                    raise ValueError("Unexecuted pass cannot contribute a finding")
                if not set(contribution.evidenceIds) <= evidence.keys():
                    raise ValueError("Unknown contributing evidence")
                refs = [
                    ref
                    for eid in contribution.evidenceIds
                    for ref in evidence[eid].sourceRefs
                ]
                if any(ref not in refs for ref in contribution.codeReferences):
                    raise ValueError("Finding source not in contributing evidence")
                if contribution.codeReferences and not any(
                    ref.path in p.checkedScope for ref in contribution.codeReferences
                ):
                    raise ValueError("Contribution outside checked scope")
                if contribution.disposition == "VERIFIED" and (
                    not contribution.codeReferences
                    or not any(
                        evidence[eid].evidenceType == "CODE"
                        for eid in contribution.evidenceIds
                    )
                ):
                    raise ValueError("Verified contribution requires code evidence")
        from review_harness.application.report import render_multipass_report

        expected = render_multipass_report(
            self.passResults,
            self.findings,
            self.coverageStatus,
            locale="en"
            if self.reportDraft.markdown.startswith("# Multi-pass review draft\n\n")
            else "zh-CN",
        )
        if self.reportDraft != expected:
            raise ValueError("Report must match structured pass facts and findings")
        return self


class MultipassExecutionContract(StrictModel):
    executionMode: Literal["REAL", "SIMULATED"]
    resultVersion: Literal["3.0"]


class MultipassCapabilities(StrictModel):
    protocolVersion: Literal["1.1"]
    configuredContract: MultipassExecutionContract
    planVersions: list[Literal["1.0"]] = Field(min_length=1, max_length=1)
    feeReservation: Literal["READY", "NOT_READY"]
    status: Literal["READY", "NOT_READY"]
    failureCodes: list[Annotated[str, Field(pattern=r"^[A-Z][A-Z0-9_]{0,127}$")]] = (
        Field(max_length=16)
    )

    @model_validator(mode="after")
    def readiness(self):
        if self.status == "READY" and (
            self.failureCodes or self.feeReservation != "READY"
        ):
            raise ValueError("Multipass readiness requires durable fee protection")
        return self
