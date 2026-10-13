"""Real execution state keeps all provider ID mappings, even after compaction."""

from typing import Literal

from pydantic import Field, field_validator, model_validator

from review_harness.domain.graph import (
    UUID,
    ModelResponse,
    ReviewGraphState,
    StrictModel,
)
from review_harness.domain.handles import HandleRegistry, SubmissionReceipt
from review_harness.domain.provider import ProviderCallMapping
from review_harness.domain.review import ReviewCandidate


class Inspection(StrictModel):
    path: str = Field(min_length=1, max_length=1024)
    coverage: Literal["FULL", "PARTIAL"]
    ranges: list[list[int]] = Field(default_factory=list, max_length=32)
    finalLine: int | None = Field(default=None, ge=0)


class ArtifactProgress(StrictModel):
    artifactId: UUID
    nextCursor: str | None = Field(default=None, max_length=1024)
    completed: bool = False
    path: str = Field(min_length=1, max_length=1024)
    startLine: int = Field(ge=1)
    endLine: int = Field(ge=0)
    finalPage: bool
    readable: bool


class RealModelResponse(ModelResponse):
    content: str = Field(max_length=65536)

    @field_validator("content")
    @classmethod
    def response_bytes(cls, text):
        if len(text.encode("utf-8")) > 65536:
            raise ValueError("Response byte budget exceeded")
        return text


class RealReviewGraphState(ReviewGraphState):
    stateVersion: Literal["2.0"] = "2.0"
    providerCallMappings: list[ProviderCallMapping] = Field(default_factory=list)
    toolCallCount: int = Field(default=0, ge=0)
    evidenceRefs: list[UUID] = Field(default_factory=list, max_length=128)
    candidateRefs: list[UUID] = Field(default_factory=list, max_length=32)
    candidates: list[ReviewCandidate] = Field(default_factory=list, max_length=32)
    inspections: list[Inspection] = Field(default_factory=list, max_length=128)
    artifactProgress: list[ArtifactProgress] = Field(
        default_factory=list, max_length=32
    )
    diffNextCursor: str | None = Field(default=None, max_length=1024)
    diffStarted: bool = False
    diffCompleted: bool = False
    correctionCount: int = Field(default=0, ge=0, le=2)

    @model_validator(mode="after")
    def mapping(self):
        if set(self.candidateRefs) != {c.candidateId for c in self.candidates} or len(
            set(self.candidateRefs)
        ) != len(self.candidates):
            raise ValueError("Candidate identities do not match structured state")
        if any(
            not set(c.evidenceIds) <= set(self.evidenceRefs) for c in self.candidates
        ):
            raise ValueError("Candidate evidence anchors missing")
        pairs = {m.toolCallId: m.providerToolCallId for m in self.providerCallMappings}
        if len(pairs) != len(self.providerCallMappings) or len(
            set(pairs.values())
        ) != len(pairs):
            raise ValueError("Provider call IDs must be unique across the run")
        if self.toolCallCount != len(pairs):
            raise ValueError("Tool call count does not match mapping")
        for message in self.messages:
            for call in message.toolCalls:
                if pairs.get(call.toolCallId) != call.providerToolCallId:
                    raise ValueError("Provider call mapping mismatch")
        return self


class HandleReviewGraphState(RealReviewGraphState):
    stateVersion: Literal["3.0"] = "3.0"
    handleRegistry: HandleRegistry
    lastSubmissionReceipt: SubmissionReceipt | None = None

    @model_validator(mode="after")
    def handle_ownership(self):
        registry = self.handleRegistry
        if (registry.runId, registry.snapshotId) != (self.runId, self.snapshotId):
            raise ValueError("Handle registry ownership mismatch")
        if any(
            s.source.sourceType == "USER_INPUT"
            and s.source.sha256 != self.userGoalDigest
            for s in registry.sources
        ):
            raise ValueError("User source does not match original request")
        if {e.evidenceId for e in registry.evidence} != set(self.evidenceRefs) or {
            c.candidateId for c in registry.candidates
        } != set(self.candidateRefs):
            raise ValueError("Handles must match confirmed state anchors")
        if self.lastSubmissionReceipt:
            receipt = self.lastSubmissionReceipt
            if (
                receipt.checkpointRevision > self.revision
                or not {e.evidenceHandle for e in receipt.newEvidence}
                <= {e.evidenceHandle for e in registry.evidence}
                or not set(receipt.candidateHandles)
                <= {c.candidateHandle for c in registry.candidates}
            ):
                raise ValueError("Receipt is not bound to confirmed state")
        return self


def parse_state(value):
    if value.get("stateVersion") == "4.0":
        from review_harness.domain.multipass_state import MultipassState

        return MultipassState.model_validate(value)
    model = (
        HandleReviewGraphState
        if value.get("stateVersion") == "3.0"
        else RealReviewGraphState
        if value.get("stateVersion") == "2.0"
        else ReviewGraphState
    )
    return model.model_validate(value)
