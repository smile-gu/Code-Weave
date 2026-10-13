import asyncio
import copy
import json
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.api.app import create_app
from review_harness.domain.model import canonical, sha256
from tests.integration.test_deepseek_adapter import response
from tests.integration.test_lifecycle import client_for, settings_for
from tests.snapshot_fixture import publish_snapshot


@pytest.mark.parametrize(
    "core_invalid,tamper",
    [
        (False, None),
        (True, None),
        ("access", None),
        (False, "codeReferences"),
        (False, "evidenceIds"),
        (False, "claimKind"),
        (False, "unknown"),
        (False, "candidateId"),
        (False, "quantitative"),
        (False, "unresolved"),
        (False, "missing-audit"),
        (False, "bad-quote"),
        (False, "bad-source"),
        (False, "bad-user-quote"),
        (False, "core-verified"),
        (False, "no-correction"),
        (False, "unchanged-correction"),
        (False, "extra-evidence"),
        (False, "other-evidence"),
        (False, "contradiction-recover"),
        (False, "contradiction-persistent"),
    ],
)
async def test_conflicting_user_description_is_corrected_or_rejected(
    database, tmp_path, payload, core_invalid, tamper
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
    payload["input"]["message"] = (
        "The public method value(Integer x) may receive null; null must return zero."
    )
    payload["snapshot"]["snapshotId"] = str(uuid4())
    source = b"class Demo { int value(Integer x) { return x.intValue(); } }\n"
    if core_invalid is True:
        source = b"class Demo { int value(Integer x) { if (x == null) return 0; return x.intValue(); } }\n"
    publish_snapshot(settings.snapshot_root, payload, {"src/Demo.java": source})
    requests = []
    reference = None

    def server(request):
        nonlocal reference
        data = json.loads(request.content)
        requests.append(data)
        anchors = next(
            json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
            for m in data["messages"]
            if "Untrusted fixed input data: " in (m.get("content") or "")
        )
        value = response()
        message = value["choices"][0]["message"]
        if "Validate candidates" in data["messages"][0]["content"]:
            original = anchors["candidates"][0]
            projection = next(
                json.loads(m["content"].removeprefix("Untrusted fixed input data: "))[
                    "candidateFactSources"
                ]
                for m in data["messages"]
                if '"candidateFactSources"' in (m.get("content") or "")
            )
            assert projection[original["candidateHandle"]] == [
                original["codeSelections"][0]["sourceHandle"]
            ]
            materials = next(
                json.loads(m["content"].removeprefix("Untrusted fixed input data: "))[
                    "candidateMaterials"
                ]
                for m in data["messages"]
                if '"candidateMaterials"' in (m.get("content") or "")
            )
            assert (
                materials[0]["evidence"][0]["materials"][0]["text"] == source.decode()
            )
            corrected = copy.deepcopy(original)
            corrected.update(
                title="Package-private method dereferences nullable input",
                claim="The package-private value(Integer x) throws for null instead of returning zero.",
            )
            corrected["reasoningSummary"].update(
                assumptions=[
                    "Null behavior is required; implementation access is package-private, contrary to the request description."
                ],
                conclusion="The null regression applies when value(null) is invoked within its accessible scope.",
            )
            if tamper == "codeReferences":
                corrected["codeSelections"][0]["sourceHandle"] = "s999"
            elif tamper == "evidenceIds":
                corrected["evidenceLinks"] = [{"kind": "SAVED", "handle": "e999"}]
            elif tamper == "claimKind":
                corrected["claimKind"] = "ENVIRONMENT_DEPENDENT"
            elif tamper == "unknown":
                corrected["unapprovedField"] = True
            elif tamper == "candidateId":
                original = {**original, "candidateHandle": "c999"}
            elif tamper == "quantitative":
                corrected["claim"] = "The change increases latency by 10 ms."
            message["content"] = json.dumps(
                {
                    "validations": [
                        {
                            "candidateHandle": original["candidateHandle"],
                            **(
                                {}
                                if tamper == "missing-audit"
                                else {
                                    "factChecks": [
                                        {
                                            "candidateText": anchors["candidates"][0][
                                                "claim"
                                            ],
                                            "candidateHandle": anchors["candidates"][0][
                                                "candidateHandle"
                                            ],
                                            "sourceHandle": anchors["candidates"][0][
                                                "codeSelections"
                                            ][0]["sourceHandle"],
                                            "sourceQuote": source.decode().strip(),
                                            "userQuote": "The public method value(Integer x)",
                                            "outcome": "CORE_CONTRADICTED"
                                            if core_invalid
                                            else "CORRECTED",
                                            "explanation": "Default access contradicts the claimed visibility; evaluate null behavior independently.",
                                        }
                                    ]
                                }
                            ),
                            "disposition": "REJECTED" if core_invalid else "VERIFIED",
                            **(
                                {}
                                if core_invalid
                                else {"correctedCandidate": corrected}
                            ),
                            "validationSummary": {
                                "basis": "Source has default access; the claim requires direct invocation from another package."
                                if core_invalid == "access"
                                else "Source has a null guard; original no-guard premise is false."
                                if core_invalid
                                else "Source has default access and no null guard; the user description of public access is incorrect.",
                                "counterexampleChecked": "Check access modifier, null guard, assumptions and impact scope against raw source.",
                                "counterexampleHolds": bool(core_invalid),
                                "assumptionsResolved": not core_invalid
                                and tamper != "unresolved",
                            },
                        }
                    ]
                }
            )
            if tamper in {
                "bad-quote",
                "bad-source",
                "bad-user-quote",
                "core-verified",
                "no-correction",
                "unchanged-correction",
                "other-evidence",
            }:
                reply = json.loads(message["content"])
                item = reply["validations"][0]
                check = item["factChecks"][0]
                if tamper == "bad-quote":
                    check["sourceQuote"] = "invented source text"
                elif tamper == "bad-source":
                    check["sourceHandle"] = "s999"
                elif tamper == "bad-user-quote":
                    check["userQuote"] = "invented original request"
                elif tamper == "core-verified":
                    check["outcome"] = "CORE_CONTRADICTED"
                elif tamper == "no-correction":
                    item.pop("correctedCandidate")
                elif tamper == "unchanged-correction":
                    item["correctedCandidate"]["claim"] = anchors["candidates"][0][
                        "claim"
                    ]
                elif tamper == "other-evidence":
                    check["sourceHandle"] = anchors["evidenceRecords"][1]["selections"][
                        0
                    ]["sourceHandle"]
                message["content"] = json.dumps(reply)
            if tamper in {"contradiction-recover", "contradiction-persistent"}:
                validation_requests = [
                    r
                    for r in requests
                    if "Validate candidates" in r["messages"][0]["content"]
                ]
                if len(validation_requests) > 1:
                    assert "VALIDATION_CONTRADICTION" in json.dumps(data)
                    assert "counterexample" in json.dumps(data)
                    rejected = next(
                        m
                        for m in data["messages"]
                        if m.get("kind") == "untrusted-rejected-submission"
                        or (
                            m["role"] == "assistant"
                            and '"validations"' in (m.get("content") or "")
                        )
                    )
                    assert (
                        json.loads(rejected["content"])["validations"][0][
                            "validationSummary"
                        ]["counterexampleHolds"]
                        is True
                    )
                reply = json.loads(message["content"])
                reply["validations"][0]["validationSummary"]["counterexampleHolds"] = (
                    tamper == "contradiction-persistent"
                    or len(validation_requests) == 1
                )
                message["content"] = json.dumps(reply)
        elif len(requests) == 1:
            value["choices"][0].update(
                finish_reason="tool_calls",
                message={
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "read-source",
                            "type": "function",
                            "function": {
                                "name": "read_file",
                                "arguments": '{"path":"src/Demo.java"}',
                            },
                        }
                    ],
                },
            )
        elif "response_format" not in data:
            message["content"] = "Ready to submit."
        else:
            reference = {
                "sourceHandle": next(
                    s["sourceHandle"]
                    for s in anchors["sourceCatalog"]
                    if s["sourceKind"] == "CODE"
                ),
                "startLine": 1,
                "endLine": 1,
            }
            records = anchors.get("evidenceRecords", [])
            candidate = {
                "passId": "correctness",
                "category": "NULL_DEREFERENCE",
                "title": "Public method dereferences null",
                "claim": "The public value(Integer x) has no null guard and throws for null.",
                "severity": "HIGH",
                "confidence": 0.9,
                "evidenceLinks": [
                    {"kind": "SAVED", "handle": records[0]["evidenceHandle"]}
                ]
                if records
                else [{"kind": "NEW", "key": "n1"}],
                "codeSelections": [reference],
                "claimKind": "STATIC_CORRECTNESS",
                "reasoningSummary": {
                    "rule": "Null must return zero.",
                    "trigger": "Call value(null).",
                    "executionPath": "Enter value; dereference x.",
                    "assumptions": [
                        "The method is public and callable outside its package."
                    ],
                    "counterexamples": ["A null guard would refute the defect."],
                    "conclusion": "Public callers can trigger the null regression.",
                },
                "suggestion": "Restore the null guard.",
            }
            if core_invalid == "access":
                candidate["claim"] = (
                    "Callers in unrelated packages can directly invoke public value(null) and trigger the failure."
                )
                candidate["reasoningSummary"]["trigger"] = (
                    "Direct invocation from another package."
                )
            message["content"] = json.dumps(
                {
                    "decision": "COMPLETE",
                    "evidenceCandidates": []
                    if records
                    else [
                        {
                            "evidenceType": "CODE",
                            "localKey": "n1",
                            "selections": [reference],
                            "coverage": "FULL",
                            "summary": "Original value method implementation.",
                        }
                    ],
                    "candidates": [candidate],
                    "checkedScope": ["src/Demo.java"],
                    "scopeKind": "FINAL_FILES",
                    "coverage": "FULL",
                    "limitations": ["Only the fixed source was inspected."],
                    "incompleteReasons": [],
                }
            )
            if not records and tamper in {"extra-evidence", "other-evidence"}:
                reply = json.loads(message["content"])
                extra = copy.deepcopy(reply["evidenceCandidates"][0])
                extra.update(
                    localKey="n2",
                    evidenceType="DOMAIN_CONTEXT",
                    selections=[
                        {
                            "sourceHandle": next(
                                s["sourceHandle"]
                                for s in anchors["sourceCatalog"]
                                if s["sourceKind"] == "USER_INPUT"
                            )
                        }
                    ],
                )
                extra["summary"] = (
                    "Additional Run evidence not acknowledged by this candidate."
                )
                reply["evidenceCandidates"].append(extra)
                message["content"] = json.dumps(reply)
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

    app = create_app(settings, provider_transport=httpx.MockTransport(server))
    async with app.router.lifespan_context(app), client_for(app) as client:
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        await asyncio.wait_for(app.state.supervisor.join(), 60)
        url = "/internal/v1/review-runs/" + payload["runId"]
        run = (await client.get(url)).json()
        assert run["resourceState"] == "RELEASED"
        if tamper in {
            "codeReferences",
            "evidenceIds",
            "claimKind",
            "unknown",
            "candidateId",
            "missing-audit",
            "bad-quote",
            "bad-source",
            "bad-user-quote",
            "core-verified",
            "no-correction",
            "unchanged-correction",
            "other-evidence",
            "contradiction-persistent",
        }:
            assert run["status"] == "FAILED", run
            assert run["failure"]["code"] in {
                "PROVIDER_VALIDATION_INVALID",
                "PROVIDER_STRUCTURED_OUTPUT_INVALID",
            }
            assert (
                sum(
                    "Validate candidates" in r["messages"][0]["content"]
                    for r in requests
                )
                == 3
            )
            assert (await client.get(url + "/result")).status_code != 200
            return
        assert run["status"] == "SUCCEEDED", run
        result = (await client.get(url + "/result")).json()
        if core_invalid:
            assert result["findings"] == []
        elif tamper in {"quantitative", "unresolved"}:
            assert result["findings"][0]["disposition"] == "RISK"
        else:
            finding = result["findings"][0]
            assert finding["disposition"] == "VERIFIED"
            assert (
                finding["title"] == "Package-private method dereferences nullable input"
            )
            assert (
                finding["claim"]
                == "The package-private value(Integer x) throws for null instead of returning zero."
            )
            assert (
                finding["reasoningSummary"]["conclusion"]
                == "The null regression applies when value(null) is invoked within its accessible scope."
            )
            assert "Public callers" not in result["reportDraft"]["markdown"]
            assert finding["codeReferences"] == [
                {
                    "sourceType": "CODE",
                    "path": "src/Demo.java",
                    "side": "final",
                    "sha256": sha256(source),
                    "startLine": 1,
                    "endLine": 1,
                }
            ]
        for data in requests:
            assert (
                "User input defines expected behavior; original source and Diff determine implementation facts."
                in data["messages"][0]["content"]
            )
        assert (
            "key facts, necessary assumptions and impact scope"
            in requests[-1]["messages"][0]["content"]
        )
