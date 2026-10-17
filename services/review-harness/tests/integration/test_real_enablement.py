import asyncio
import copy
import json
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.api.app import create_app
from review_harness.domain.model import canonical
from tests.integration.test_deepseek_adapter import response
from tests.integration.test_lifecycle import client_for, settings_for
from tests.snapshot_fixture import publish_snapshot


async def test_real_mode_without_key_is_not_ready_and_reserves_nothing(
    database, tmp_path, payload
):
    settings = settings_for(database, tmp_path).model_copy(
        update={"execution_mode": "REAL"}
    )
    app = create_app(settings)
    async with app.router.lifespan_context(app), client_for(app) as client:
        response = await client.get("/internal/v1/health")
        assert response.status_code == 503
        capability = (await client.get("/internal/v1/review-capabilities")).json()
        assert capability["configuredContract"] == {
            "executionMode": "REAL",
            "resultVersion": "2.0",
        }
        assert "PROVIDER_CREDENTIAL_UNAVAILABLE" in capability["failureCodes"]
        request = {**payload, "executionContract": capability["configuredContract"]}
        assert (
            await client.post("/internal/v1/review-runs", json=request)
        ).status_code == 503
        assert (
            await client.get(f"/internal/v1/review-runs/{payload['runId']}")
        ).status_code == 404


@pytest.mark.parametrize("missing", ["asset", "checkpoint"])
async def test_real_missing_dependencies_never_dispatch_or_reserve(
    database, tmp_path, payload, missing
):
    updates = {"execution_mode": "REAL", "deepseek_api_key": SecretStr("offline-only")}
    if missing == "asset":
        updates.update(
            deepseek_asset_root=tmp_path / "missing",
            checkpoint_database_url=f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
        )
    settings = settings_for(database, tmp_path).model_copy(update=updates)
    sent = []
    app = create_app(
        settings,
        provider_transport=httpx.MockTransport(lambda request: sent.append(request)),
    )
    async with app.router.lifespan_context(app), client_for(app) as client:
        assert (await client.get("/internal/v1/health")).status_code == 503
        payload["executionContract"] = settings.execution_contract
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 503
        assert (
            await client.get(f"/internal/v1/review-runs/{payload['runId']}")
        ).status_code == 404
        assert not sent


@pytest.mark.parametrize("step_budget", [64, 2])
async def test_real_app_delivers_authenticated_atomic_report_from_fixed_input(
    database, tmp_path, payload, step_budget
):
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "execution_mode": "REAL",
            "deepseek_api_key": SecretStr("offline-only"),
            "checkpoint_database_url": f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
            "shutdown_grace_seconds": 2,
            "graph_max_steps": step_budget,
        }
    )
    payload["executionContract"] = settings.execution_contract
    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(
        settings.snapshot_root, payload, {"src/Demo.java": b"class Demo {}\n"}
    )
    sent = []

    def server(request):
        data = json.loads(request.content)
        sent.append(data)
        value = response()
        value["usage"]["prompt_tokens_details"] = {"cached_tokens": 0}
        if len(sent) == 1:
            value["choices"][0].update(
                finish_reason="tool_calls",
                message={
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": "app-provider-read",
                            "type": "function",
                            "function": {
                                "name": "read_file",
                                "arguments": '{"path":"src/Demo.java"}',
                            },
                        }
                    ],
                },
            )
        elif "response_format" in data:
            assert data["messages"][-1]["role"] != "assistant"
            assert all(message.get("tool_calls") != [] for message in data["messages"])
            anchors = next(
                json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
                for m in data["messages"]
                if "Untrusted fixed input data: " in (m.get("content") or "")
            )
            recorded = bool(anchors.get("evidenceRecords"))
            value["choices"][0]["message"]["content"] = json.dumps(
                {
                    "decision": "COMPLETE" if recorded else "CONTINUE",
                    "evidenceCandidates": []
                    if recorded
                    else [
                        {
                            "evidenceType": "CODE",
                            "coverage": "FULL",
                            "summary": "Fixed class declaration",
                            "localKey": "n1",
                            "selections": [
                                {
                                    "sourceHandle": next(
                                        s["sourceHandle"]
                                        for s in anchors["sourceCatalog"]
                                        if s["sourceKind"] == "CODE"
                                    ),
                                    "startLine": 1,
                                    "endLine": 1,
                                }
                            ],
                        }
                    ],
                    "candidates": [],
                    "checkedScope": ["src/Demo.java"],
                    "scopeKind": "FINAL_FILES",
                    "coverage": "FULL",
                    "limitations": ["Only this fixed class declaration was inspected."],
                    "incompleteReasons": [],
                }
            )
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

    app = create_app(settings, provider_transport=httpx.MockTransport(server))
    async with app.router.lifespan_context(app), client_for(app) as client:
        assert (await client.get("/internal/v1/health")).status_code == 200
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 30)
        facts = (
            await client.get(f"/internal/v1/review-runs/{payload['runId']}")
        ).json()
        assert (
            facts["status"] == "SUCCEEDED" and facts["resourceState"] == "RELEASED"
        ), facts
        result = await client.get(f"/internal/v1/review-runs/{payload['runId']}/result")
        assert result.status_code == 200
        assert result.json()["resultVersion"] == "2.0"
        assert result.json()["passResults"][0]["status"] == (
            "INCOMPLETE" if step_budget == 2 else "COMPLETED"
        )
        assert not result.json()["findings"]
        if step_budget == 2:
            assert len(sent) == 1
            assert (
                "CONTEXT_GRAPH_STEP_LIMIT_EXCEEDED"
                in result.json()["passResults"][0]["incompleteReasons"]
            )
            return
        evidence_id = result.json()["evidence"][0]["evidenceId"]
        material = await client.get(
            f"/internal/v1/review-runs/{payload['runId']}/evidence/{evidence_id}/materials"
        )
        assert material.status_code == 200
        assert material.json()["materials"][0]["text"] == "class Demo {}\n"
        assert result.headers["etag"] == '"' + facts["result"]["resultSha256"] + '"'
        assert any(
            m.get("tool_call_id") == "app-provider-read"
            for call in sent
            for m in call["messages"]
        )
        assert all("tools" not in call for call in sent if "response_format" in call)
        assert not (settings.execution_root / payload["runId"]).exists()


