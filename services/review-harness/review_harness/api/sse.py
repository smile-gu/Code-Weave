import asyncio
import re

from starlette.responses import StreamingResponse

from review_harness.domain.model import TERMINAL, HarnessError, canonical


def parse_cursor(value: str | None, maximum: int) -> int:
    if value is None or value == "":
        return 0
    if (
        len(value) > 19
        or re.fullmatch(r"[0-9]+", value) is None
        or int(value) > 9223372036854775807
    ):
        raise HarnessError("REVIEW_EVENT_CURSOR_INVALID", 400, "事件游标格式无效")
    cursor = int(value)
    if cursor > maximum:
        raise HarnessError("REVIEW_EVENT_CURSOR_AHEAD", 409, "事件游标超过已提交序号")
    return cursor


class BoundedStreamingResponse(StreamingResponse):
    """A stalled socket cannot keep a stream producer alive without a bound."""

    async def stream_response(self, send):
        async def bounded_send(message):
            await asyncio.wait_for(send(message), 5)

        try:
            await super().stream_response(bounded_send)
        except TimeoutError:
            # A disconnected/stalled subscriber has no authority over the Run.
            return
        finally:
            close = getattr(self.body_iterator, "aclose", None)
            if close is not None:
                await close()


async def stream_events(request, store, hub, run_id, cursor, settings):
    # Subscribe before querying so a commit between query and wait cannot be lost.
    with hub.subscribe(run_id) as signal:
        while not await request.is_disconnected():
            signal.clear()
            page = await store.read_events(run_id, cursor, settings.sse_page_size)
            for item in page:
                cursor = item["sequence"]
                yield (
                    b"id: "
                    + str(cursor).encode()
                    + b"\nevent: review-run-event\ndata: "
                    + canonical(item)
                    + b"\n\n"
                )
                if item["eventType"] in {
                    "REVIEW_RUN_SUCCEEDED",
                    "REVIEW_RUN_FAILED",
                    "REVIEW_RUN_CANCELLED",
                }:
                    return
            if page:
                continue
            run = await store.get_run(run_id)
            if run.status in TERMINAL and cursor == run.last_event_sequence:
                return
            try:
                await asyncio.wait_for(signal.wait(), settings.sse_heartbeat_seconds)
            except TimeoutError:
                yield b": heartbeat\n\n"
