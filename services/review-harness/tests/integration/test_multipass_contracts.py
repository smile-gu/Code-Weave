import copy

import pytest
from pydantic import ValidationError

from review_harness.domain.multipass import PASS_ORDER, ReviewPlan
from review_harness.domain.snapshot import WorkspaceError


def plan_data():
    return {
        "planVersion": "1.0",
        "selectedPasses": list(PASS_ORDER),
        "executionBudget": {
            "maxModelCallsPerPass": 300,
            "maxModelCallsPerRun": 2000,
            "maxDedupCalls": 10,
            "requestTimeoutSeconds": 120,
            "passTimeoutSeconds": 1800,
            "runTimeoutSeconds": 10800,
            "summaryTimeoutSeconds": 600,
        },
        "feeAuthorization": {
            "authorizationId": "11111111-1111-4111-8111-111111111111",
            "currency": "CNY",
            "maxAmount": "8.000001",
            "confirmedAt": "2026-10-13T09:00:00.000000Z",
            "priceProfileId": "deepseek-flash-verified-v1",
            "priceProfileDigest": "sha256:" + "a" * 64,
        },
    }


def test_plan_preserves_explicit_selection_and_exact_authorization():
    value = plan_data()
    plan = ReviewPlan.model_validate(value)
    assert plan.model_dump() == value
    assert plan.feeAuthorization.amount_micros == 8000001


@pytest.mark.parametrize(
    "selection",
    [[], ["correctness", "correctness"], ["unknown"], ["security", "correctness"]],
)
def test_plan_rejects_empty_unknown_duplicate_or_noncanonical_selection(selection):
    value = plan_data()
    value["selectedPasses"] = selection
    with pytest.raises(ValidationError):
        ReviewPlan.model_validate(value)


@pytest.mark.parametrize(
    "amount", ["0", "0.000000", "1e2", "1.0000001", 8.0, "-1", "NaN"]
)
def test_authorization_rejects_nonpositive_inexact_or_float_amount(amount):
    value = plan_data()
    value["feeAuthorization"]["maxAmount"] = amount
    with pytest.raises(ValidationError):
        ReviewPlan.model_validate(value)


def test_plan_requires_all_explicit_frozen_budgets():
    value = plan_data()
    value["executionBudget"]["maxModelCallsPerRun"] = 2001
    with pytest.raises(ValidationError):
        ReviewPlan.model_validate(value)
    missing = copy.deepcopy(plan_data())
    missing.pop("feeAuthorization")
    with pytest.raises(ValidationError):
        ReviewPlan.model_validate(missing)


def result_data():
    from tests.integration.test_real_contracts import incomplete_result

    value = incomplete_result()
    value["resultVersion"] = "3.0"
    value["generatedAt"] = "2026-10-13T10:00:00.000000Z"
    plan = plan_data()
    plan["selectedPasses"] = ["correctness"]
    value["reviewPlan"] = plan
    value["coverageStatus"] = "SELECTED_INCOMPLETE"
    value["passResults"][0].update(
        selected=True, skipReason=None, scopeKind="FINAL_FILES"
    )
    for pass_id in PASS_ORDER[1:]:
        value["passResults"].append(
            {
                "passId": pass_id,
                "selected": False,
                "status": "SKIPPED",
                "scopeKind": "FIXED_DIFF",
                "checkedScope": [],
                "coverage": "PARTIAL",
                "limitations": ["Not selected by user"],
                "incompleteReasons": [],
                "skipReason": "USER_NOT_SELECTED",
                "unvalidatedCandidates": 0,
                "findingIds": [],
                "usage": {"modelCalls": 0, "inputTokens": 0, "outputTokens": 0},
            }
        )
    value["diagnostics"].update(
        dedupUsage={"modelCalls": 0, "inputTokens": 0, "outputTokens": 0},
        feeSummary={
            "authorizationId": plan["feeAuthorization"]["authorizationId"],
            "currency": "CNY",
            "maxAmountMicros": 8000001,
            "spentMicros": 10,
            "reservedMicros": 0,
            "overrunMicros": 0,
            "unknownCalls": 0,
        },
    )
    from review_harness.application.report import render_multipass_report
    from review_harness.domain.multipass import MultipassPassResult

    value["reportDraft"] = render_multipass_report(
        [MultipassPassResult.model_validate(p) for p in value["passResults"]],
        [],
        value["coverageStatus"],
    ).model_dump()
    return value


