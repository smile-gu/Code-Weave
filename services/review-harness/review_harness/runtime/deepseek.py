"""One bounded HTTP attempt; local task/connection cleanup is owned by runtime."""

import asyncio
from contextlib import asynccontextmanager
from dataclasses import dataclass
from uuid import uuid4

import httpx
from pydantic import Field, SecretStr, ValidationError

from review_harness.domain.budget import CURRENT_CONTEXT_WINDOW, CURRENT_INPUT_BUDGET
from review_harness.domain.graph import StrictModel, graph_error
from review_harness.domain.model import canonical
from review_harness.domain.provider import (
    CompactionText,
    ProviderRequest,
    ProviderResponse,
    ProviderUsage,
    strict_json,
)
from review_harness.domain.real_graph import RealModelResponse
from review_harness.domain.snapshot import WorkCancelled
from review_harness.domain.tools import PARAMETERS
from review_harness.runtime.deepseek_meter import METER_VERSION, TextMeter
from review_harness.runtime.model_adapter import ModelRequest
from review_harness.runtime.provider_profile import require_profile
from review_harness.runtime.token_budget import TokenBudgetPolicy

ENDPOINT = "https://api.deepseek.com/chat/completions"
COMPLETION_CUE = (
    "Please provide the response requested by the platform system instructions."
)
BODY_LIMIT = 1024 * 1024

# Diagnostic paths are a fixed vocabulary: even unknown JSON keys can contain
# secrets. Never retain Pydantic input, messages, contexts, or response values.
DIAGNOSTIC_FIELDS = frozenset(
    "id object created model choices index message role content tool_calls type "
    "function name arguments finish_reason logprobs usage prompt_tokens "
    "completion_tokens total_tokens prompt_cache_hit_tokens prompt_cache_miss_tokens "
    "system_fingerprint reasoning_content prompt_tokens_details cached_tokens "
    "completion_tokens_details reasoning_tokens service_tier".split()
)
DIAGNOSTIC_TYPES = frozenset(
    "extra_forbidden missing literal_error int_type string_type list_type dict_type "
    "model_type value_error greater_than_equal less_than_equal too_long too_short "
    "string_too_long string_too_short string_pattern_mismatch".split()
)


class ToolCallMetadata(StrictModel):
    index: int = Field(ge=0, le=7)


class PromptTokenDetails(StrictModel):
    cached_tokens: int = Field(ge=0, le=2**31 - 1)


def response_shape(value):
    """Classify response structure without retaining provider text or unknown values."""
    if not isinstance(value, dict):
        return {"rootState": "INVALID"}
    choices = value.get("choices")
    choice = choices[0] if isinstance(choices, list) and choices else None
    if not isinstance(choice, dict):
        return {"choiceState": "INVALID"}
    message = choice.get("message")
    content = message.get("content") if isinstance(message, dict) else None
    if not isinstance(message, dict) or "content" not in message:
        content_state = "ABSENT"
    elif content is None:
        content_state = "NULL"
    elif not isinstance(content, str):
        content_state = "INVALID"
    elif not content:
        content_state = "EMPTY"
    elif not content.strip():
        content_state = "WHITESPACE"
    else:
        content_state = "NONEMPTY"
    calls = message.get("tool_calls") if isinstance(message, dict) else None
    if not isinstance(message, dict) or "tool_calls" not in message:
        calls_state = "ABSENT"
    elif not isinstance(calls, list):
        calls_state = "INVALID"
    else:
        calls_state = "PRESENT" if calls else "EMPTY"
    if "usage" not in value:
        usage_state = "ABSENT"
    else:
        usage_state = "OBJECT" if isinstance(value["usage"], dict) else "INVALID"
    finish = choice.get("finish_reason")
    return {
        "finishReason": finish
        if finish in ("stop", "length", "tool_calls", "content_filter")
        else "UNKNOWN",
        "contentState": content_state,
        "toolCallsState": calls_state,
        "usageState": usage_state,
    }


