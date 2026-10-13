"""Versioned multipass identities; historical contracts retain their exact shapes."""

from typing import Literal
from uuid import uuid4

from pydantic import Field, model_validator

from review_harness.domain.graph import ExpandedContextManifest, ModelUsage, StrictModel
from review_harness.domain.handles import (
    ConfirmedCandidate,
    HandleRegistry,
    LocalCandidate,
    LocalInvestigation,
    LocalValidation,
    LocalValidationBatch,
    SourceBinding,
    source_identity,
)
from review_harness.domain.multipass import (
    PASS_ORDER,
    MultipassPassResult,
    PassId,
    ReviewPlan,
)
from review_harness.domain.real_graph import HandleReviewGraphState
from review_harness.domain.review import (
    CandidateFields,
    CandidateValidation,
    Finding,
    ReviewCandidate,
    Timestamp,
    ValidationBatch,
)


class PassSourceBinding(SourceBinding):
    passId: PassId

    def view(self):
        return {**super().view(), "observedInPass": self.passId}


class MultipassRegistry(HandleRegistry):
    activePass: PassId
    sources: list[PassSourceBinding] = Field(default_factory=list, max_length=64)

    def binding_identity(self, binding):
        return (
            binding.passId,
            source_identity(binding.source, binding.coverage, binding.redactionState),
        )

    def source_matches(self, binding, identity):
        return (
            binding.passId == self.activePass
            and self.binding_identity(binding)[1] == identity
        )

    def source_fields(self):
        return {"passId": self.activePass}

    def for_pass(self, pass_id):
        if PASS_ORDER.index(pass_id) <= PASS_ORDER.index(self.activePass):
            raise ValueError("Pass sources cannot return to an earlier phase")
        return type(self).model_validate({**self.model_dump(), "activePass": pass_id})


class PassCandidate(ReviewCandidate):
    passId: PassId


class PassCandidateFields(CandidateFields):
    passId: PassId


class PassFinding(Finding):
    passId: PassId


class PassLocalCandidate(LocalCandidate):
    passId: PassId


class PassConfirmedCandidate(ConfirmedCandidate):
    passId: PassId


class PassInvestigation(LocalInvestigation):
    candidates: list[PassLocalCandidate] = Field(max_length=32)


class PassLocalValidation(LocalValidation):
    correctedCandidate: PassConfirmedCandidate | None = None


class PassLocalValidationBatch(LocalValidationBatch):
    validations: list[PassLocalValidation] = Field(min_length=1, max_length=32)


class PassValidation(CandidateValidation):
    correctedCandidate: PassCandidateFields | None = None


class PassValidationBatch(ValidationBatch):
    validations: list[PassValidation] = Field(min_length=1, max_length=32)


class PassOutcome(StrictModel):
    passResult: MultipassPassResult
    findings: list[PassFinding] = Field(max_length=32)


class MultipassContextManifest(ExpandedContextManifest):
    manifestVersion: Literal["1.3"] = "1.3"
    callPurpose: Literal["INVESTIGATION", "COMPACTION", "VALIDATION", "DEDUP"]
    passId: Literal[
        "correctness",
        "consistency",
        "security",
        "performance",
        "architecture",
        "testing",
        "dedup",
    ]


class MultipassState(HandleReviewGraphState):
    stateVersion: Literal["4.0"] = "4.0"
    passId: PassId
    activePass: PassId
    reviewPlan: ReviewPlan
    runStartedAt: Timestamp
    phaseStartedAt: Timestamp | None = None
    runModelCalls: int = Field(default=0, ge=0, le=2000)
    aggregating: bool = False
    modelCallCount: int = Field(default=0, ge=0, le=300)
    graphStepCount: int = Field(default=0, ge=0, le=1204)
    modelUsages: list[ModelUsage] = Field(default_factory=list, max_length=300)
    candidates: list[PassCandidate] = Field(default_factory=list, max_length=32)
    handleRegistry: MultipassRegistry
    passLedger: list[PassOutcome] = Field(default_factory=list, max_length=6)

    @model_validator(mode="after")
    def pass_ownership(self):
        if (
            self.passId != self.activePass
            or self.handleRegistry.activePass != self.activePass
        ):
            raise ValueError("Pass identity mismatch")
        if (
            self.activePass not in self.reviewPlan.selectedPasses
            or self.modelCallCount > self.runModelCalls
        ):
            raise ValueError("Unselected pass or inconsistent call counts")
        order = [o.passResult.passId for o in self.passLedger]
        if order != [p for p in self.reviewPlan.selectedPasses if p in order]:
            raise ValueError("Pass ledger must be unique and ordered")
        if any(PASS_ORDER.index(p) > PASS_ORDER.index(self.activePass) for p in order):
            raise ValueError("Ledger cannot contain future passes")
        return self

    def for_pass(self, pass_id, started_at):
        return self.updated(
            activePass=pass_id,
            passId=pass_id,
            handleRegistry=self.handleRegistry.for_pass(pass_id).model_dump(),
            phaseStartedAt=started_at,
            investigationId=str(uuid4()),
            revision=self.revision + 1,
            messages=[],
            artifactRefs=[],
            sourceRefs=[],
            summaryVersion=0,
            summaryArtifactRef=None,
            sourceMessageIds=[],
            sourceDigest=None,
            pendingToolCalls=[],
            modelCallCount=0,
            modelUsages=[],
            graphStepCount=0,
            compactionCount=0,
            correctionCount=0,
            lastContextManifestRef=None,
            inspections=[],
            artifactProgress=[],
            diffNextCursor=None,
            diffStarted=False,
            diffCompleted=False,
            lastSubmissionReceipt=None,
        )


def current_candidates(state):
    return [
        c
        for c in state.candidates
        if not isinstance(state, MultipassState) or c.passId == state.activePass
    ]