def test_result3_keeps_six_dimensions_and_allows_unchecked_skipped_passes():
    from review_harness.domain.multipass import ReviewResultV3

    result = ReviewResultV3.model_validate(result_data())
    assert len(result.passResults) == 6
    assert result.passResults[1].checkedScope == []
    from review_harness.domain.review import ReviewResult

    with pytest.raises(ValidationError):
        ReviewResult.model_validate(result.model_dump())


@pytest.mark.parametrize(
    "mistake",
    [
        "order",
        "missing",
        "selection",
        "coverage",
        "scope",
        "skip-usage",
        "fee",
        "calls",
        "unknown",
    ],
)
def test_result3_rejects_inconsistent_pass_and_budget_facts(mistake):
    from review_harness.domain.multipass import ReviewResultV3

    value = result_data()
    if mistake == "order":
        value["passResults"].reverse()
    elif mistake == "missing":
        value["passResults"].pop()
    elif mistake == "selection":
        value["passResults"][1]["selected"] = True
    elif mistake == "coverage":
        value["coverageStatus"] = "SELECTED_COMPLETE"
    elif mistake == "scope":
        value["passResults"][0]["checkedScope"] = ["../escape"]
    elif mistake == "skip-usage":
        value["passResults"][1]["usage"]["modelCalls"] = 1
    elif mistake == "fee":
        value["diagnostics"]["feeSummary"]["maxAmountMicros"] += 1
    elif mistake == "calls":
        value["diagnostics"]["modelCallIds"] = []
    else:
        value["diagnostics"]["untrustedExtra"] = True
    with pytest.raises((ValidationError, WorkspaceError)):
        ReviewResultV3.model_validate(value)


async def test_new_request_is_validated_but_cannot_dispatch_into_single_pass_runtime(
    database, tmp_path, payload
):
    from review_harness.api.app import create_app
    from review_harness.api.dto import CreateRun, CreateRunV11
    from tests.integration.test_lifecycle import client_for, settings_for

    settings = settings_for(database, tmp_path)
    value = {
        **payload,
        "protocolVersion": "1.1",
        "executionContract": {"executionMode": "REAL", "resultVersion": "3.0"},
        "reviewPlan": plan_data(),
    }
    dto = CreateRunV11.model_validate(value)
    assert dto.reviewPlan.selectedPasses == list(PASS_ORDER)
    with pytest.raises(ValidationError):
        CreateRun.model_validate(value)
    app = create_app(settings)
    async with app.router.lifespan_context(app), client_for(app) as client:
        old = (await client.get("/internal/v1/review-capabilities")).json()
        assert old["protocolVersion"] == "1.0"
        new = (
            await client.get("/internal/v1/review-capabilities?protocolVersion=1.1")
        ).json()
        assert new["protocolVersion"] == "1.1"
        assert new["status"] == "NOT_READY"
        assert new["feeReservation"] == "NOT_READY"
        assert new["configuredContract"]["resultVersion"] == "3.0"
        rejected = await client.post("/internal/v1/review-runs", json=value)
        assert rejected.status_code == 400
        assert rejected.json()["errorCode"] == "EXECUTION_CONTRACT_UNSUPPORTED"
        assert (
            await client.get("/internal/v1/review-runs/" + payload["runId"])
        ).status_code == 404
        value.pop("reviewPlan")
        assert (
            await client.post("/internal/v1/review-runs", json=value)
        ).status_code == 422


def test_result3_rejects_markdown_that_invents_findings():
    from review_harness.domain.multipass import ReviewResultV3

    value = result_data()
    text = "# VERIFIED invented finding"
    value["reportDraft"] = {"markdown": text, "byteSize": len(text.encode())}
    with pytest.raises(ValidationError):
        ReviewResultV3.model_validate(value)


