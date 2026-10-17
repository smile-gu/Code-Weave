"""Schema feedback is useful, bounded, and safe at the actual provider seam."""

import asyncio
import json
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.domain.model import canonical
from tests.integration.test_deepseek_adapter import response
from tests.integration.test_lifecycle import client_for, settings_for
from tests.legacy_runtime_fixture import legacy_protocol_app as create_app


def candidate(reference):
    return {
        "passId": "correctness",
        "category": "SYNTHETIC",
        "title": "Synthetic claim",
        "claim": "Inspect the declaration",
        "severity": "LOW",
        "confidence": 0.5,
        "evidenceIds": [str(uuid4())],
        "codeReferences": [reference],
        "claimKind": "STATIC_CORRECTNESS",
        "suggestion": "Check the original material",
        "reasoningSummary": {
            "rule": "Declaration is required",
            "trigger": "Inspect source",
            "executionPath": "Read source",
            "assumptions": [],
            "counterexamples": ["Declaration is present"],
            "conclusion": "Needs verification",
        },
    }


@pytest.mark.parametrize("mistake", ["missing", "empty", "uuid", "extra", "many"])
async def test_legacy_schema_feedback_explains_constraints_without_rejected_values(
    database, tmp_path, payload, mistake
):
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "execution_mode": "REAL",
            "deepseek_api_key": SecretStr("offline-only"),
            "checkpoint_database_url": f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
        }
    )
    payload["executionContract"] = settings.execution_contract
    requests, diagnostics, attempts = [], [], 0
    reference = None

    def server(request):
        nonlocal reference, attempts
        data = json.loads(request.content)
        requests.append(data)
        result = response()
        message = result["choices"][0]["message"]
        if len(requests) == 1:
            result["choices"][0]["finish_reason"] = "tool_calls"
            message.update(
                content=None,
                tool_calls=[
                    {
                        "id": "actual-read",
                        "type": "function",
                        "function": {
                            "name": "read_file",
                            "arguments": '{"path":"src/Demo.java"}',
                        },
                    }
                ],
            )
        elif "response_format" not in data:
            message["content"] = "Ready for submission."
        else:
            attempts += 1
            anchors = next(
                json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
                for m in data["messages"]
                if "Untrusted fixed input data: " in (m.get("content") or "")
            )
            for m in data["messages"]:
                if m["role"] == "tool":
                    reference = json.loads(m["content"])["data"]["evidenceReferences"][
                        0
                    ]
            evidence = [
                anchors["evidenceCandidateExample"],
                {
                    "evidenceType": "CODE",
                    "sourceRefs": [dict(reference)],
                    "coverage": "FULL",
                    "summary": "Declaration",
                },
            ]
            submission = {
                "decision": "COMPLETE",
                "evidenceCandidates": evidence,
                "candidates": [],
                "checkedScope": ["src/Demo.java"],
                "scopeKind": "FINAL_FILES",
                "coverage": "FULL",
                "limitations": ["Fixed synthetic input only"],
                "incompleteReasons": [],
            }
            if attempts == 1:
                item = candidate(reference)
                if mistake == "extra":
                    evidence[1]["sourceRefs"][0]["private_unknown_field"] = {
                        "api_key": "do not disclose"
                    }
                else:
                    submission["candidates"] = [item]
                    if mistake == "missing":
                        del item["claimKind"]
                    elif mistake == "empty":
                        item["evidenceIds"] = []
                    elif mistake == "uuid":
                        item["evidenceIds"] = ["password = 'do not disclose'"]
                    else:
                        item.update(
                            {
                                f"private_unknown_field_{i}": "password = 'do not disclose'"
                                for i in range(20)
                            }
                        )
            else:
                feedback = next(
                    json.loads(m["content"])
                    for m in data["messages"]
                    if m["role"] == "user"
                    and "platformSubmissionFeedback" in (m.get("content") or "")
                )
                details = feedback["platformSubmissionFeedback"]["schemaViolations"]
                assert 1 <= len(details) <= 8
                first = details[0]
                assert (
                    first["errorType"]
                    == {
                        "missing": "missing",
                        "empty": "too_short",
                        "uuid": "string_pattern_mismatch",
                        "extra": "extra_forbidden",
                        "many": "extra_forbidden",
                    }[mistake]
                )
                if mistake == "missing":
                    assert first["expected"]["enum"] == [
                        "STATIC_CORRECTNESS",
                        "ENVIRONMENT_DEPENDENT",
                        "QUANTITATIVE",
                    ]
                elif mistake == "empty":
                    assert (
                        first["expected"]["minItems"] == 1
                        and first["expected"]["maxItems"] == 16
                    )
                elif mistake == "uuid":
                    assert first["expected"]["pattern"].startswith("^[0-9a-f]{8}")
                else:
                    assert first["fieldPath"][-1] == "<unknown-field>"
                    assert first["expected"]["additionalProperties"] is False
                    assert (
                        "sha256" in first["expected"]["allowedFields"]
                        if mistake == "extra"
                        else "claimKind" in first["expected"]["allowedFields"]
                    )
                assert "private_unknown_field" not in json.dumps(details)
                assert "do not disclose" not in json.dumps(data)
                if mistake == "extra":
                    rejected = next(
                        m["content"]
                        for m in reversed(data["messages"])
                        if m["role"] == "assistant"
                    )
                    assert (
                        json.loads(rejected)["evidenceCandidates"][1]["sourceRefs"][0][
                            "private_unknown_field"
                        ]["api_key"]
                        == "[REDACTED]"
                    )
                diagnostics.append(feedback["diagnosticArtifactRef"]["artifactId"])
            message["content"] = json.dumps(submission)
        return httpx.Response(200, stream=httpx.ByteStream(canonical(result)))

    app = create_app(settings, provider_transport=httpx.MockTransport(server))
    async with app.router.lifespan_context(app), client_for(app) as client:
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 60)
        run_url = f"/internal/v1/review-runs/{payload['runId']}"
        run = (await client.get(run_url)).json()
        assert run["status"] == "SUCCEEDED", run
        assert run["resourceState"] == "RELEASED"
        result = (await client.get(run_url + "/result")).json()
        assert len(result["evidence"]) == 2 and not result["findings"]
        assert len(requests) == 4 and attempts == 2
        assert len(diagnostics) == 1
        content = (
            await client.get(run_url + f"/artifacts/{diagnostics[0]}/content")
        ).json()["text"]
        assert "schemaViolations" in content
        assert (
            "private_unknown_field" not in content and "do not disclose" not in content
        )
