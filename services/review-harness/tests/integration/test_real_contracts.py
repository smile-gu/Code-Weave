"""Batch 5 public wire boundaries; no network or paid provider requests."""

import pytest
from pydantic import ValidationError

from review_harness.domain.provider import ProviderRequest


def test_real_request_requires_explicit_nonthinking_and_closed_tool_history():
    request = {
        "model": "deepseek-flash",
        "messages": [{"role": "user", "content": "Check correctness."}],
        "thinking": {"type": "disabled"},
        "stream": False,
        "max_tokens": 4096,
    }
    assert ProviderRequest.model_validate(request).model == "deepseek-flash"
    for bad in (
        {**request, "thinking": {"type": "enabled"}},
        {**request, "endpoint": "https://example.invalid"},
        {
            **request,
            "messages": [
                {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "call_provider_1",
                            "type": "function",
                            "function": {
                                "name": "read_file",
                                "arguments": '{"path":"a.java"}',
                            },
                        }
                    ],
                }
            ],
        },
    ):
        with pytest.raises(ValidationError):
            ProviderRequest.model_validate(bad)


def incomplete_result():
    return {
        "resultVersion": "2.0",
        "resultId": "11111111-1111-4111-8111-111111111111",
        "runId": "22222222-2222-4222-8222-222222222222",
        "snapshotId": "33333333-3333-4333-8333-333333333333",
        "manifestSha256": "sha256:" + "a" * 64,
        "generatedAt": "2026-10-10T14:00:00.000000Z",
        "passResults": [
            {
                "passId": "correctness",
                "status": "INCOMPLETE",
                "checkedScope": ["src/Demo.java"],
                "coverage": "PARTIAL",
                "limitations": ["Call budget exhausted"],
                "incompleteReasons": ["MODEL_CALL_LIMIT"],
                "unvalidatedCandidates": 0,
                "findingIds": [],
                "usage": {"modelCalls": 1, "inputTokens": 17, "outputTokens": 2},
            }
        ],
        "evidence": [],
        "findings": [],
        "reportDraft": {"markdown": "", "byteSize": 0},
        "diagnostics": {
            "executionMode": "REAL",
            "contextManifestArtifactIds": ["66666666-6666-4666-8666-666666666666"],
            "modelCallIds": ["44444444-4444-4444-8444-444444444444"],
            "meterVersion": "v4.1-2cba9e42-v1",
            "limitations": ["Finite local meter samples"],
        },
    }


def test_real_result_rejects_unknown_nested_fields_and_dangling_finding_refs():
    from review_harness.domain.review import ReviewResult

    value = incomplete_result()
    assert ReviewResult.model_validate(value).resultVersion == "2.0"
    value["passResults"][0]["findingIds"] = ["55555555-5555-4555-8555-555555555555"]
    with pytest.raises(ValidationError):
        ReviewResult.model_validate(value)


def test_real_result_rejects_unknown_diagnostic_fields():
    from review_harness.domain.review import ReviewResult

    value = incomplete_result()
    value["diagnostics"]["rawPrompt"] = "secret"
    with pytest.raises(ValidationError):
        ReviewResult.model_validate(value)


def test_finding_requires_an_anchor_in_the_delivered_checked_scope():
    from review_harness.domain.review import ReviewResult

    value = incomplete_result()
    evidence_id = "77777777-7777-4777-8777-777777777777"
    finding_id = "88888888-8888-4888-8888-888888888888"
    ref = {
        "sourceType": "CODE",
        "path": "src/Other.java",
        "side": "final",
        "sha256": "sha256:" + "b" * 64,
        "startLine": 1,
        "endLine": 1,
    }
    value["evidence"] = [
        {
            "evidenceId": evidence_id,
            "runId": value["runId"],
            "snapshotId": value["snapshotId"],
            "evidenceType": "CODE",
            "sourceRefs": [ref],
            "coverage": "FULL",
            "summary": "Original code",
            "producerType": "MODEL_SUBMISSION",
            "producerId": value["diagnostics"]["modelCallIds"][0],
            "capturedAt": value["generatedAt"],
        }
    ]
    value["findings"] = [
        {
            "findingId": finding_id,
            "candidateId": "99999999-9999-4999-8999-999999999999",
            "validationModelCallId": value["diagnostics"]["modelCallIds"][0],
            "passId": "correctness",
            "category": "STATIC",
            "title": "A supported path",
            "claim": "The supplied path has a defect",
            "severity": "LOW",
            "confidence": 0.5,
            "evidenceIds": [evidence_id],
            "codeReferences": [ref],
            "claimKind": "STATIC_CORRECTNESS",
            "suggestion": "Check the path",
            "reasoningSummary": {
                "rule": "A static rule",
                "trigger": "The supplied input",
                "executionPath": "The supplied path",
                "assumptions": [],
                "counterexamples": ["A guard would prevent the failure"],
                "conclusion": "A path is supported",
            },
            "disposition": "VERIFIED",
            "validationSummary": {
                "basis": "Original code inspected",
                "counterexampleChecked": "Checked the guard",
                "counterexampleHolds": False,
                "assumptionsResolved": True,
            },
        }
    ]
    value["passResults"][0]["findingIds"] = [finding_id]
    with pytest.raises(ValidationError):
        ReviewResult.model_validate(value)
    value["passResults"][0]["checkedScope"] = ["src/Other.java"]
    assert ReviewResult.model_validate(value).findings[0].findingId == finding_id
