"""Small model-facing intake; Evidence identities remain platform-owned."""

from typing import Annotated, Literal

from pydantic import Field

from review_harness.domain.graph import StrictModel
from review_harness.domain.handles import SourceSelection
from review_harness.domain.multipass import PassId
from review_harness.domain.position import LineSelection
from review_harness.domain.review import CandidateSemantics, Coverage, Text


class SourceCandidate(CandidateSemantics):
    passId: PassId
    location: LineSelection
    supportingSources: list[SourceSelection] = Field(
        default_factory=list, max_length=15
    )


class SourceInvestigation(StrictModel):
    submissionVersion: Literal["SOURCE_V1"]
    decision: Literal["CONTINUE", "COMPLETE"]
    # At most two generated Evidence records per candidate (code + domain).
    candidates: list[SourceCandidate] = Field(max_length=8)
    checkedScope: list[Annotated[str, Field(min_length=1, max_length=1024)]] = Field(
        max_length=128
    )
    scopeKind: Literal["FINAL_FILES", "FIXED_DIFF"]
    coverage: Coverage
    limitations: list[Text] = Field(min_length=1, max_length=16)
    incompleteReasons: list[Text] = Field(max_length=16)
