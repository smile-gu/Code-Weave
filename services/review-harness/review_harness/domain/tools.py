import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class Parameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    @field_validator("*", mode="after")
    @classmethod
    def string_budget(cls, value):
        if isinstance(value, str) and len(value.encode("utf-8", "strict")) > 1024:
            raise ValueError("String exceeds limit")
        return value


class ReadFile(Parameters):
    path: str = Field(min_length=1, max_length=1024)
    startLine: int = Field(default=1, ge=1)
    maxLines: int = Field(default=200, ge=1, le=1000)


class SearchCode(Parameters):
    query: str = Field(min_length=1, max_length=1024)
    glob: str | None = Field(default=None, max_length=1024)
    maxResults: int = Field(default=100, ge=1, le=500)


class GlobFiles(Parameters):
    pattern: str = Field(min_length=1, max_length=1024)
    maxResults: int = Field(default=100, ge=1, le=500)


class GitDiff(Parameters):
    cursor: str | None = Field(default=None, max_length=1024)


class ReadArtifact(Parameters):
    artifactId: str = Field(
        pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    cursor: str | None = Field(default=None, max_length=1024)

    @field_validator("artifactId")
    @classmethod
    def uuid(cls, value):
        if not re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", value
        ):
            raise ValueError("Invalid artifact identity")
        return value


class ToolError(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    code: str
    message: str = Field(max_length=256)
    retryable: bool


class ToolEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    toolProtocolVersion: Literal["1.0"] = "1.0"
    toolCallId: str = Field(
        pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    toolName: str = Field(max_length=64)
    status: Literal["OK", "ERROR", "CANCELLED"]
    data: dict | None
    sourceRefs: list[dict]
    coverage: Literal["FULL", "PARTIAL", "SAMPLED"]
    truncated: bool
    artifactRef: dict | None
    error: ToolError | None


PARAMETERS = {
    "read_file": ReadFile,
    "search_code": SearchCode,
    "glob_files": GlobFiles,
    "git_diff": GitDiff,
    "read_artifact": ReadArtifact,
}
