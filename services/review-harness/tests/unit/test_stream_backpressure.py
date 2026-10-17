import asyncio

from review_harness.api.sse import BoundedStreamingResponse
from review_harness.application.hub import EventHub


async def test_stalled_asgi_send_releases_hub_subscription_after_write_timeout():
    hub = EventHub()
    observed = None
    blocked = asyncio.Event()

    async def frames():
        nonlocal observed
        with hub.subscribe("run") as signal:
            observed = signal
            yield b"data: frame\n\n"

    async def send(message):
        if message["type"] == "http.response.body":
            await blocked.wait()

    response = BoundedStreamingResponse(frames())
    await asyncio.wait_for(response.stream_response(send), 6)
    hub.notify("run")
    assert observed is not None and not observed.is_set()