def result_with_finding():
    value = result_data()
    ref = {
        "sourceType": "CODE",
        "path": "src/Demo.java",
        "side": "final",
        "sha256": "sha256:" + "b" * 64,
        "startLine": 1,
        "endLine": 1,
    }
    evidence_id = "77777777-7777-4777-8777-777777777777"
    candidate_id = "88888888-8888-4888-8888-888888888888"
    finding_id = "99999999-9999-4999-8999-999999999999"
    call_id = value["diagnostics"]["modelCallIds"][0]
    contribution = {
        "passId": "correctness",
        "candidateId": candidate_id,
        "evidenceIds": [evidence_id],
        "codeReferences": [ref],
        "disposition": "VERIFIED",
        "validationModelCallId": call_id,
    }
    finding = {
        **contribution,
        "findingId": finding_id,
        "contributions": [contribution],
        "category": "Null safety",
        "title": "Null dereference",
        "claim": "Null throws.",
        "severity": "HIGH",
        "confidence": 0.9,
        "claimKind": "STATIC_CORRECTNESS",
        "suggestion": "Restore guard.",
        "reasoningSummary": {
            "rule": "Null returns zero",
            "trigger": "value(null)",
            "executionPath": "dereference",
            "assumptions": [],
            "counterexamples": ["Guard exists"],
            "conclusion": "Guard missing",
        },
        "validationSummary": {
            "basis": "Read source",
            "counterexampleChecked": "No guard",
            "counterexampleHolds": False,
            "assumptionsResolved": True,
        },
    }
    value["evidence"] = [
        {
            "evidenceId": evidence_id,
            "runId": value["runId"],
            "snapshotId": value["snapshotId"],
            "evidenceType": "CODE",
            "sourceRefs": [ref],
            "coverage": "FULL",
            "summary": "Observed code",
            "producerType": "MODEL_SUBMISSION",
            "producerId": call_id,
            "capturedAt": value["generatedAt"],
        }
    ]
    value["findings"] = [finding]
    value["passResults"][0]["findingIds"] = [finding_id]
    refresh_report(value)
    return value


def refresh_report(value):
    from review_harness.application.report import render_multipass_report
    from review_harness.domain.multipass import MultipassFinding, MultipassPassResult

    value["reportDraft"] = render_multipass_report(
        [MultipassPassResult.model_validate(p) for p in value["passResults"]],
        [MultipassFinding.model_validate(f) for f in value["findings"]],
        value["coverageStatus"],
    ).model_dump()


def test_result3_preserves_contributing_identity_and_original_evidence():
    from review_harness.domain.multipass import ReviewResultV3

    value = result_with_finding()
    assert ReviewResultV3.model_validate(value).findings[0].disposition == "VERIFIED"
    for field in ["evidenceIds", "candidateId", "validationModelCallId"]:
        broken = copy.deepcopy(value)
        broken["findings"][0][field] = (
            ["11111111-1111-4111-8111-111111111111"]
            if field == "evidenceIds"
            else "11111111-1111-4111-8111-111111111111"
        )
        with pytest.raises(ValidationError):
            ReviewResultV3.model_validate(broken)
    forged = copy.deepcopy(value)
    forged["evidence"][0]["snapshotId"] = "11111111-1111-4111-8111-111111111111"
    with pytest.raises(ValidationError):
        ReviewResultV3.model_validate(forged)


@pytest.mark.parametrize(
    "status,selected,reason",
    [
        ("SKIPPED", True, "RUN_BUDGET_EXHAUSTED"),
        ("INCOMPLETE", True, None),
    ],
)
def test_no_material_does_not_require_fabricated_checked_scope(
    status, selected, reason
):
    from review_harness.domain.multipass import ReviewResultV3

    value = result_data()
    p = value["passResults"][0]
    p.update(
        status=status,
        selected=selected,
        skipReason=reason,
        checkedScope=[],
        usage={"modelCalls": 0, "inputTokens": 0, "outputTokens": 0},
        incompleteReasons=[] if status == "SKIPPED" else ["NO_VERIFIABLE_MATERIAL"],
    )
    value["diagnostics"]["modelCallIds"] = []
    value["diagnostics"]["contextManifestArtifactIds"] = []
    refresh_report(value)
    assert ReviewResultV3.model_validate(value).coverageStatus == "SELECTED_INCOMPLETE"


def test_request_fingerprint_changes_with_plan_and_authorization():
    from review_harness.api.dto import CreateRunV11
    from review_harness.domain.model import canonical, sha256
    from tests.export_multipass_contracts import examples

    value = examples()["review-harness-create-run-v1.1"]
    first = sha256(canonical(CreateRunV11.model_validate(value).model_dump()))
    value["reviewPlan"]["selectedPasses"] = ["correctness"]
    assert sha256(canonical(CreateRunV11.model_validate(value).model_dump())) != first