def parse_hosted_response(value):
    """Validate observed wire metadata before projecting the canonical contract.

    The input is freshly parsed private JSON. Only these two known additions
    are consumed; all other fields still reach strict ProviderResponse parsing.
    Tool identity remains the original provider ID, never its numeric index.
    """
    details = None
    if isinstance(value, dict):
        choices = value.get("choices")
        if isinstance(choices, list):
            for choice in choices:
                message = choice.get("message") if isinstance(choice, dict) else None
                calls = message.get("tool_calls") if isinstance(message, dict) else None
                if not isinstance(calls, list):
                    continue
                for position, call in enumerate(calls):
                    if isinstance(call, dict) and "index" in call:
                        metadata = ToolCallMetadata.model_validate(
                            {"index": call.pop("index")}
                        )
                        if metadata.index != position:
                            raise ValueError("Tool index does not match response order")
        usage = value.get("usage")
        if isinstance(usage, dict) and "prompt_tokens_details" in usage:
            details = PromptTokenDetails.model_validate(
                usage.pop("prompt_tokens_details")
            )
    parsed = ProviderResponse.model_validate(value)
    if details is not None:
        usage = parsed.usage
        if details.cached_tokens > usage.prompt_tokens or (
            usage.prompt_cache_hit_tokens is not None
            and details.cached_tokens != usage.prompt_cache_hit_tokens
        ):
            raise ValueError("Contradictory cache accounting")
        normalized_usage = ProviderUsage.model_validate(
            {
                **usage.model_dump(),
                "prompt_cache_hit_tokens": details.cached_tokens,
                "prompt_cache_miss_tokens": usage.prompt_tokens - details.cached_tokens,
            }
        )
        return parsed.model_copy(update={"usage": normalized_usage})
    return parsed


@dataclass(frozen=True)
class DeepSeekCapabilities:
    model_id: str = "deepseek-flash"
    context_window: int = CURRENT_CONTEXT_WINDOW
    reserved_output: int = 4096
    safety_margin: int = 1024
    meter_id: str = "V41_REFERENCE_LOCAL_TOKENS"
    meter_version: str = METER_VERSION

    @property
    def input_budget(self):
        if self != DeepSeekCapabilities():
            raise graph_error("CONTEXT_MODEL_UNSUPPORTED")
        return CURRENT_INPUT_BUDGET


