"""Strict graph protocol; no runtime clients, paths or product status are serialized."""

import json
from typing import Annotated, ClassVar, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from review_harness.domain.model import (
    HarnessError,
    canonical,
    sha256,
    valid_time,
    valid_uuid,
)
from review_harness.domain.tools import PARAMETERS

GRAPH_TOOL_OUTPUT_BYTES = 8192

UUID = Annotated[
    str,
    Field(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
]
Digest = Annotated[str, Field(pattern=r"^sha256:[0-9a-f]{64}$")]


def graph_error(code):
    return HarnessError(code, 503, "调查运行未能安全完成")


class StrictModel(BaseModel):
    json_byte_limit: ClassVar[int] = 256 * 1024
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    @model_validator(mode="after")
    def bounded_json(self):
        if len(canonical(self.model_dump())) > self.json_byte_limit:
            raise ValueError("Protocol object exceeds byte budget")
        return self


class ArtifactRef(StrictModel):
    artifactId: UUID
    sha256: Digest
    byteSize: int = Field(ge=0)
    coverage: Literal["FULL", "PARTIAL", "SAMPLED"]
    redactionState: Literal["NONE", "APPLIED", "NOT_APPLICABLE"]


class SourceRef(StrictModel):
    path: str | None = Field(default=None, max_length=1024)
    contentSha256: Digest | None = None
    diffSha256: Digest | None = None
    startLine: int | None = Field(default=None, ge=1)
    endLine: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def source(self):
        if not self.contentSha256 and not self.diffSha256:
            raise ValueError("Source digest required")
        if self.contentSha256 is not None and self.path is None:
            raise ValueError("Content source path required")
        if self.path is not None:
            from review_harness.infrastructure.paths import portable

            portable(self.path)
        if self.endLine is not None and (
            self.startLine is None or self.endLine < self.startLine
        ):
            raise ValueError("Invalid source range")
        return self


class ToolCall(StrictModel):
    toolCallId: UUID
    providerToolCallId: str | None = Field(default=None, min_length=1, max_length=128)
    name: Literal["read_file", "search_code", "glob_files", "git_diff", "read_artifact"]
    arguments: dict

    @model_validator(mode="after")
    def arguments_schema(self):
        PARAMETERS[self.name].model_validate(self.arguments)
        return self


class GraphMessage(StrictModel):
    messageId: UUID
    role: Literal["assistant", "tool", "user"]
    content: str = Field(max_length=16384)
    toolCalls: list[ToolCall] = Field(default_factory=list, max_length=8)
    toolCallId: UUID | None = None
    artifactRefs: list[ArtifactRef] = Field(default_factory=list, max_length=32)
    sourceRefs: list[SourceRef] = Field(default_factory=list, max_length=32)

    @field_validator("content")
    @classmethod
    def content_bytes(cls, text):
        if len(text.encode("utf-8")) > 16384:
            raise ValueError("Message byte budget exceeded")
        from review_harness.application.redaction import redact, redact_structure

        try:
            structured = json.loads(text)
        except ValueError:
            unsafe = redact(text)[0] != text
        else:
            # Tool envelopes are already structured/redacted. Reinterpreting
            # escaped JSON as source syntax can consume safe text after a value.
            unsafe = redact_structure(structured)[0] != structured
        if unsafe:
            raise ValueError("Unsanitized graph content")
        return text

    @model_validator(mode="after")
    def role_structure(self):
        if self.toolCalls and self.role != "assistant":
            raise ValueError("Calls require assistant")
        if (self.toolCallId is not None) != (self.role == "tool"):
            raise ValueError("Tool result identity required")
        return self


def pending_calls(messages):
    calls, pending, ids = set(), set(), set()
    for message in messages:
        if message.messageId in ids:
            raise ValueError("Duplicate message identity")
        ids.add(message.messageId)
        if pending and message.role != "tool":
            raise ValueError("Unclosed tool batch")
        for call in message.toolCalls:
            if call.toolCallId in calls:
                raise ValueError("Duplicate call identity")
            calls.add(call.toolCallId)
            pending.add(call.toolCallId)
        if message.role == "tool":
            if message.toolCallId not in pending:
                raise ValueError("Missing or duplicate call result")
            pending.remove(message.toolCallId)
    return sorted(pending)


class ModelUsage(StrictModel):
    modelCallId: UUID
    estimatedInputTokens: int = Field(ge=0)
    inputUnits: int = Field(ge=0)
    outputUnits: int = Field(ge=0)


class ReviewGraphState(StrictModel):
    stateVersion: Literal["1.0"] = "1.0"
    runId: UUID
    reviewThreadId: UUID
    snapshotId: UUID
    manifestSha256: Digest
    investigationId: UUID
    passId: Literal["correctness"] = "correctness"
    userGoalDigest: Digest
    scopeDigest: Digest
    toolSetDigest: Digest
    revision: int = Field(default=0, ge=0)
    summaryVersion: int = Field(default=0, ge=0)
    summaryArtifactRef: ArtifactRef | None = None
    messages: list[GraphMessage] = Field(default_factory=list, max_length=128)
    artifactRefs: list[ArtifactRef] = Field(default_factory=list, max_length=64)
    sourceRefs: list[SourceRef] = Field(default_factory=list, max_length=64)
    evidenceRefs: list[UUID] = Field(default_factory=list, max_length=0)
    candidateRefs: list[UUID] = Field(default_factory=list, max_length=0)
    sourceMessageIds: list[UUID] = Field(default_factory=list, max_length=128)
    sourceDigest: Digest | None = None
    pendingToolCalls: list[UUID] = Field(default_factory=list, max_length=8)
    modelCallCount: int = Field(default=0, ge=0, le=16)
    compactionCount: int = Field(default=0, ge=0, le=8)
    graphStepCount: int = Field(default=0, ge=0, le=160)
    lastContextManifestRef: ArtifactRef | None = None
    modelUsages: list[ModelUsage] = Field(default_factory=list, max_length=16)

    @model_validator(mode="after")
    def pairing(self):
        if pending_calls(self.messages) != sorted(self.pendingToolCalls):
            raise ValueError("Pending calls do not match messages")
        if bool(self.summaryVersion) != (self.summaryArtifactRef is not None) or bool(
            self.summaryVersion
        ) != (self.sourceDigest is not None):
            raise ValueError("Summary reference and lineage required")
        for value in (
            self.runId,
            self.reviewThreadId,
            self.snapshotId,
            self.investigationId,
        ):
            if not valid_uuid(value):
                raise ValueError("Invalid UUID")
        return self

    def updated(self, **changes):
        return type(self).model_validate({**self.model_dump(), **changes})

    def identity(self):
        return {
            key: getattr(self, key)
            for key in (
                "runId",
                "reviewThreadId",
                "snapshotId",
                "manifestSha256",
                "investigationId",
                "passId",
                "stateVersion",
            )
        }

    def digest(self):
        return sha256(canonical(self.model_dump()))


class ContextManifest(StrictModel):
    manifestVersion: Literal["1.0"] = "1.0"
    modelCallId: UUID
    runId: UUID
    snapshotId: UUID
    manifestSha256: Digest
    passId: Literal["correctness"]
    investigationId: UUID
    modelId: str = Field(min_length=1, max_length=128)
    callPurpose: Literal["INVESTIGATION", "COMPACTION", "VALIDATION"]
    stateRevision: int = Field(ge=0)
    summaryVersion: int = Field(ge=0)
    sourceRefs: list[SourceRef] = Field(max_length=64)
    evidenceRefs: list[UUID] = Field(default_factory=list, max_length=128)
    candidateRefs: list[UUID] = Field(default_factory=list, max_length=32)
    toolSetDigest: Digest
    requestDigest: Digest
    meterId: Literal["test-only-utf8-units", "V41_REFERENCE_LOCAL_TOKENS"]
    meterVersion: Literal["1", "v4.1-2cba9e42-v1"]
    estimatedInputTokens: int = Field(ge=0)
    inputBudget: int = Field(gt=0)
    reservedOutputTokens: int = Field(gt=0)
    protocolSafetyMargin: int = Field(gt=0)
    createdAt: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")

    @model_validator(mode="after")
    def budget(self):
        if (self.meterId, self.meterVersion) not in {
            ("test-only-utf8-units", "1"),
            ("V41_REFERENCE_LOCAL_TOKENS", "v4.1-2cba9e42-v1"),
        }:
            raise ValueError("Meter identity/version mismatch")
        if self.estimatedInputTokens > self.inputBudget or not valid_time(
            self.createdAt
        ):
            raise ValueError("Invalid context budget or timestamp")
        return self


class CompatibleContextManifest(ContextManifest):
    manifestVersion: Literal["1.1"] = "1.1"
    conservativeInputTokens: int = Field(ge=0)
    uncertaintyReserveTokens: int = Field(ge=1024)
    policyVersion: Literal["deepseek-compatible-budget-v1"]

    @model_validator(mode="after")
    def conservative_budget(self):
        from review_harness.domain.budget import input_budget_for

        reserve = max(1024, (self.estimatedInputTokens + 9) // 10)
        if (
            self.meterId != "V41_REFERENCE_LOCAL_TOKENS"
            or self.inputBudget != input_budget_for(self.policyVersion)
            or self.reservedOutputTokens != 4096
            or self.protocolSafetyMargin != 1024
            or self.uncertaintyReserveTokens != reserve
            or self.conservativeInputTokens != self.estimatedInputTokens + reserve
            or self.conservativeInputTokens > self.inputBudget
        ):
            raise ValueError("Invalid conservative context budget")
        return self


class ExpandedContextManifest(CompatibleContextManifest):
    manifestVersion: Literal["1.2"] = "1.2"
    policyVersion: Literal["deepseek-compatible-budget-v2"]


def read_context_manifest(value):
    """Select a strict persisted version; unknown versions never fall back."""
    version = value.get("manifestVersion") if isinstance(value, dict) else None
    if version == "1.0":
        return ContextManifest.model_validate(value)
    if version == "1.1":
        return CompatibleContextManifest.model_validate(value)
    if version == "1.2":
        return ExpandedContextManifest.model_validate(value)
    if version == "1.3":
        from review_harness.domain.multipass_state import MultipassContextManifest

        return MultipassContextManifest.model_validate(value)
    raise ValueError("Unsupported context manifest version")


class Summary(StrictModel):
    summaryVersion: int = Field(gt=0)
    sourceMessageIds: list[UUID] = Field(min_length=1, max_length=128)
    sourceDigest: Digest
    previousSummaryVersion: int = Field(ge=0)
    summaryText: str = Field(min_length=1, max_length=16384)
    referencedArtifactIds: list[UUID] = Field(max_length=64)
    sourceRefs: list[SourceRef] = Field(max_length=64)
    referencedEvidenceIds: list[UUID] = Field(default_factory=list, max_length=128)
    referencedCandidateIds: list[UUID] = Field(default_factory=list, max_length=32)
    modelId: str = Field(min_length=1, max_length=128)
    estimatedTokens: int = Field(ge=0)
    createdAt: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")

    @model_validator(mode="after")
    def lineage(self):
        if (
            self.summaryVersion != self.previousSummaryVersion + 1
            or len(set(self.sourceMessageIds)) != len(self.sourceMessageIds)
            or len(self.summaryText.encode()) > 16384
            or not valid_time(self.createdAt)
        ):
            raise ValueError("Invalid summary lineage or byte budget")
        return self


class ModelResponse(StrictModel):
    content: str = Field(max_length=16384)
    toolCalls: list[ToolCall] = Field(default_factory=list, max_length=8)
    inputUnits: int = Field(ge=0)
    outputUnits: int = Field(ge=0)
    modelCallId: UUID
    referencedArtifactIds: list[UUID] = Field(default_factory=list, max_length=64)
    sourceRefs: list[SourceRef] = Field(default_factory=list, max_length=64)

    @field_validator("content")
    @classmethod
    def response_bytes(cls, text):
        if len(text.encode("utf-8")) > 16384:
            raise ValueError("Response byte budget exceeded")
        return text
