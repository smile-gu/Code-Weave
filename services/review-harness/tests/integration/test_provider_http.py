"""Actual loopback sockets; the test transport alone maps the fixed TLS endpoint."""

import asyncio
import json
from uuid import uuid4

import httpx
from fastapi import FastAPI, Request
from pydantic import SecretStr
from starlette.responses import Response

from review_harness.domain.model import canonical
from review_harness.runtime.deepseek import DeepSeekAdapter
from tests.integration.network import network_client


class LoopbackTransport(httpx.AsyncBaseTransport):
    def __init__(self, base):
        self.base = base
        self.http = httpx.AsyncHTTPTransport(retries=0)

    async def handle_async_request(self, request):
        assert str(request.url) == "https://api.deepseek.com/chat/completions"
        mapped = httpx.Request(
            request.method,
            self.base.join("/chat/completions"),
            headers={k: v for k, v in request.headers.items() if k.lower() != "host"},
            content=request.content,
        )
        return await self.http.handle_async_request(mapped)

    async def aclose(self):
        await self.http.aclose()


async def test_real_http_provider_ids_are_continued_without_retry_or_changed_wire():
    server = FastAPI()
    received = []

    @server.post("/chat/completions")
    async def completion(request: Request):
        raw = await request.body()
        received.append(raw)
        message = {"role": "assistant", "content": "Done."}
        finish = "stop"
        if len(received) == 1:
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "index": 0,
                        "id": "socket-provider-generated-call",
                        "type": "function",
                        "function": {
                            "name": "read_file",
                            "arguments": '{"path":"Demo.java"}',
                        },
                    }
                ],
            }
            finish = "tool_calls"
        return Response(
            canonical(
                {
                    "id": "local-provider",
                    "object": "chat.completion",
                    "created": 0,
                    "model": "deepseek-flash",
                    "choices": [
                        {"index": 0, "message": message, "finish_reason": finish}
                    ],
                    "usage": {
                        "prompt_tokens_details": {"cached_tokens": 0},
                        "prompt_tokens": 1,
                        "completion_tokens": 1,
                        "total_tokens": 2,
                    },
                }
            ),
            media_type="application/json",
        )

    async with network_client(server) as client:
        adapter = DeepSeekAdapter(
            SecretStr("offline-only"), transport=LoopbackTransport(client.base_url)
        )
        blocks = [{"role": "user", "content": "Review fixed source."}]
        first = adapter.build_request(blocks, [], "INVESTIGATION", str(uuid4()))
        value = await adapter.invoke(first, asyncio.Event())
        result = adapter.normalize_response(value, first)
        call = result.toolCalls[0]
        blocks.extend(
            [
                {
                    "role": "assistant",
                    "content": {
                        "messageId": str(uuid4()),
                        "content": "",
                        "toolCalls": [call.model_dump()],
                    },
                },
                {
                    "role": "tool",
                    "content": {
                        "messageId": str(uuid4()),
                        "toolCallId": call.toolCallId,
                        "content": "class Demo {}",
                    },
                },
            ]
        )
        second = adapter.build_request(blocks, [], "INVESTIGATION", str(uuid4()))
        value = await adapter.invoke(second, asyncio.Event())
        assert adapter.normalize_response(value, second).content == "Done."
        assert received == [first.wire, second.wire]
        assert (
            json.loads(received[1])["messages"][-1]["tool_call_id"]
            == "socket-provider-generated-call"
        )
