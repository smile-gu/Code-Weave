"""Run-bound local names; authority remains in verified original material."""

from typing import Annotated, Literal

from pydantic import Field, TypeAdapter, model_serializer, model_validator

from review_harness.domain.graph import UUID, Digest, StrictModel
from review_harness.domain.model import canonical
from review_harness.domain.review import (
    CandidateSemantics,
    Coverage,
    EvidenceSource,
    InvestigationSubmission,
    Text,
    ValidationSummary,
)
from review_harness.domain.submission import SubmissionIssue

SourceHandle = Annotated[str, Field(pattern=r"^s[1-9][0-9]*$", max_length=16)]
EvidenceHandle = Annotated[str, Field(pattern=r"^e[1-9][0-9]*$", max_length=16)]
CandidateHandle = Annotated[str, Field(pattern=r"^c[1-9][0-9]*$", max_length=16)]
LocalKey = Annotated[str, Field(pattern=r"^n[1-9][0-9]*$", max_length=16)]
Range = Annotated[list[Annotated[int, Field(ge=1)]], Field(min_length=2, max_length=2)]
SOURCE = TypeAdapter(EvidenceSource)


def source_identity(source, coverage, redaction):
    return canonical(
        {
            "source": source.model_dump(exclude={"startLine", "endLine"}),
            "coverage": coverage,
            "redactionState": redaction,
        }
    )


def merge_ranges(ranges):
    merged = []
    for lower, upper in sorted(ranges):
        if upper < lower:
            raise ValueError("Invalid observation range")
        if merged and lower <= merged[-1][1] + 1:
            merged[-1][1] = max(merged[-1][1], upper)
        else:
            merged.append([lower, upper])
    return merged


class SourceSelection(StrictModel):
    sourceHandle: SourceHandle
    startLine: int | None = Field(default=None, ge=1)
    endLine: int | None = Field(default=None, ge=1)

    @model_serializer(mode="wrap")
    def persisted_coordinates(self, serialize):
        return {
            key: value for key, value in serialize(self).items() if value is not None
        }

    @model_validator(mode="after")
    def range(self):
        if (self.startLine is None) != (self.endLine is None) or (
            self.startLine is not None and self.endLine < self.startLine
        ):
            raise ValueError("Incomplete selection coordinates")
        return self


class NewEvidenceLink(StrictModel):
    kind: Literal["NEW"]
    key: LocalKey


class SavedEvidenceLink(StrictModel):
    kind: Literal["SAVED"]
    handle: EvidenceHandle


EvidenceLink = Annotated[
    NewEvidenceLink | SavedEvidenceLink, Field(discriminator="kind")
]


class LocalEvidence(StrictModel):
    localKey: LocalKey
    evidenceType: Literal["CODE", "DOMAIN_CONTEXT"]
    selections: list[SourceSelection] = Field(min_length=1, max_length=16)
    coverage: Coverage
    summary: Text


class LocalCandidate(CandidateSemantics):
    evidenceLinks: list[EvidenceLink] = Field(min_length=1, max_length=16)
    codeSelections: list[SourceSelection] = Field(max_length=16)


class ConfirmedCandidate(LocalCandidate):
    candidateHandle: CandidateHandle


class LocalInvestigation(InvestigationSubmission):
    evidenceCandidates: list[LocalEvidence] = Field(max_length=16)
    candidates: list[LocalCandidate] = Field(max_length=32)


class LocalFactCheck(StrictModel):
    candidateHandle: CandidateHandle
    sourceHandle: SourceHandle
    candidateText: Text
    sourceQuote: Text
    userQuote: Text | None = None
    outcome: Literal["SUPPORTED", "CORRECTED", "CORE_CONTRADICTED", "UNRESOLVED"]
    explanation: Text


class LocalValidation(StrictModel):
    candidateHandle: CandidateHandle
    disposition: Literal["VERIFIED", "RISK", "SUGGESTION", "REJECTED"]
    validationSummary: ValidationSummary
    correctedCandidate: ConfirmedCandidate | None = None
    factChecks: list[LocalFactCheck] = Field(default_factory=list, max_length=16)


class LocalValidationBatch(StrictModel):
    validations: list[LocalValidation] = Field(min_length=1, max_length=32)


class SourceBinding(StrictModel):
    sourceHandle: SourceHandle
    source: EvidenceSource
    observedRanges: list[Range] = Field(max_length=32)
    coverage: Coverage
    redactionState: Literal["NONE", "APPLIED"]
    expectedCursor: str | None = Field(default=None, max_length=1024)
    pendingLine: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def observations(self):
        if self.observedRanges != merge_ranges(self.observedRanges):
            raise ValueError("Observation ranges must be canonical")
        if self.source.sourceType == "CODE" and self.source.side == "base":
            raise ValueError("Baseline source is unsupported")
        if self.source.sourceType == "USER_INPUT" and self.observedRanges:
            raise ValueError("User context has no line coordinates")
        return self

    def view(self):
        source = self.source
        detail = (
            {"path": source.path, "side": source.side}
            if source.sourceType == "CODE"
            else {"artifactId": source.artifactId}
            if source.sourceType == "ARTIFACT"
            else {}
        )
        return {
            "sourceHandle": self.sourceHandle,
            "sourceKind": source.sourceType,
            **detail,
            "observedRanges": self.observedRanges,
            "coverage": self.coverage,
            "redactionState": self.redactionState,
        }


