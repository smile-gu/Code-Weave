from fastapi import FastAPI

from tests.integration.network import network_client


async def test_loopback_acceptance_does_not_use_operator_proxy(monkeypatch):
    for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"):
        monkeypatch.setenv(name, "http://127.0.0.1:1")
        monkeypatch.setenv(name.lower(), "http://127.0.0.1:1")
    monkeypatch.setenv("NO_PROXY", "")
    monkeypatch.delenv("no_proxy", raising=False)
    app = FastAPI()

    @app.get("/probe")
    async def probe():
        return {"owner": "local"}

    async with network_client(app) as client:
        response = await client.get("/probe")
        assert response.status_code == 200 and response.json() == {"owner": "local"}
