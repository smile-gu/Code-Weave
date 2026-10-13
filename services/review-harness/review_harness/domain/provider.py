"""Frozen text-only DeepSeek protocol. Provider IDs never become platform IDs."""

import json
from typing import Annotated, Literal

from pydantic import ConfigDict, Field, field_validator, model_validator

from review_harness.domain.graph import UUID, StrictModel
from review_harness.domain.model import canonical
from review_harness.domain.tools import PARAMETERS

ProviderId = Annotated[str, Field(min_length=1, max_length=128)]
ToolName = Literal[
    "read_file", "search_code", "glob_files", "git_diff", "read_artifact"
]


def strict_json(raw: str | bytes, *, limit=1024 * 1024):
    """Reject duplicate keys/non-finite numbers before business validation."""
    if len(raw if isinstance(raw, bytes) else raw.encode("utf-8")) > limit:
        raise ValueError("JSON byte limit exceeded")

    def pairs(items):
        result = {}
        for name, value in items:
            if name in result:
                raise ValueError("Duplicate JSON key")
            result[name] = value
        return result

    def invalid(_):
        raise ValueError("Non-finite JSON number")

    return json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid)


class FunctionCall(StrictModel):
    name: ToolName
    arguments: str = Field(max_length=16384)


class ProviderToolCall(StrictModel):
    id: ProviderId
    type: Literal["function"]
    function: FunctionCall


class SystemMessage(StrictModel):
    role: Literal["system"]
    content: str = Field(min_length=1)


class UserMessage(StrictModel):
    role: Literal["user"]
    content: str = Field(min_length=1)


class AssistantMessage(StrictModel):
    role: Literal["assistant"]
    content: str | None
    tool_calls: list[ProviderToolCall] = Field(default_factory=list, max_length=8)

    @model_validator(mode="after")
    def meaningful(self):
        if not self.tool_calls and not (self.content and self.content.strip()):
            raise ValueError("Empty assistant response")
        return self


class ToolMessage(StrictModel):
    role: Literal["tool"]
    content: str
    tool_call_id: ProviderId


Message = Annotated[
    SystemMessage | UserMessage | AssistantMessage | ToolMessage,
    Field(discriminator="role"),
]


class FunctionDefinition(StrictModel):
    model_config = ConfigDict(
        json_schema_extra={
            "oneOf": [
                {
                    "properties": {
                        "name": {"const": name},
                        "parameters": {"const": model.model_json_schema()},
                    }
                }
                for name, model in PARAMETERS.items()
            ]
        }
    )
    name: ToolName
    description: str = Field(min_length=1, max_length=1024)
    # Only the platform supplies schemas. The adapter validates against the
    # canonical registered schema before serializing, never provider input.
    parameters: dict = Field(
        json_schema_extra={
            "enum": [model.model_json_schema() for model in PARAMETERS.values()]
        }
    )

    @model_validator(mode="after")
    def registered_schema(self):
        if self.parameters != PARAMETERS[self.name].model_json_schema():
            raise ValueError("Tool schema is not the registered platform schema")
        return self


class ToolDefinition(StrictModel):
    type: Literal["function"]
    function: FunctionDefinition


class Thinking(StrictModel):
    type: Literal["disabled"]


class ResponseFormat(StrictModel):
    type: Literal["json_object"]


class ProviderRequest(StrictModel):
    json_byte_limit = 512 * 1024
    model: Literal["deepseek-flash"]
    messages: list[Message] = Field(min_length=1, max_length=160)
    thinking: Thinking
    stream: Literal[False]
    max_tokens: int = Field(ge=1, le=4096)
    tools: list[ToolDefinition] | None = Field(default=None, min_length=1, max_length=5)
    tool_choice: Literal["auto"] | None = None
    response_format: ResponseFormat | None = None

    @model_validator(mode="after")
    def protocol(self):
        if bool(self.tools) != (self.tool_choice == "auto"):
            raise ValueError("Tools require explicit auto")
        if self.response_format and self.tools:
            raise ValueError("Structured submission cannot dispatch tools")
        if (
            self.response_format
            and "json"
            not in " ".join(
                m.content or "" for m in self.messages if m.role in {"system", "user"}
            ).lower()
        ):
            raise ValueError("JSON mode requires explicit prompt instruction")
        pending, seen = set(), set()
        for message in self.messages:
            if pending and message.role != "tool":
                raise ValueError("Unclosed tool batch")
            if isinstance(message, AssistantMessage):
                for call in message.tool_calls:
                    if call.id in seen:
                        raise ValueError("Repeated provider call ID")
                    seen.add(call.id)
                    pending.add(call.id)
            elif isinstance(message, ToolMessage):
                if message.tool_call_id not in pending:
                    raise ValueError("Unknown or repeated tool result")
                pending.remove(message.tool_call_id)
        if pending:
            raise ValueError("Unclosed tool batch")
        if len(canonical(self.model_dump(exclude_none=True))) > 512 * 1024:
            raise ValueError("Request byte limit exceeded")
        return self


class ProviderCallMapping(StrictModel):
    modelCallId: UUID
    toolCallId: UUID
    providerToolCallId: ProviderId


class ProviderUsage(StrictModel):
    # Observed accounting is not a request permission. A valid response above
    # the local operational window must reach the drift gate and be persisted.
    prompt_tokens: int = Field(ge=0, le=2**31 - 1)
    completion_tokens: int = Field(ge=0, le=4096)
    total_tokens: int = Field(ge=0, le=2**31 - 1)
    prompt_cache_hit_tokens: int | None = Field(default=None, ge=0)
    prompt_cache_miss_tokens: int | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def totals(self):
        if self.total_tokens != self.prompt_tokens + self.completion_tokens:
            raise ValueError("Usage total mismatch")
        if (self.prompt_cache_hit_tokens is None) != (
            self.prompt_cache_miss_tokens is None
        ):
            raise ValueError("Incomplete cache usage")
        if self.prompt_cache_hit_tokens is not None and (
            self.prompt_cache_hit_tokens + self.prompt_cache_miss_tokens
            != self.prompt_tokens
        ):
            raise ValueError("Cache usage mismatch")
        return self


class ProviderChoice(StrictModel):
    index: Literal[0]
    message: AssistantMessage
    finish_reason: Literal["stop", "tool_calls"]
    logprobs: Literal[None] = None

    @model_validator(mode="after")
    def completion(self):
        if bool(self.message.tool_calls) != (self.finish_reason == "tool_calls"):
            raise ValueError("Finish reason does not match calls")
        return self


class ProviderResponse(StrictModel):
    json_byte_limit = 1024 * 1024
    id: str = Field(min_length=1, max_length=256)
    object: Literal["chat.completion"]
    created: int = Field(ge=0)
    model: Literal["deepseek-flash"]
    choices: list[ProviderChoice] = Field(min_length=1, max_length=1)
    usage: ProviderUsage
    system_fingerprint: str | None = Field(default=None, max_length=256)


class CompactionText(StrictModel):
    """Internal text bound, never a model JSON output protocol."""

    summaryText: str = Field(min_length=1, max_length=8192)

    @field_validator("summaryText")
    @classmethod
    def text_budget(cls, text):
        if not text.strip() or len(text.encode("utf-8")) > 16384:
            raise ValueError("Invalid compaction text budget")
        return text
