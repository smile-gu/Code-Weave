"""Test-only model protocol. UTF-8 units explicitly are NOT provider tokens."""

import json
from dataclasses import dataclass
from uuid import uuid4

from pydantic import ValidationError

from review_harness.domain.graph import ModelResponse, graph_error
from review_harness.domain.model import canonical, sha256


@dataclass(frozen=True)
class ModelCapabilities:
    model_id: str = "test-only-deterministic"
    context_window: int = 65536
    reserved_output: int = 1024
    safety_margin: int = 128
    meter_id: str = "test-only-utf8-units"
    meter_version: str = "1"

    @property
    def input_budget(self):
        if (
            any(
                type(value) is not int
                for value in (
                    self.context_window,
                    self.reserved_output,
                    self.safety_margin,
                )
            )
            or self.model_id != "test-only-deterministic"
            or self.meter_id != "test-only-utf8-units"
            or self.meter_version != "1"
            or self.context_window <= 0
            or self.reserved_output <= 0
            or self.safety_margin <= 0
        ):
            raise graph_error("CONTEXT_MODEL_UNSUPPORTED")
        return self.context_window - self.reserved_output - self.safety_margin


@dataclass(frozen=True)
class ModelRequest:
    wire: bytes
    model_call_id: str | None = None
    purpose: str | None = None

    @property
    def digest(self):
        return sha256(self.wire)


class TestWindowExceeded(Exception):
    """Fake protocol test seam; does not imply any real Provider support."""


class FakeAdapter:
    def __init__(
        self, *, context_window=65536, responses=(), reserved_output=1024, hook=None
    ):
        self.capabilities = ModelCapabilities(
            context_window=context_window, reserved_output=reserved_output
        )
        self.responses = list(responses)
        self.calls = []
        self.hook = hook

    def build_request(self, blocks, tools, purpose, call_id, *, structured=False):
        wire = canonical(
            {
                "protocol": "test-only-v1",
                "model": self.capabilities.model_id,
                "purpose": purpose,
                "modelCallId": call_id,
                "blocks": blocks,
                "tools": tools,
                "outputReservation": self.capabilities.reserved_output,
            }
        )
        if len(wire) > 512 * 1024:
            raise graph_error("CONTEXT_REQUEST_LIMIT_EXCEEDED")
        return ModelRequest(wire)

    def count_request(self, request):
        if self.capabilities.meter_id != "test-only-utf8-units":
            raise graph_error("CONTEXT_METER_UNSUPPORTED")
        return len(request.wire)

    async def invoke(self, request, signal):
        self.calls.append(request)
        if self.hook:
            await self.hook(request, signal)
        value = (
            self.responses.pop(0)
            if self.responses
            else {"content": "Deterministic probe complete."}
        )
        if isinstance(value, TestWindowExceeded):
            raise value
        request_data = json.loads(request.wire)
        return {
            **value,
            "modelCallId": request_data["modelCallId"],
            "inputUnits": len(request.wire),
            "outputUnits": len(canonical(value)),
        }

    def normalize_response(self, value, request):
        try:
            response = ModelResponse.model_validate(value)
        except (ValidationError, ValueError, UnicodeError):
            raise graph_error("CONTEXT_RESPONSE_INVALID") from None
        if len({call.toolCallId for call in response.toolCalls}) != len(
            response.toolCalls
        ):
            raise graph_error("CONTEXT_RESPONSE_INVALID")
        if (
            response.modelCallId != json.loads(request.wire)["modelCallId"]
            or response.inputUnits != self.count_request(request)
            or response.outputUnits > self.capabilities.reserved_output
        ):
            raise graph_error("CONTEXT_RESPONSE_INVALID")
        return ModelResponse.model_validate(
            {
                **response.model_dump(),
                "toolCalls": [
                    {**call.model_dump(), "toolCallId": str(uuid4())}
                    for call in response.toolCalls
                ],
            }
        )