async def test_unconfirmed_http_close_retains_run_and_other_session_can_complete(
    database, tmp_path, payload
):
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "execution_mode": "REAL",
            "deepseek_api_key": SecretStr("offline-only"),
            "checkpoint_database_url": f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
            "shutdown_grace_seconds": 2,
        }
    )
    payload["executionContract"] = settings.execution_contract
    received = {}

    def server(request):
        data = json.loads(request.content)
        anchors = next(
            json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
            for m in data["messages"]
            if "Untrusted fixed input data: " in (m.get("content") or "")
        )
        identity = anchors["runId"]
        received[identity] = received.get(identity, 0) + 1
        value = response()
        if received[identity] == 1:
            value["choices"][0].update(
                finish_reason="tool_calls",
                message={
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "provider-read",
                            "type": "function",
                            "function": {
                                "name": "read_file",
                                "arguments": '{"path":"src/Demo.java"}',
                            },
                        }
                    ],
                },
            )
        elif "response_format" in data:
            value["choices"][0]["message"]["content"] = json.dumps(
                {
                    "decision": "COMPLETE",
                    "evidenceCandidates": [],
                    "candidates": [],
                    "checkedScope": ["src/Demo.java"],
                    "scopeKind": "FINAL_FILES",
                    "coverage": "FULL",
                    "limitations": ["Finite fixed scope"],
                    "incompleteReasons": [],
                }
            )
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

    class FailingCloseTransport(httpx.MockTransport):
        attempts = 0

        async def aclose(self):
            self.attempts += 1
            if self.attempts == 1:
                raise httpx.CloseError("Controlled missing release acknowledgement")

    transport = FailingCloseTransport(server)
    app = create_app(settings, provider_transport=transport)
    async with app.router.lifespan_context(app), client_for(app) as client:
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 30)
        run = (await client.get(f"/internal/v1/review-runs/{payload['runId']}")).json()
        assert (run["status"], run["resourceState"]) == ("FAILED", "UNCERTAIN")
        assert (settings.execution_root / payload["runId"]).exists()
        same = copy.deepcopy(payload)
        same["runId"], same["reviewThreadId"] = str(uuid4()), str(uuid4())
        assert (
            await client.post("/internal/v1/review-runs", json=same)
        ).status_code == 503
        other = copy.deepcopy(payload)
        other["runId"], other["reviewThreadId"], other["codingSessionId"] = (
            str(uuid4()) for _ in range(3)
        )
        other["snapshot"]["snapshotId"] = str(uuid4())
        publish_snapshot(settings.snapshot_root, other)
        assert (
            await client.post("/internal/v1/review-runs", json=other)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 30)
        assert (await client.get(f"/internal/v1/review-runs/{other['runId']}")).json()[
            "status"
        ] == "SUCCEEDED"
    rebuilt = create_app(settings, provider_transport=transport)
    async with rebuilt.router.lifespan_context(rebuilt), client_for(rebuilt) as client:
        run = (await client.get(f"/internal/v1/review-runs/{payload['runId']}")).json()
        assert run["resourceState"] == "UNCERTAIN"
        assert (settings.execution_root / payload["runId"]).exists()