@pytest.mark.parametrize(
    "name",
    [
        "review-plan-v1",
        "review-pass-result-v3",
        "review-execution-contract-v2",
        "review-capabilities-v2",
        "review-harness-create-run-v1.1",
        "review-harness-result-v3",
    ],
)
def test_shared_multipass_schema_is_current_and_strict(name):
    import json

    from jsonschema import Draft202012Validator

    from tests.export_multipass_contracts import MODELS, ROOT

    schema = json.loads((ROOT / "schemas" / (name + ".schema.json")).read_text("utf-8"))
    assert {k: v for k, v in schema.items() if k not in {"$id", "$schema"}} == MODELS[
        name
    ].model_json_schema()
    Draft202012Validator.check_schema(schema)
    fixture = json.loads(
        (ROOT / "fixtures/review-multipass-v1/examples.json").read_text("utf-8")
    )[name]
    Draft202012Validator(schema).validate(fixture["positive"])
    assert list(Draft202012Validator(schema).iter_errors(fixture["negative"]))
    MODELS[name].model_validate(fixture["positive"])
    with pytest.raises(ValidationError):
        MODELS[name].model_validate(fixture["negative"])


def test_merged_finding_keeps_contributing_passes_and_primary_grade():
    from review_harness.domain.multipass import ReviewResultV3

    value = result_with_finding()
    value["reviewPlan"]["selectedPasses"] = ["correctness", "security"]
    security = value["passResults"][2]
    security.update(
        selected=True,
        status="INCOMPLETE",
        skipReason=None,
        checkedScope=["src/Demo.java"],
        incompleteReasons=["FINITE_SCOPE_LIMIT"],
        usage={"modelCalls": 1, "inputTokens": 10, "outputTokens": 2},
    )
    call_id = "55555555-5555-4555-8555-555555555555"
    value["diagnostics"]["modelCallIds"].append(call_id)
    contribution = copy.deepcopy(value["findings"][0]["contributions"][0])
    contribution.update(
        passId="security",
        candidateId="66666666-6666-4666-8666-666666666666",
        disposition="RISK",
        validationModelCallId=call_id,
    )
    value["findings"][0]["contributions"].append(contribution)
    security["findingIds"] = [value["findings"][0]["findingId"]]
    refresh_report(value)
    result = ReviewResultV3.model_validate(value)
    assert [c.passId for c in result.findings[0].contributions] == [
        "correctness",
        "security",
    ]
    assert result.findings[0].contributions[1].disposition == "RISK"
    assert result.reportDraft.markdown.count("## Null dereference") == 1
    upgraded = copy.deepcopy(value)
    upgraded["findings"][0]["contributions"][0]["disposition"] = "RISK"
    with pytest.raises(ValidationError):
        ReviewResultV3.model_validate(upgraded)
    forged = copy.deepcopy(value)
    forged["findings"][0]["contributions"][1]["codeReferences"][0]["sha256"] = (
        "sha256:" + "c" * 64
    )
    with pytest.raises(ValidationError):
        ReviewResultV3.model_validate(forged)


def test_fee_summary_keeps_per_call_overrun_even_inside_total_authorization():
    from review_harness.domain.multipass import ReviewResultV3

    value = result_data()
    value["diagnostics"]["feeSummary"].update(spentMicros=10, overrunMicros=2)
    assert (
        ReviewResultV3.model_validate(value).diagnostics.feeSummary.overrunMicros == 2
    )


def test_incomplete_pass_without_material_cannot_claim_full_coverage():
    from review_harness.domain.multipass import MultipassPassResult

    value = result_data()["passResults"][0]
    value.update(checkedScope=[], coverage="FULL")
    with pytest.raises(ValidationError):
        MultipassPassResult.model_validate(value)


def test_contribution_cannot_repeat_evidence_to_inflate_support():
    from review_harness.domain.multipass import ReviewResultV3

    value = result_with_finding()
    ids = value["findings"][0]["contributions"][0]["evidenceIds"]
    ids.append(ids[0])
    with pytest.raises(ValidationError):
        ReviewResultV3.model_validate(value)
