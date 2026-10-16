"""Strict submission parsing reports safe coordinates without accepting prefixes."""

import pytest

from review_harness.domain.handles import LocalValidationBatch
from review_harness.domain.submission import SubmissionIssue
from review_harness.runtime.review import parse_submission


@pytest.mark.parametrize(
    "text,expected",
    [
        ('{"validations":[]}]  }', (1, 19, 18, "EXTRA_DATA")),
        ('{\n"validations":[]\n}] }', (3, 2, 20, "EXTRA_DATA")),
        ('{"秘密":true}{}', (1, 12, 11, "EXTRA_DATA")),
        ('{"validations": }', (1, 17, 16, "SYNTAX_INVALID")),
    ],
)
def test_json_errors_only_project_class_and_original_character_coordinates(
    text, expected
):
    with pytest.raises(SubmissionIssue) as caught:
        parse_submission(text, LocalValidationBatch)
    line, column, position, kind = expected
    assert caught.value.diagnostic() == {
        "reason": "JSON_INVALID",
        "fieldPath": [],
        "stage": "JSON",
        "code": "PROVIDER_STRUCTURED_OUTPUT_INVALID",
        "jsonError": {
            "errorType": kind,
            "line": line,
            "column": column,
            "position": position,
        },
    }


@pytest.mark.parametrize(
    "text",
    [
        '{"password":"diagnostic-secret","value":NaN}',
        '"' + "x" * 65536 + '"',
    ],
    ids=["non-finite", "byte-limit"],
)
def test_strict_semantic_and_size_rejections_do_not_expose_parser_values(text):
    with pytest.raises(SubmissionIssue) as caught:
        parse_submission(text, LocalValidationBatch)
    assert caught.value.diagnostic() == {
        "reason": "JSON_INVALID",
        "fieldPath": [],
        "stage": "JSON",
        "code": "PROVIDER_STRUCTURED_OUTPUT_INVALID",
    }


@pytest.mark.parametrize(
    "text",
    [
        '{"secret-key":"secret-value","secret-key":"other"}',
        '{"nested":{"a":1,"a":1}}',
        '[{"a":1,"a":2}]',
        r'{"a":1,"\u0061":2}',
    ],
)
def test_duplicate_keys_have_safe_specific_diagnostic(text):
    with pytest.raises(SubmissionIssue) as caught:
        parse_submission(text, LocalValidationBatch)
    assert caught.value.diagnostic() == {
        "reason": "JSON_INVALID",
        "fieldPath": [],
        "stage": "JSON",
        "code": "PROVIDER_STRUCTURED_OUTPUT_INVALID",
        "jsonError": {"errorType": "DUPLICATE_KEY"},
    }


def test_same_keys_in_separate_objects_are_legal():
    from review_harness.domain.provider import strict_json

    assert strict_json('[{"a":1},{"a":2}]') == [{"a": 1}, {"a": 2}]


def test_historical_duplicate_key_replies_remain_rejected():
    import json
    from pathlib import Path

    from review_harness.runtime.dedup import DedupDecision

    records = json.loads(
        (
            Path(__file__).parents[1] / "fixtures/dedup-real-duplicate-keys.json"
        ).read_bytes()
    )
    assert [r["trace"] for r in records] == ["035", "037", "038"]
    for record in records:
        with pytest.raises(SubmissionIssue) as caught:
            parse_submission(record["content"], DedupDecision)
        assert caught.value.diagnostic()["jsonError"] == {"errorType": "DUPLICATE_KEY"}


def test_duplicate_exception_does_not_include_sensitive_key_or_value():
    from review_harness.domain.provider import DuplicateJSONKeyError, strict_json

    with pytest.raises(DuplicateJSONKeyError) as caught:
        strict_json('{"secret-name":"secret-value","secret-name":"other"}')
    assert str(caught.value) == "Duplicate JSON key"


def test_c11_quality_baseline_preserves_actual_wrong_supported_judgment():
    import json
    from pathlib import Path

    data = json.loads(
        (
            Path(__file__).parents[1] / "fixtures/c11-known-quality-failure.json"
        ).read_bytes()
    )
    assert data["classification"] == "KNOWN_MODEL_QUALITY_FAILURE"
    original = data["originalFinding"]
    assert original["claim"] == data["observation"]["publishedClaim"]
    assert data["originalCandidate"]["claim"] == original["claim"]
    assert "opposite null handling" in original["claim"]
    judgment = next(
        v
        for v in data["record"]["submitted"]["validations"]
        if v["candidateHandle"] == "c11"
    )
    assert judgment["disposition"] == "VERIFIED"
    assert judgment.get("correctedCandidate") is None
    assert all(c["outcome"] == "SUPPORTED" for c in judgment["factChecks"])
    assert "return text.length();" in data["source"]
    assert 'Objects.requireNonNull(text, "text")' in data["source"]
    assert (
        data["source"].splitlines()[12].strip()
        == "/** Null is invalid and must throw NullPointerException. */"
    )
    assert any(
        b["candidateHandle"] == "c11" and b["sourceQuote"]
        for b in data["record"]["bindings"]
    )

    from review_harness.domain.model import sha256

    assert any(
        b["candidateHandle"] == "c11"
        and b["authorizedSourceRef"]["sourceType"] == "CODE"
        and b["authorizedSourceRef"]["sha256"] == sha256(data["source"].encode())
        for b in data["record"]["bindings"]
    )