class EvidenceBinding(StrictModel):
    evidenceHandle: EvidenceHandle
    evidenceId: UUID
    selections: list[SourceSelection] = Field(min_length=1, max_length=16)


class CandidateBinding(StrictModel):
    candidateHandle: CandidateHandle
    candidateId: UUID


class EvidenceReceipt(StrictModel):
    localKey: LocalKey
    evidenceHandle: EvidenceHandle


class SubmissionReceipt(StrictModel):
    modelCallId: UUID
    submissionDigest: Digest
    checkpointRevision: int = Field(ge=0)
    newEvidence: list[EvidenceReceipt] = Field(max_length=16)
    candidateHandles: list[CandidateHandle] = Field(max_length=32)

    @model_validator(mode="after")
    def unique_batch(self):
        if (
            len({e.localKey for e in self.newEvidence}) != len(self.newEvidence)
            or len({e.evidenceHandle for e in self.newEvidence})
            != len(self.newEvidence)
            or len(set(self.candidateHandles)) != len(self.candidateHandles)
        ):
            raise ValueError("Receipt contains duplicate batch bindings")
        return self


class HandleRegistry(StrictModel):
    runId: UUID
    snapshotId: UUID
    sources: list[SourceBinding] = Field(default_factory=list, max_length=64)
    evidence: list[EvidenceBinding] = Field(default_factory=list, max_length=128)
    candidates: list[CandidateBinding] = Field(default_factory=list, max_length=32)

    @model_validator(mode="after")
    def identities(self):
        for values, field, prefix, identity in (
            (self.sources, "sourceHandle", "s", None),
            (self.evidence, "evidenceHandle", "e", "evidenceId"),
            (self.candidates, "candidateHandle", "c", "candidateId"),
        ):
            if [getattr(v, field) for v in values] != [
                f"{prefix}{i}" for i in range(1, len(values) + 1)
            ]:
                raise ValueError("Handle sequence must be platform assigned")
            if identity and len({getattr(v, identity) for v in values}) != len(values):
                raise ValueError("Repeated handle identity")
        if len({self.binding_identity(s) for s in self.sources}) != len(self.sources):
            raise ValueError("Repeated source identity")
        for evidence in self.evidence:
            for selection in evidence.selections:
                try:
                    self.resolve(selection)
                except SubmissionIssue:
                    raise ValueError(
                        "Evidence handle contains an unobserved source"
                    ) from None
        return self

    def binding_identity(self, binding):
        return source_identity(binding.source, binding.coverage, binding.redactionState)

    def source_matches(self, binding, identity):
        return self.binding_identity(binding) == identity

    def source_fields(self):
        return {}

    def register_source(self, source, coverage, redaction, ranges):
        source = SOURCE.validate_python(source)
        identity = source_identity(source, coverage, redaction)
        values = [s.model_dump() for s in self.sources]
        for index, binding in enumerate(self.sources):
            if self.source_matches(binding, identity):
                values[index]["observedRanges"] = merge_ranges(
                    [*binding.observedRanges, *ranges]
                )
                return self.model_validate(
                    {**self.model_dump(), "sources": values}
                ), binding.sourceHandle
        handle = f"s{len(values) + 1}"
        if len(values) >= 64:
            from review_harness.domain.graph import graph_error

            raise graph_error("CONTEXT_REFERENCE_LIMIT_EXCEEDED")
        values.append(
            dict(
                sourceHandle=handle,
                source=source.model_dump(),
                observedRanges=merge_ranges(ranges),
                coverage=coverage,
                redactionState=redaction,
                **self.source_fields(),
            )
        )
        return self.model_validate({**self.model_dump(), "sources": values}), handle

    def source_binding(self, handle):
        binding = next((s for s in self.sources if s.sourceHandle == handle), None)
        if binding is None:
            raise SubmissionIssue("SOURCE_HANDLE_UNKNOWN", path=("sourceHandle",))
        return binding

    def resolve(self, selection):
        binding = self.source_binding(selection.sourceHandle)
        if binding.source.sourceType == "USER_INPUT":
            if selection.model_fields_set != {"sourceHandle"}:
                raise SubmissionIssue("USER_INPUT_COORDINATES_FORBIDDEN")
            return binding.source
        if selection.startLine is None or not any(
            lower <= selection.startLine <= selection.endLine <= upper
            for lower, upper in binding.observedRanges
        ):
            raise SubmissionIssue("SOURCE_RANGE_NOT_OBSERVED", path=("startLine",))
        return SOURCE.validate_python(
            {
                **binding.source.model_dump(),
                "startLine": selection.startLine,
                "endLine": selection.endLine,
            }
        )

    def evidence_id(self, handle):
        binding = next((e for e in self.evidence if e.evidenceHandle == handle), None)
        if binding is None:
            raise SubmissionIssue("EVIDENCE_HANDLE_UNKNOWN")
        return binding.evidenceId

    def evidence_handle(self, identity):
        return next(e.evidenceHandle for e in self.evidence if e.evidenceId == identity)

    def candidate_id(self, handle):
        binding = next(
            (c for c in self.candidates if c.candidateHandle == handle), None
        )
        if binding is None:
            raise SubmissionIssue(
                "CANDIDATE_HANDLE_UNKNOWN", code="PROVIDER_VALIDATION_INVALID"
            )
        return binding.candidateId
