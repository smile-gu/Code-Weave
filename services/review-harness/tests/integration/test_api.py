import copy

import httpx
import pytest

from review_harness.api.app import create_app
from review_harness.application.service import RunService
from review_harness.config import Settings
from review_harness.infrastructure.persistence.store import RunStore


class HoldingScheduler:
    """Execution boundary stays pending while HTTP contracts are exercised."""

    accepting = True

    def ensure_submitted(self, run_id):
        return True

    def signal_cancel(self, run_id):
        pass


@pytest.fixture
async def api(database, tmp_path):
    store = RunStore(database)
    service = RunService(store, HoldingScheduler())
    app = create_app(
        Settings(
            internal_bearer_token="test-only-credential",
            snapshot_root=tmp_path / "snapshots",
            execution_root=tmp_path / "executions",
        ),
        service=service,
    )
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
        headers={"Authorization": "Bearer test-only-credential"},
    ) as client:
        yield client, store


async def test_http_create_query_retry_conflict_cancel(api, payload):
    client, _ = api
    response = await client.post("/internal/v1/review-runs", json=payload)
    assert response.status_code == 202 and response.json()["status"] == "PENDING"
    assert "message" not in response.text
    assert (
        await client.post("/internal/v1/review-runs", json=payload)
    ).status_code == 200
    different = copy.deepcopy(payload)
    different["snapshot"]["manifestSha256"] = "sha256:" + "b" * 64
    conflict = await client.post("/internal/v1/review-runs", json=different)
    assert (
        conflict.status_code == 409
        and conflict.json()["errorCode"] == "REVIEW_RUN_CONFLICT"
    )
    root = "/internal/v1/review-runs/" + payload["runId"]
    assert (await client.get(root)).json()["lastEventSequence"] == 1
    assert (await client.post(root + "/cancel")).json()["status"] == "CANCELLED"
    assert (await client.post(root + "/cancel")).status_code == 200
    result = await client.get(root + "/result")
    assert (
        result.status_code == 409
        and result.json()["errorCode"] == "REVIEW_RESULT_UNAVAILABLE"
    )


@pytest.mark.parametrize(
    "mutation,code,status",
    [
        ({"runId": "not-uuid"}, "REQUEST_VALIDATION_FAILED", 422),
        ({"repositoryPath": "secret-path"}, "REQUEST_VALIDATION_FAILED", 422),
        ({"protocolVersion": "2.0"}, "PROTOCOL_VERSION_UNSUPPORTED", 400),
        (
            {"snapshot": {"snapshotId": "bad", "manifestSha256": "oops"}},
            "REQUEST_VALIDATION_FAILED",
            422,
        ),
        ({"input": {"message": "  \t"}}, "REQUEST_VALIDATION_FAILED", 422),
        ({"input": {"message": "中" * 12000}}, "REQUEST_VALIDATION_FAILED", 422),
    ],
)
async def test_invalid_input_uses_safe_problem_details(
    api, payload, mutation, code, status
):
    client, _ = api
    payload.update(mutation)
    response = await client.post("/internal/v1/review-runs", json=payload)
    assert response.status_code == status and response.json()["errorCode"] == code
    assert response.headers["content-type"].startswith("application/problem+json")
    assert "secret-path" not in response.text and "oops" not in response.text


async def test_body_size_is_bounded_before_json_parsing(api):
    client, _ = api
    response = await client.post("/internal/v1/review-runs", content=b"x" * 65537)
    assert response.status_code == 413
    assert response.json()["errorCode"] == "REVIEW_REQUEST_TOO_LARGE"


async def test_result_http_preserves_original_bytes(api, payload):
    client, store = api
    await client.post("/internal/v1/review-runs", json=payload)
    root = "/internal/v1/review-runs/" + payload["runId"]
    assert (await client.get(root + "/result")).json()[
        "errorCode"
    ] == "REVIEW_RESULT_NOT_READY"
    await store.start_run(payload["runId"])
    await store.complete_success(payload["runId"])
    original, digest = await store.get_result(payload["runId"])
    response = await client.get(root + "/result")
    assert response.status_code == 200 and response.content == original
    assert response.headers["etag"] == '"' + digest + '"'
    assert response.headers["x-content-sha256"] == digest
    assert response.headers["content-type"] == "application/json; charset=utf-8"
