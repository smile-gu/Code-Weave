import asyncio

import httpx
import pytest

from review_harness.api.app import create_app
from review_harness.application.hub import EventHub
from review_harness.application.service import RunService
from review_harness.config import Settings
from review_harness.infrastructure.persistence.store import RunStore
from tests.integration.network import network_client
from tests.integration.test_api import HoldingScheduler


@pytest.fixture
async def sse_app(database, tmp_path):
    hub = EventHub()
    store = RunStore(database, notify=hub.notify)
    settings = Settings(
        internal_bearer_token="test-only-credential",
        snapshot_root=tmp_path,
        sse_page_size=1,
        sse_heartbeat_seconds=0.05,
    )
    app = create_app(settings, service=RunService(store, HoldingScheduler()), hub=hub)
    return app, store


@pytest.mark.parametrize(
    "cursor,expected", [(None, [1, 2, 3]), ("1", [2, 3]), ("3", [])]
)
async def test_network_sse_replays_in_sequence_and_closes_at_terminal(
    sse_app, payload, cursor, expected
):
    app, store = sse_app
    await store.create_run(payload)
    await store.start_run(payload["runId"])
    await store.complete_success(payload["runId"])
    async with network_client(app) as client:
        headers = {} if cursor is None else {"Last-Event-ID": cursor}
        async with client.stream(
            "GET",
            "/internal/v1/review-runs/" + payload["runId"] + "/events",
            headers=headers,
        ) as response:
            lines = [line async for line in response.aiter_lines()]
    assert [int(line[4:]) for line in lines if line.startswith("id: ")] == expected
    assert lines.count("event: review-run-event") == len(expected)


@pytest.mark.parametrize(
    "cursor,code",
    [
        ("-1", "REVIEW_EVENT_CURSOR_INVALID"),
        ("x", "REVIEW_EVENT_CURSOR_INVALID"),
        ("9223372036854775808", "REVIEW_EVENT_CURSOR_INVALID"),
        ("1" * 100, "REVIEW_EVENT_CURSOR_INVALID"),
        ("2", "REVIEW_EVENT_CURSOR_AHEAD"),
    ],
)
async def test_cursor_errors_are_problem_details_before_streaming(
    sse_app, payload, cursor, code
):
    app, store = sse_app
    await store.create_run(payload)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
        headers={"Authorization": "Bearer test-only-credential"},
    ) as client:
        response = await client.get(
            "/internal/v1/review-runs/" + payload["runId"] + "/events",
            headers={"Last-Event-ID": cursor},
        )
    assert response.json()["errorCode"] == code


async def test_disconnect_does_not_cancel_run_and_lost_hub_notify_recovers(
    sse_app, payload
):
    app, store = sse_app
    await store.create_run(payload)
    root = "/internal/v1/review-runs/" + payload["runId"] + "/events"
    async with network_client(app) as client:
        async with client.stream("GET", root) as response:
            async for line in response.aiter_lines():
                if line.startswith("data:"):
                    break
        assert (await store.get_run(payload["runId"])).status == "PENDING"
        async with client.stream(
            "GET", root, headers={"Last-Event-ID": "1"}
        ) as response:

            def lose_notify(_):
                raise OSError("lost notify")

            store.notify = lose_notify
            await store.cancel_pending(payload["runId"])
            text = await response.aread()
        assert b"id: 2" in text and b"REVIEW_RUN_CANCELLED" in text


async def test_uncertain_terminal_closes_and_new_connection_replays_release(
    sse_app, payload
):
    from review_harness.domain.model import Failure

    app, store = sse_app
    await store.create_run(payload)
    await store.start_run(payload["runId"])
    await store.fail_cleanup_uncertain(
        payload["runId"], Failure.make("REVIEW_RESOURCE_CLEANUP_FAILED")
    )
    async with network_client(app) as client:
        root = "/internal/v1/review-runs/" + payload["runId"] + "/events"
        first = await client.get(root)
        assert "REVIEW_RUN_FAILED" in first.text
        await store.confirm_resources_released(payload["runId"])
        second = await client.get(root, headers={"Last-Event-ID": "3"})
        assert "REVIEW_RUN_RESOURCES_RELEASED" in second.text


async def test_nonreading_network_subscriber_does_not_hold_writer_transaction(
    sse_app, payload
):
    app, store = sse_app
    await store.create_run(payload)
    root = "/internal/v1/review-runs/" + payload["runId"] + "/events"
    async with network_client(app) as client:
        async with client.stream("GET", root) as response:
            assert response.status_code == 200
            # Intentionally do not consume response frames while durable facts advance.
            await asyncio.wait_for(store.start_run(payload["runId"]), 2)
            await asyncio.wait_for(store.complete_success(payload["runId"]), 2)
            await response.aread()
        replay = await client.get(root)
        assert [
            line for line in replay.text.splitlines() if line.startswith("id:")
        ] == ["id: 1", "id: 2", "id: 3"]
