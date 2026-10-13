"""Scope correction at the production Provider/API boundary."""

import asyncio
import json

import httpx
import pytest
from pydantic import SecretStr

from review_harness.api.app import create_app
from review_harness.domain.model import canonical
from tests.integration.test_deepseek_adapter import response
from tests.integration.test_lifecycle import client_for, settings_for


@pytest.mark.parametrize("persistent", [False, True])
async def test_local_scope_feedback_repairs_paths_or_exhausts_budget(
    database, tmp_path, payload, persistent
):
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "execution_mode": "REAL",
            "deepseek_api_key": SecretStr("offline-only"),
            "checkpoint_database_url": f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
        }
    )
    payload["executionContract"] = settings.execution_contract
    attempts = 0

    def server(request):
        nonlocal attempts
        data = json.loads(request.content)
        reply = response()
        choice = reply["choices"][0]
        if "response_format" not in data:
            if not any(m["role"] == "tool" for m in data["messages"]):
                choice.update(
                    finish_reason="tool_calls",
                    message={
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": "read",
                                "type": "function",
                                "function": {
                                    "name": "read_file",
                                    "arguments": '{"path":"src/Demo.java"}',
                                },
                            }
                        ],
                    },
                )
            else:
                choice["message"]["content"] = "Ready to submit."
        else:
            attempts += 1
            if attempts > 1:
                feedback = next(
                    json.loads(m["content"])
                    for m in reversed(data["messages"])
                    if m["role"] == "user"
                    and "platformSubmissionFeedback" in (m.get("content") or "")
                )
                assert (
                    feedback["platformSubmissionFeedback"]["reason"] == "SCOPE_INVALID"
                )
                assert feedback["allowedCheckedScope"] == ["src/Demo.java"]
                assert "codeSelections" in feedback["instruction"]
                assert "verbatim" in feedback["instruction"]
                rejected = next(
                    json.loads(m["content"])
                    for m in reversed(data["messages"])
                    if m["role"] == "assistant"
                    and '"checkedScope"' in (m.get("content") or "")
                )
                assert rejected["checkedScope"] == ["src/Demo.java line 1"]
                assert rejected["limitations"] == ["password = [REDACTED]"]
                assert "scope-test-secret" not in json.dumps(data)
            assert (
                "checkedScope must copy complete file paths verbatim from scope.targetPaths"
                in data["messages"][0]["content"]
            )
            anchors = next(
                json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
                for m in data["messages"]
                if "Untrusted fixed input data: " in (m.get("content") or "")
            )
            source = next(
                s for s in anchors["sourceCatalog"] if s["sourceKind"] == "CODE"
            )
            choice["message"]["content"] = json.dumps(
                {
                    "decision": "COMPLETE",
                    "candidates": [],
                    "evidenceCandidates": [
                        {
                            "localKey": "n1",
                            "evidenceType": "CODE",
                            "coverage": "FULL",
                            "summary": "Inspected fixed declaration.",
                            "selections": [
                                {
                                    "sourceHandle": source["sourceHandle"],
                                    "startLine": 1,
                                    "endLine": 1,
                                }
                            ],
                        }
                    ],
                    "checkedScope": ["src/Demo.java line 1"]
                    if persistent or attempts == 1
                    else ["src/Demo.java"],
                    "scopeKind": "FINAL_FILES",
                    "coverage": "FULL",
                    "incompleteReasons": [],
                    "limitations": ["password = scope-test-secret"],
                }
            )
        return httpx.Response(200, stream=httpx.ByteStream(canonical(reply)))

    app = create_app(settings, provider_transport=httpx.MockTransport(server))
    async with app.router.lifespan_context(app), client_for(app) as client:
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 60)
        url = "/internal/v1/review-runs/" + payload["runId"]
        run = (await client.get(url)).json()
        assert run["resourceState"] == "RELEASED"
        if persistent:
            assert run["status"] == "FAILED", run
            assert run["failure"]["code"] == "FINDING_SCOPE_VIOLATION"
            assert attempts == 3
            assert (await client.get(url + "/result")).status_code != 200
        else:
            assert run["status"] == "SUCCEEDED", run
            assert attempts == 2
            result = (await client.get(url + "/result")).json()
            assert result["passResults"][0]["checkedScope"] == ["src/Demo.java"]
            assert len(result["evidence"]) == 1
