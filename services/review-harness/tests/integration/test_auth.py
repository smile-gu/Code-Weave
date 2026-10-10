import httpx
import pytest

from review_harness.api.app import create_app
from review_harness.config import Settings


@pytest.mark.parametrize(
    "path",
    [
        "health",
        "review-runs",
        "review-runs/x",
        "review-runs/x/events",
        "review-runs/x/result",
        "review-runs/x/cancel",
    ],
)
@pytest.mark.parametrize("token", [None, "Bearer wrong"])
async def test_every_internal_route_requires_authentication(tmp_path, path, token):
    app = create_app(
        Settings(
            internal_bearer_token="test-only-credential",
            snapshot_root=tmp_path / "snapshots",
            execution_root=tmp_path / "executions",
        )
    )
    headers = {} if token is None else {"Authorization": token}
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        method = "POST" if path in ("review-runs", "review-runs/x/cancel") else "GET"
        response = await client.request(method, "/internal/v1/" + path, headers=headers)
    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["errorCode"] == "REVIEW_HARNESS_UNAUTHENTICATED"
    assert "wrong" not in response.text