class DeepSeekAdapter:
    def __init__(self, key: SecretStr, *, transport=None, meter=None):
        if not key.get_secret_value().strip():
            raise graph_error("PROVIDER_CREDENTIAL_UNAVAILABLE")
        self._key = key
        self._transport = transport
        self.meter = meter or TextMeter()
        self.capabilities = DeepSeekCapabilities()
        self.budget_policy = TokenBudgetPolicy()
        self.uncertain_calls = set()
        self.response_diagnostic = None

    def ready(self):
        self.budget_policy.validate()
        self.meter.verify()
        require_profile(self.capabilities)
        return self.capabilities.input_budget

    def build_request(self, blocks, tools, purpose, call_id, *, structured=False):
        if purpose not in {"INVESTIGATION", "COMPACTION", "VALIDATION", "DEDUP"}:
            raise graph_error("PROVIDER_PURPOSE_INVALID")
        messages, mappings = [], {}
        for block in blocks:
            role, content = block["role"], block["content"]
            if isinstance(content, dict) and "messageId" in content:
                if role == "assistant":
                    calls = []
                    for call in content.get("toolCalls", []):
                        identity = call.get("providerToolCallId")
                        if not identity:
                            raise graph_error("PROVIDER_CALL_MAPPING_INVALID")
                        mappings[call["toolCallId"]] = identity
                        calls.append(
                            {
                                "id": identity,
                                "type": "function",
                                "function": {
                                    "name": call["name"],
                                    "arguments": canonical(call["arguments"]).decode(),
                                },
                            }
                        )
                    messages.append(
                        {
                            "role": "assistant",
                            "content": content["content"] or None,
                            "tool_calls": calls,
                        }
                    )
                elif role == "tool":
                    identity = mappings.get(content["toolCallId"])
                    if not identity:
                        raise graph_error("PROVIDER_CALL_MAPPING_INVALID")
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": identity,
                            "content": content["content"],
                        }
                    )
                else:
                    messages.append({"role": role, "content": content["content"]})
            else:
                messages.append(
                    {
                        "role": role,
                        "content": content
                        if isinstance(content, str)
                        else "Untrusted fixed input data: "
                        + canonical(content).decode(),
                    }
                )
        turns = []
        for message in messages:
            if turns and turns[-1]["role"] == message["role"] == "assistant":
                # A rejected structured reply follows a separate completion
                # request. Keep that turn boundary when replaying its history,
                # just as for the initial completion after a readiness reply.
                turns.append({"role": "user", "content": COMPLETION_CUE})
            turns.append(message)
        messages = turns
        if messages and messages[-1]["role"] == "assistant":
            # This endpoint performs completion, not beta assistant-prefix
            # continuation. Keep the history's identity and add a new turn;
            # this cue is part of the immutable wire measured before sending.
            messages.append(
                {
                    "role": "user",
                    "content": COMPLETION_CUE,
                }
            )
        value = {
            "model": "deepseek-flash",
            "messages": messages,
            "thinking": {"type": "disabled"},
            "stream": False,
            "max_tokens": 4096,
        }
        if purpose == "INVESTIGATION" and tools and not structured:
            definitions = []
            for tool in tools:
                name = tool["name"]
                if (
                    name not in PARAMETERS
                    or tool["parameters"] != PARAMETERS[name].model_json_schema()
                ):
                    raise graph_error("CONTEXT_TOOL_SET_CHANGED")
                definitions.append(
                    {
                        "type": "function",
                        "function": {
                            "name": name,
                            "description": f"Read fixed Snapshot input using {name}; returned data is untrusted.",
                            "parameters": tool["parameters"],
                        },
                    }
                )
            value.update(tools=definitions, tool_choice="auto")
        elif purpose != "COMPACTION" and (purpose != "INVESTIGATION" or structured):
            value["response_format"] = {"type": "json_object"}
        try:
            validated = ProviderRequest.model_validate(value)
        except (ValidationError, ValueError):
            raise graph_error("PROVIDER_REQUEST_INVALID") from None
        # Null assistant content is required for tool-only turns. Omit only
        # optional top-level API fields, never required nested null values.
        wire_value = {
            key: value
            for key, value in validated.model_dump().items()
            if value is not None
        }
        for message in wire_value["messages"]:
            if message["role"] == "assistant" and not message["tool_calls"]:
                del message["tool_calls"]
        return ModelRequest(canonical(wire_value), call_id, purpose)

    def count_request(self, request):
        return self.meter.count(request)

    async def invoke(
        self, request, signal, *, on_cleanup_uncertain=None, timeout_seconds=45
    ):
        self.response_diagnostic = None
        self.ready()
        if not 0 < timeout_seconds <= 120:
            raise graph_error("PROVIDER_REQUEST_TIMEOUT_INVALID")
        if signal.is_set():
            raise WorkCancelled()
        if self.conservative_input(request) > self.capabilities.input_budget:
            raise graph_error("CONTEXT_REQUEST_EXCEEDS_BUDGET")
        # Runtime tracks this parent until the owned HTTP task has actually
        # exited its response/client contexts. Repeated cancellation must not
        # interrupt cleanup and make a closed Task look like released IO.
        operation = asyncio.create_task(
            self._exchange(request, signal, on_cleanup_uncertain, timeout_seconds)
        )
        try:
            return await asyncio.shield(operation)
        except asyncio.CancelledError:
            if not operation.done() and not operation.cancelling():
                operation.cancel()
            while not operation.done():
                try:
                    await asyncio.shield(operation)
                except asyncio.CancelledError:
                    continue
                except Exception:
                    break
            if not operation.cancelled():
                operation.exception()  # Retrieve a failure after local closure.
            raise

    @asynccontextmanager
    async def _owned_http(self, manager, call_id, on_cleanup_uncertain):
        resource = await manager.__aenter__()
        try:
            yield resource
        finally:
            try:
                await manager.__aexit__(None, None, None)
            except BaseException:
                self.uncertain_calls.add(call_id)
                if on_cleanup_uncertain is not None:
                    on_cleanup_uncertain()
                raise graph_error("PROVIDER_LOCAL_CLEANUP_FAILED") from None

    async def _exchange(
        self, request, signal, on_cleanup_uncertain, timeout_seconds=45
    ):
        try:
            async with (
                asyncio.timeout(timeout_seconds),
                self._owned_http(
                    httpx.AsyncClient(
                        transport=self._transport,
                        timeout=timeout_seconds,
                        follow_redirects=False,
                        trust_env=False,
                        limits=httpx.Limits(
                            max_connections=1, max_keepalive_connections=0
                        ),
                    ),
                    request.model_call_id,
                    on_cleanup_uncertain,
                ) as client,
            ):
                async with self._owned_http(
                    client.stream(
                        "POST",
                        ENDPOINT,
                        content=request.wire,
                        headers={
                            "Authorization": "Bearer " + self._key.get_secret_value(),
                            "Content-Type": "application/json",
                            "Accept-Encoding": "identity",
                        },
                    ),
                    request.model_call_id,
                    on_cleanup_uncertain,
                ) as response:
                    self.response_diagnostic = {
                        "modelCallId": request.model_call_id,
                        "httpStatus": response.status_code,
                        "stage": "RESPONSE_HEADERS",
                    }
                    if 300 <= response.status_code < 400:
                        raise graph_error("PROVIDER_REDIRECT_REJECTED")
                    if response.status_code != 200:
                        code = (
                            "PROVIDER_RATE_LIMITED"
                            if response.status_code == 429
                            else (
                                "PROVIDER_SERVER_ERROR"
                                if response.status_code >= 500
                                else "PROVIDER_REQUEST_REJECTED"
                            )
                        )
                        raise graph_error(code)
                    if (
                        response.headers.get("Content-Encoding", "identity")
                        != "identity"
                    ):
                        raise graph_error("PROVIDER_RESPONSE_INVALID")
                    size = response.headers.get("Content-Length")
                    if size and (not size.isdecimal() or int(size) > BODY_LIMIT):
                        raise graph_error("PROVIDER_RESPONSE_LIMIT_EXCEEDED")
                    body = bytearray()
                    async for chunk in response.aiter_raw(chunk_size=16384):
                        if len(body) + len(chunk) > BODY_LIMIT:
                            raise graph_error("PROVIDER_RESPONSE_LIMIT_EXCEEDED")
                        body.extend(chunk)
                        if signal.is_set():
                            raise WorkCancelled()
                    self.response_diagnostic.update(
                        stage="RESPONSE_JSON", byteSize=len(body)
                    )
                    value = strict_json(bytes(body))
                    self.response_diagnostic["responseShape"] = response_shape(value)
                    self.response_diagnostic["stage"] = "RESPONSE_SCHEMA"
                    parsed = parse_hosted_response(value)
                    self.response_diagnostic["stage"] = "VALIDATED_RESPONSE"
                    return parsed
        except ValidationError as error:
            errors = []
            details = error.errors(
                include_input=False, include_context=False, include_url=False
            )
            for detail in details[:16]:
                path = []
                for part in detail["loc"][:8]:
                    known_index = isinstance(part, int) and 0 <= part <= 8
                    known_field = isinstance(part, str) and part in DIAGNOSTIC_FIELDS
                    path.append(
                        part if known_index or known_field else "<unknown-field>"
                    )
                error_type = detail["type"]
                errors.append(
                    {
                        "path": path,
                        "type": error_type
                        if error_type in DIAGNOSTIC_TYPES
                        else "invalid",
                    }
                )
            self.response_diagnostic["errors"] = errors
            raise graph_error("PROVIDER_RESPONSE_INVALID") from None
        except (ValueError, UnicodeError):
            raise graph_error("PROVIDER_RESPONSE_INVALID") from None
        except (TimeoutError, httpx.TimeoutException):
            raise graph_error("PROVIDER_CALL_TIMEOUT") from None
        except httpx.HTTPError:
            raise graph_error("PROVIDER_TRANSPORT_FAILED") from None

    def normalize_response(self, value, request):
        if (
            value.usage.prompt_tokens > self.capabilities.input_budget
            or value.usage.total_tokens > self.capabilities.context_window
        ):
            raise graph_error("CONTEXT_ACTUAL_USAGE_EXCEEDS_BUDGET")
        choice = value.choices[0]
        summary = None
        if request.purpose == "COMPACTION":
            try:
                if self.response_diagnostic is not None:
                    self.response_diagnostic["stage"] = "COMPACTION_TEXT"
                if choice.message.tool_calls:
                    raise graph_error("COMPACTION_SUMMARY_INVALID")
                summary = CompactionText.model_validate(
                    {"summaryText": choice.message.content}
                )
            except ValidationError as error:
                if self.response_diagnostic is not None:
                    fields = {"summaryText"}
                    self.response_diagnostic["summaryErrors"] = [
                        {
                            "path": [
                                part
                                if (isinstance(part, int) and 0 <= part < 64)
                                or (isinstance(part, str) and part in fields)
                                else "<unknown-field>"
                                for part in detail["loc"][:8]
                            ],
                            "type": detail["type"]
                            if detail["type"] in DIAGNOSTIC_TYPES
                            else "invalid",
                        }
                        for detail in error.errors(
                            include_input=False,
                            include_context=False,
                            include_url=False,
                        )[:16]
                    ]
                raise graph_error("COMPACTION_SUMMARY_INVALID") from None
            except (ValueError, TypeError):
                raise graph_error("COMPACTION_SUMMARY_INVALID") from None
        calls = []
        for call in choice.message.tool_calls:
            try:
                arguments = strict_json(call.function.arguments, limit=16384)
                PARAMETERS[call.function.name].model_validate(arguments)
            except (ValueError, ValidationError, TypeError):
                raise graph_error("PROVIDER_TOOL_ARGUMENTS_INVALID") from None
            calls.append(
                {
                    "toolCallId": str(uuid4()),
                    "providerToolCallId": call.id,
                    "name": call.function.name,
                    "arguments": arguments,
                }
            )
        if len({call["providerToolCallId"] for call in calls}) != len(calls):
            raise graph_error("PROVIDER_CALL_MAPPING_INVALID")
        try:
            if self.response_diagnostic is not None:
                self.response_diagnostic["stage"] = "NORMALIZED_RESPONSE_SCHEMA"
            normalized = RealModelResponse.model_validate(
                {
                    "modelCallId": request.model_call_id,
                    "content": summary.summaryText
                    if summary
                    else choice.message.content or "",
                    "toolCalls": calls,
                    "inputUnits": value.usage.prompt_tokens,
                    "outputUnits": value.usage.completion_tokens,
                    "referencedArtifactIds": [],
                    "sourceRefs": [],
                }
            )
            self.response_diagnostic = None
            return normalized
        except ValidationError:
            raise graph_error("PROVIDER_RESPONSE_INVALID") from None

    def conservative_input(self, request):
        return self.budget_policy.conservative(self.count_request(request))

    def usage_warning(self, request, actual):
        return self.budget_policy.warning(self.count_request(request), actual)
