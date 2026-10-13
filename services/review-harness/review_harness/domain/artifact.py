import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from review_harness.domain.model import HarnessError, valid_time, valid_uuid


def artifact_error(code, status=503):
    return HarnessError(code, status, "材料操作未能安全完成")


def camel(name):
    first, *tail = name.split("_")
    return first + "".join(part.title() for part in tail)


class Artifact(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        strict=True,
        frozen=True,
        alias_generator=lambda name: camel(name),
        populate_by_name=True,
    )
    artifact_id: str = Field(
        pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    run_id: str = Field(
        pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    snapshot_id: str = Field(
        pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    kind: Literal[
        "SOURCE_EXCERPT", "TOOL_OUTPUT", "CONTEXT_MANIFEST", "COMPACTION_SUMMARY"
    ]
    producer_type: Literal["TOOL", "SYSTEM"]
    producer_id: str = Field(
        min_length=1, max_length=160, pattern=r"^[a-z_]{1,64}:[0-9a-f-]{36}$"
    )
    media_type: Literal["text/plain", "application/json", "application/octet-stream"]
    encoding: Literal["UTF-8", "BINARY"]
    byte_size: int = Field(ge=0)
    sha256: str = Field(pattern=r"^sha256:[0-9a-f]{64}$")
    coverage: Literal["FULL", "PARTIAL", "SAMPLED"]
    redaction_state: Literal["NONE", "APPLIED", "NOT_APPLICABLE"]
    created_at: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")
    integrity_state: Literal["READY", "CORRUPTED"]

    @model_validator(mode="after")
    def identity(self):
        if not all(
            valid_uuid(value)
            for value in (self.artifact_id, self.run_id, self.snapshot_id)
        ):
            raise ValueError("Invalid artifact identity")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", self.sha256) or not valid_time(
            self.created_at
        ):
            raise ValueError("Invalid artifact integrity metadata")
        if not re.fullmatch(r"[a-z_]{1,64}:[0-9a-f-]{36}", self.producer_id):
            raise ValueError("Invalid trusted producer")
        if not valid_uuid(self.producer_id.split(":", 1)[1]):
            raise ValueError("Invalid producer call identity")
        if (self.encoding == "BINARY") != (
            self.media_type == "application/octet-stream"
        ):
            raise ValueError("Invalid artifact encoding")
        return self

    def wire(self):
        return self.model_dump(by_alias=True)


class ContentCoordinates(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    startLine: int = Field(ge=1)
    endLine: int = Field(ge=1)
    startsMidLine: bool
    endsMidLine: bool


class ContentPage(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    artifactId: str = Field(
        pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    sha256: str = Field(pattern=r"^sha256:[0-9a-f]{64}$")
    text: str
    coverage: Literal["FULL", "PARTIAL", "SAMPLED"]
    redactionState: Literal["NONE", "APPLIED", "NOT_APPLICABLE"]
    nextCursor: str | None = Field(max_length=1024)
    hasMore: bool
    coordinates: ContentCoordinates | None = None
