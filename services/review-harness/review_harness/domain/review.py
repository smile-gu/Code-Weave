"""Single correctness pass contracts; evidence is a fact, validation a judgment."""

from typing import Annotated, Literal

from pydantic import Field, field_validator, model_validator

from review_harness.domain.budget import CURRENT_CONTEXT_WINDOW
from review_harness.domain.graph import UUID, Digest, StrictModel
from review_harness.domain.model import canonical, valid_time
from review_harness.infrastructure.paths import portable

Text = Annotated[str, Field(min_length=1, max_length=4096, pattern=r"\S")]
Coverage = Literal["FULL", "PARTIAL", "SAMPLED"]
Timestamp = Annotated[
    str, Field(pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")
]


class CodeReference(StrictModel):
    sourceType: Literal["CODE"]
    path: str = Field(min_length=1, max_length=1024)
    side: Literal["base", "final", "diff"]
    sha256: Digest
    startLine: int = Field(ge=1)
    endLine: int = Field(ge=1)

    @model_validator(mode="after")
    def coordinates(self):
        portable(self.path)
        if self.endLine < self.startLine:
            raise ValueError("Invalid source range")
        return self


class ArtifactReference(StrictModel):
    sourceType: Literal["ARTIFACT"]
    artifactId: UUID
    sha256: Digest
    startLine: int = Field(ge=1)
    endLine: int = Field(ge=1)
    coverage: Coverage

    @model_validator(mode="after")
    def coordinates(self):
        if self.endLine < self.startLine:
            raise ValueError("Invalid artifact range")
        return self


class UserInputReference(StrictModel):
    sourceType: Literal["USER_INPUT"]
    sha256: Digest


EvidenceSource = Annotated[
    CodeReference | ArtifactReference | UserInputReference,
    Field(discriminator="sourceType"),
]


class EvidenceCandidate(StrictModel):
    evidenceType: Literal["CODE", "DOMAIN_CONTEXT"]
    sourceRefs: list[EvidenceSource] = Field(min_length=1, max_length=16)
    coverage: Coverage
    summary: Text

    @model_validator(mode="after")
    def sources(self):
        types = {r.sourceType for r in self.sourceRefs}
        if self.evidenceType == "CODE" and not types <= {"CODE", "ARTIFACT"}:
            raise ValueError("Code evidence requires code material")
        if self.evidenceType == "DOMAIN_CONTEXT" and types != {"USER_INPUT"}:
            raise ValueError("Only original user context is supported")
        return self


class EvidenceRecord(EvidenceCandidate):
    evidenceId: UUID
    runId: UUID
    snapshotId: UUID
    producerType: Literal["MODEL_SUBMISSION"]
    producerId: UUID
    capturedAt: Timestamp

    @field_validator("capturedAt")
    @classmethod
    def timestamp(cls, value):
        if not valid_time(value):
            raise ValueError("Invalid timestamp")
        return value


class ReasoningSummary(StrictModel):
    rule: Text
    trigger: Text
    executionPath: Text
    assumptions: list[Text] = Field(max_length=16)
    counterexamples: list[Text] = Field(min_length=1, max_length=16)
    conclusion: Text


class CandidateSemantics(StrictModel):
    passId: Literal["correctness"]
    category: str = Field(min_length=1, max_length=200, pattern=r"\S")
    title: str = Field(min_length=1, max_length=200, pattern=r"\S")
    claim: Text
    severity: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    confidence: float = Field(ge=0, le=1, allow_inf_nan=False)
    reasoningSummary: ReasoningSummary
    suggestion: Text
    claimKind: Literal["STATIC_CORRECTNESS", "ENVIRONMENT_DEPENDENT", "QUANTITATIVE"]


class CandidateFields(CandidateSemantics):
    evidenceIds: list[UUID] = Field(min_length=1, max_length=16)
    codeReferences: list[CodeReference] = Field(max_length=16)


class ReviewCandidate(CandidateFields):
    candidateId: UUID


class ValidationSummary(StrictModel):
    basis: Text
    counterexampleChecked: Text
    counterexampleHolds: bool
    assumptionsResolved: bool


class FactCheck(StrictModel):
    candidateText: Text
    evidenceId: UUID
    sourceIndex: int = Field(ge=0, le=15)
    sourceQuote: Text
    userQuote: Text | None = None
    outcome: Literal["SUPPORTED", "CORRECTED", "CORE_CONTRADICTED", "UNRESOLVED"]
    explanation: Text


class CandidateValidation(StrictModel):
    candidateId: UUID
    disposition: Literal["VERIFIED", "RISK", "SUGGESTION", "REJECTED"]
    validationSummary: ValidationSummary
    correctedCandidate: CandidateFields | None = None
    factChecks: list[FactCheck] = Field(default_factory=list, max_length=16)


class Finding(ReviewCandidate):
    findingId: UUID
    validationModelCallId: UUID
    disposition: Literal["VERIFIED", "RISK", "SUGGESTION"]
    validationSummary: ValidationSummary

    @model_validator(mode="after")
    def verified_gate(self):
        if self.validationSummary.counterexampleHolds:
            raise ValueError("Established counterexample rejects finding")
        if self.disposition == "VERIFIED" and (
            self.claimKind != "STATIC_CORRECTNESS"
            or not self.codeReferences
            or not self.validationSummary.assumptionsResolved
        ):
            raise ValueError("Verified finding requires resolved static path")
        return self


class PassUsage(StrictModel):
    modelCalls: int = Field(ge=1, le=16)
    inputTokens: int = Field(ge=0, le=16 * CURRENT_CONTEXT_WINDOW)
    outputTokens: int = Field(ge=0, le=16 * 4096)


class PassResult(StrictModel):
    passId: Literal["correctness"]
    status: Literal["COMPLETED", "INCOMPLETE"]
    scopeKind: Literal["FINAL_FILES", "FIXED_DIFF"] = "FINAL_FILES"
    checkedScope: list[Annotated[str, Field(min_length=1, max_length=1024)]] = Field(
        min_length=1, max_length=128
    )
    coverage: Coverage
    limitations: list[Text] = Field(min_length=1, max_length=32)
    incompleteReasons: list[Text] = Field(max_length=32)
    unvalidatedCandidates: int = Field(ge=0, le=32)
    findingIds: list[UUID] = Field(max_length=32)
    usage: PassUsage

    @model_validator(mode="after")
    def completion(self):
        if self.status == "COMPLETED" and (
            self.incompleteReasons
            or self.unvalidatedCandidates
            or self.coverage != "FULL"
        ):
            raise ValueError("Completed pass requires explicit complete finite scope")
        if self.status == "INCOMPLETE" and not self.incompleteReasons:
            raise ValueError("Incomplete pass requires reasons")
        for path in self.checkedScope:
            portable(path)
        return self


class ReportDraft(StrictModel):
    markdown: str = Field(max_length=65536)
    byteSize: int = Field(ge=0, le=65536)

    @model_validator(mode="after")
    def bytes_match(self):
        if len(self.markdown.encode("utf-8")) != self.byteSize:
            raise ValueError("Markdown byte size mismatch")
        return self


class ResultDiagnostics(StrictModel):
    executionMode: Literal["REAL"]
    contextManifestArtifactIds: list[UUID] = Field(min_length=1, max_length=16)
    modelCallIds: list[UUID] = Field(min_length=1, max_length=16)
    meterVersion: Literal["v4.1-2cba9e42-v1"]
    limitations: list[Text] = Field(min_length=1, max_length=32)


class ReviewResult(StrictModel):
    json_byte_limit = 1024 * 1024
    resultVersion: Literal["2.0"]
    resultId: UUID
    runId: UUID
    snapshotId: UUID
    manifestSha256: Digest
    generatedAt: Timestamp
    passResults: list[PassResult] = Field(min_length=1, max_length=1)
    evidence: list[EvidenceRecord] = Field(max_length=128)
    findings: list[Finding] = Field(max_length=32)
    reportDraft: ReportDraft
    diagnostics: ResultDiagnostics

    @model_validator(mode="after")
    def cross_references(self):
        if not valid_time(self.generatedAt):
            raise ValueError("Invalid timestamp")
        for values in (
            [e.evidenceId for e in self.evidence],
            [f.findingId for f in self.findings],
            [f.candidateId for f in self.findings],
            self.passResults[0].findingIds,
            self.diagnostics.modelCallIds,
            self.diagnostics.contextManifestArtifactIds,
        ):
            if len(set(values)) != len(values):
                raise ValueError("Repeated identity")
        evidence = {e.evidenceId: e for e in self.evidence}
        if any(
            e.runId != self.runId or e.snapshotId != self.snapshotId
            for e in self.evidence
        ):
            raise ValueError("Evidence identity mismatch")
        if set(self.passResults[0].findingIds) != {f.findingId for f in self.findings}:
            raise ValueError("Pass findings mismatch")
        for finding in self.findings:
            if finding.codeReferences and not any(
                ref.path in self.passResults[0].checkedScope
                for ref in finding.codeReferences
            ):
                raise ValueError("Finding has no anchor in checked scope")
            if finding.validationModelCallId not in self.diagnostics.modelCallIds:
                raise ValueError("Finding validation call missing")
            if not set(finding.evidenceIds) <= evidence.keys():
                raise ValueError("Unknown finding evidence")
            refs = [
                ref for eid in finding.evidenceIds for ref in evidence[eid].sourceRefs
            ]
            if any(ref not in refs for ref in finding.codeReferences):
                raise ValueError("Finding source not in evidence")
            if finding.disposition == "VERIFIED" and not any(
                evidence[eid].evidenceType == "CODE" for eid in finding.evidenceIds
            ):
                raise ValueError("Verified finding requires code evidence")
        if len(canonical(self.model_dump())) > 1024 * 1024:
            raise ValueError("Result byte limit exceeded")
        return self


class InvestigationSubmission(StrictModel):
    decision: Literal["CONTINUE", "COMPLETE"]
    evidenceCandidates: list[EvidenceCandidate] = Field(max_length=16)
    candidates: list[CandidateFields] = Field(max_length=32)
    checkedScope: list[Annotated[str, Field(min_length=1, max_length=1024)]] = Field(
        max_length=128
    )
    scopeKind: Literal["FINAL_FILES", "FIXED_DIFF"]
    coverage: Coverage
    limitations: list[Text] = Field(min_length=1, max_length=16)
    incompleteReasons: list[Text] = Field(max_length=16)


class ValidationBatch(StrictModel):
    validations: list[CandidateValidation] = Field(min_length=1, max_length=32)


class ReviewPackage(StrictModel):
    json_byte_limit = 1024 * 1024
    passResult: PassResult
    evidence: list[EvidenceRecord] = Field(max_length=128)
    findings: list[Finding] = Field(max_length=32)
    diagnostics: ResultDiagnostics
