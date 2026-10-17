"""Production API/request boundary: positions replace copying, never the evidence gates."""

import asyncio
import copy
import json
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.api.app import create_app
from review_harness.domain.model import canonical
from tests.integration.test_deepseek_adapter import response
from tests.integration.test_lifecycle import client_for, settings_for
from tests.snapshot_fixture import publish_snapshot


@pytest.mark.parametrize(
    "mode",
    [
        "corrected",
        "c11-corrected",
        "c11-core",
        "multipass",
        "multipass-source-intake",
        "source-intake",
        "source-storage-failure",
        "source-checkpoint-unconfirmed",
        "self-partial",
        "bad-user-quote",
        "provenance-change",
        "unchanged-field",
        "whitespace-field",
        "core",
        "core-verified",
        "contradiction",
        "benign",
        "legacy-fields",
        "missing-version",
        "storage-failure",
        "checkpoint-lost-ack",
        "checkpoint-unconfirmed",
        "cancel",
    ],
)
async def test_position_protocol_uses_original_fields_and_preserves_delivery_gates(
    database, tmp_path, payload, mode
):
    use_source_intake = mode.startswith("source-") or mode == "multipass-source-intake"
    if mode == "multipass-source-intake":
        mode = "multipass"
    if mode in {"source-storage-failure", "source-checkpoint-unconfirmed"}:
        mode = mode.removeprefix("source-")
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "execution_mode": "REAL",
            "deepseek_api_key": SecretStr("offline-only"),
            "checkpoint_database_url": f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
        }
    )
    if mode == "multipass":
        from review_harness.domain.model import utc_now
        from tests.integration.test_fee_ledger import profile
        from tests.integration.test_multipass_contracts import plan_data

        price = profile()
        price_path = tmp_path / "price.json"
        price_path.write_bytes(canonical(price.model_dump()))
        settings = settings.model_copy(update={"price_profile_path": price_path})
        plan = plan_data()
        plan["feeAuthorization"].update(
            confirmedAt=utc_now(),
            priceProfileId=price.priceProfileId,
            priceProfileDigest=price.digest,
        )
        payload.update(protocolVersion="1.1", reviewPlan=plan)
    payload["executionContract"] = (
        {"executionMode": "REAL", "resultVersion": "3.0"}
        if mode == "multipass"
        else settings.execution_contract
    )
    payload["snapshot"]["snapshotId"] = str(uuid4())
    payload["input"]["message"] = "The public value method must return zero for null."
    guarded = mode in {"core", "core-verified", "benign"}
    source = (
        "class Demo {\n int value(Integer x) { "
        + ("if (x == null) return 0; " if guarded else "")
        + "return x.intValue(); }\n}\n"
    )
    quality = None
    if mode.startswith("c11-"):
        quality = json.loads(
            (
                Path(__file__).parents[1] / "fixtures/c11-known-quality-failure.json"
            ).read_bytes()
        )
        source = quality["source"]
        payload["input"]["message"] = (
            "Review null-contract correctness against the fixed source."
        )
    fact_line = 10 if quality else 2
    corrected_claim = (
        "Both methods reject null with NPE through different mechanisms; nullableLength violates its retained null-to-zero contract."
        if quality
        else "Package-private value(null) throws instead of returning zero."
    )
    publish_snapshot(
        settings.snapshot_root, payload, {"src/Demo.java": source.encode()}
    )
    requests, validation_requests = [], []
    validation_started, release = asyncio.Event(), asyncio.Event()

    async def server(request):
        data = json.loads(request.content)
        assert (
            "Report language: Simplified Chinese (zh-CN)"
            in data["messages"][0]["content"]
        )
        requests.append(data)
        reply = response()
        choice = reply["choices"][0]
        anchors = next(
            json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
            for m in data["messages"]
            if "Untrusted fixed input data: " in (m.get("content") or "")
        )
        assert anchors["validationProtocol"] == "POSITION_V1"
        if "DedupDecision" in data["messages"][0]["content"]:
            group = next(
                json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])[
                    "untrustedDedupGroup"
                ]
                for m in data["messages"]
                if "untrustedDedupGroup" in (m.get("content") or "")
            )
            choice["message"]["content"] = json.dumps(
                {
                    "candidateHandles": [c["candidateHandle"] for c in group],
                    "sameRootCause": True,
                    "sameTrigger": True,
                    "sameFix": True,
                    "rationale": "Same null contract and fix",
                }
            )
        elif "Validate candidates independently" in data["messages"][0]["content"]:
            validation_requests.append(data)
            projection = next(
                json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
                for m in data["messages"]
                if '"candidateMaterials"' in (m.get("content") or "")
            )
            material = projection["candidateMaterials"][0]
            original = material["candidate"]
            assert material["candidateFields"]["/claim"] == original["claim"]
            assert "/candidateHandle" not in material["candidateFields"]
            source_view = material["evidence"][0]["materials"][0]
            assert source_view["coordinateKind"] == "SOURCE_FILE_LINE"
            assert source_view["numberedText"] == "".join(
                f"{n}: {line}"
                for n, line in enumerate(source.splitlines(keepends=True), 1)
            )
            corrected = copy.deepcopy(original)
            corrected.update(
                title="Package-private method dereferences null",
                claim=corrected_claim,
            )
            corrected["reasoningSummary"].update(
                assumptions=[
                    "Default access; evaluate callers within accessible scope"
                ],
                conclusion="Null regression within accessible scope",
            )
            if quality:
                corrected["title"] = "Retained null-to-zero contract is violated"
                corrected["reasoningSummary"].update(
                    assumptions=[
                        "Both methods reject null; explicit and implicit checks differ in mechanism"
                    ],
                    conclusion=corrected_claim,
                )
            # The original claim may legitimately be retained in another field:
            # CORRECTED compares the selected field, not every string globally.
            corrected["suggestion"] = (
                "Restore the nullableLength null guard to honor its contract."
                if quality
                else "Historical rejected description: " + original["claim"]
            )
            if mode == "provenance-change":
                corrected["codeSelections"][0]["sourceHandle"] = "s999"
            if mode in {"unchanged-field", "whitespace-field"}:
                corrected["claim"] = original["claim"] + (
                    "  " if mode == "whitespace-field" else ""
                )
            disposition = (
                "REJECTED" if mode in {"core", "benign", "c11-core"} else "VERIFIED"
            )
            check = {
                "candidateHandle": original["candidateHandle"],
                "candidateField": "/claim",
                "sourceSelection": {
                    "sourceHandle": source_view["sourceHandle"],
                    "startLine": fact_line,
                    "endLine": fact_line,
                },
                "outcome": "CORE_CONTRADICTED"
                if mode in {"core", "core-verified", "c11-core"}
                else "SUPPORTED"
                if mode == "benign"
                else "CORRECTED",
                "explanation": "Compare access, null guard and defect eligibility against original code, independently of the request description",
                "userQuote": "Review null-contract correctness"
                if quality
                else "The public value method",
            }
            if mode == "bad-user-quote":
                check["userQuote"] = "Invented user description"
            item = {
                "candidateHandle": original["candidateHandle"],
                "disposition": disposition,
                "validationSummary": {
                    "basis": "Original source decides implementation facts",
                    "counterexampleChecked": "Guard and access checked",
                    "counterexampleHolds": mode
                    in {"core", "contradiction", "c11-core"},
                    "assumptionsResolved": True,
                },
                "factChecks": [check],
            }
            if mode not in {"core", "core-verified", "benign", "c11-core"}:
                item["correctedCandidate"] = corrected
            value = {"validationVersion": "POSITION_V1", "validations": [item]}
            if mode == "legacy-fields":
                check.update(candidateText=original["claim"], sourceQuote=source)
            if mode == "missing-version":
                value.pop("validationVersion")
            choice["message"]["content"] = json.dumps(value)
            if mode == "storage-failure":
                settings.artifact_root.rename(tmp_path / "moved-artifacts")
            if mode == "cancel":
                validation_started.set()
                await release.wait()
        elif "response_format" not in data:
            if not any(m["role"] == "tool" for m in data["messages"]):
                choice.update(
                    finish_reason="tool_calls",
                    message={
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": "read-fixed-source-"
                                + anchors.get("activePass", "correctness"),
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
            source_handle = next(
                s["sourceHandle"]
                for s in anchors["sourceCatalog"]
                if s["sourceKind"] == "CODE"
                and s.get("observedInPass", "correctness")
                == anchors.get("activePass", "correctness")
            )
            selection = {
                "sourceHandle": source_handle,
                "startLine": 1,
                "endLine": len(source.splitlines()),
            }
            claim = (
                "The public method dereferences null."
                if mode != "benign"
                else "The guard safely returns zero for null; no defect."
            )
            if quality:
                claim = quality["observation"]["publishedClaim"]
            candidate = {
                "passId": anchors.get("activePass", "correctness"),
                "category": "Null behavior",
                "title": "Null guard observation",
                "claim": claim,
                "severity": "HIGH",
                "confidence": 0.9,
                "claimKind": "STATIC_CORRECTNESS",
                "evidenceLinks": [{"kind": "NEW", "key": "n1"}],
                "codeSelections": [selection],
                "suggestion": "Check the contract",
                "reasoningSummary": {
                    "rule": "Null returns zero",
                    "trigger": "Call with null",
                    "executionPath": "Read source",
                    "assumptions": ["Public access"],
                    "counterexamples": ["A null guard"],
                    "conclusion": claim,
                },
            }
            choice["message"]["content"] = json.dumps(
                {
                    "decision": "COMPLETE",
                    "candidates": [candidate],
                    "evidenceCandidates": [
                        {
                            "localKey": "n1",
                            "evidenceType": "CODE",
                            "selections": [selection],
                            "coverage": "FULL",
                            "summary": "Fixed source",
                        }
                    ],
                    "checkedScope": ["src/Demo.java"],
                    "scopeKind": "FINAL_FILES",
                    "coverage": "PARTIAL" if mode == "self-partial" else "FULL",
                    "limitations": ["Only fixed source inspected"],
                    "incompleteReasons": [],
                }
            )
            if use_source_intake:
                value = json.loads(choice["message"]["content"])
                value["submissionVersion"] = "SOURCE_V1"
                value.pop("evidenceCandidates")
                value["candidates"][0].pop("evidenceLinks")
                value["candidates"][0].pop("codeSelections")
                value["candidates"][0]["location"] = selection
                value["candidates"][0]["supportingSources"] = []
                choice["message"]["content"] = json.dumps(value)
                assert "SOURCE_V1" in data["messages"][0]["content"]
        return httpx.Response(200, stream=httpx.ByteStream(canonical(reply)))

    app = create_app(settings, provider_transport=httpx.MockTransport(server))
    async with app.router.lifespan_context(app), client_for(app) as client:
        if mode.startswith("checkpoint-"):

            async def boundary():
                cp = app.state.checkpoints
                async with cp.connection.execute(
                    "SELECT checkpoint FROM checkpoints ORDER BY rowid DESC LIMIT 1"
                ) as cursor:
                    row = await cursor.fetchone()
                if row and json.loads(row[0])["channel_values"]["state"].get(
                    "validationRecordRefs"
                ):
                    if mode == "checkpoint-unconfirmed":
                        await cp.connection.execute(
                            "DELETE FROM checkpoints WHERE json_array_length(json_extract(CAST(checkpoint AS TEXT),'$.channel_values.state.validationRecordRefs')) > 0"
                        )
                        await cp.connection.commit()
                    raise OSError("Offline checkpoint acknowledgement lost")

            app.state.checkpoints.after_commit = boundary
        assert (
            await client.post("/internal/v1/review-runs", json=payload)
        ).status_code == 202
        url = "/internal/v1/review-runs/" + payload["runId"]
        if mode == "cancel":
            await asyncio.wait_for(validation_started.wait(), 20)
            assert (await client.post(url + "/cancel")).status_code == 202
            release.set()
        await asyncio.wait_for(app.state.supervisor.join(), 60)
        status = (await client.get(url)).json()
        assert status["resourceState"] == "RELEASED"
        if mode in {
            "bad-user-quote",
            "provenance-change",
            "unchanged-field",
            "whitespace-field",
            "core-verified",
            "contradiction",
            "legacy-fields",
            "missing-version",
            "checkpoint-unconfirmed",
            "storage-failure",
            "cancel",
        }:
            assert status["status"] == (
                "CANCELLED" if mode == "cancel" else "FAILED"
            ), status
            assert (await client.get(url + "/result")).status_code != 200
            if mode not in {"checkpoint-unconfirmed", "storage-failure", "cancel"}:
                assert len(validation_requests) == 3
            return
        assert status["status"] == "SUCCEEDED", status
        result = (await client.get(url + "/result")).json()
        if mode == "self-partial":
            assert result["passResults"][0]["status"] == "INCOMPLETE"
            assert (
                "REVIEW_COVERAGE_PARTIAL"
                in result["passResults"][0]["incompleteReasons"]
            )
            assert (
                "SOURCE_COVERAGE_PARTIAL"
                not in result["passResults"][0]["incompleteReasons"]
            )
        if mode in {"core", "benign", "c11-core"}:
            assert result["findings"] == []
        else:
            assert len(result["findings"]) == 1
            assert result["findings"][0]["claim"] == corrected_claim
        executor = (
            app.state.supervisor.executor.multipass
            if mode == "multipass"
            else app.state.supervisor.executor.single
        )
        state = executor.last_state
        assert state.stateVersion == ("6.0" if mode == "multipass" else "5.0")
        assert len(state.validationRecordRefs) == (6 if mode == "multipass" else 1)
        if mode == "multipass":
            assert all(p["status"] == "COMPLETED" for p in result["passResults"])
            assert len(result["findings"][0]["contributions"]) == 6
            assert result["diagnostics"]["feeSummary"]["reservedMicros"] == 0
        artifact = state.validationRecordRefs[0]
        page = (
            await client.get(url + "/artifacts/" + artifact.artifactId + "/content")
        ).json()
        assert not page["hasMore"]
        record = json.loads(page["text"])
        assert record["validationProtocol"] == "POSITION_V1"
        assert record["bindings"][0]["candidateField"] == "/claim"
        assert (
            record["bindings"][0]["sourceQuote"]
            == source.splitlines(keepends=True)[fact_line - 1]
        )
        assert (
            "candidateText"
            not in record["submitted"]["validations"][0]["factChecks"][0]
        )
