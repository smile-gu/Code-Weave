"""Explicit position-only validation protocol; historical quote schemas stay strict."""

from typing import Literal

from pydantic import Field

from review_harness.domain.graph import ArtifactRef, StrictModel
from review_harness.domain.handles import (
    CandidateHandle,
    LocalValidation,
    SourceSelection,
)
from review_harness.domain.multipass_state import MultipassState, PassConfirmedCandidate
from review_harness.domain.real_graph import HandleReviewGraphState
from review_harness.domain.review import Text


class LineSelection(SourceSelection):
    startLine: int = Field(ge=1)
    endLine: int = Field(ge=1)


class PositionFactCheck(StrictModel):
    candidateHandle: CandidateHandle
    candidateField: str = Field(min_length=1, max_length=128, pattern=r"^/")
    sourceSelection: LineSelection
    userQuote: Text | None = None
    outcome: Literal["SUPPORTED", "CORRECTED", "CORE_CONTRADICTED", "UNRESOLVED"]
    explanation: Text


class PositionValidation(LocalValidation):
    factChecks: list[PositionFactCheck] = Field(default_factory=list, max_length=16)


class PositionValidationBatch(StrictModel):
    validationVersion: Literal["POSITION_V1"]
    validations: list[PositionValidation] = Field(min_length=1, max_length=32)


class PassPositionValidation(PositionValidation):
    correctedCandidate: PassConfirmedCandidate | None = None


class PassPositionValidationBatch(PositionValidationBatch):
    validations: list[PassPositionValidation] = Field(min_length=1, max_length=32)


class PositionReviewState(HandleReviewGraphState):
    stateVersion: Literal["5.0"] = "5.0"
    validationProtocol: Literal["POSITION_V1"] = "POSITION_V1"
    validationRecordRefs: list[ArtifactRef] = Field(default_factory=list, max_length=6)


class PositionMultipassState(MultipassState):
    stateVersion: Literal["6.0"] = "6.0"
    validationProtocol: Literal["POSITION_V1"] = "POSITION_V1"
    validationRecordRefs: list[ArtifactRef] = Field(default_factory=list, max_length=6)
