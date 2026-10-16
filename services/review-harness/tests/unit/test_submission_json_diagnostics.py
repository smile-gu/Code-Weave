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
        '{"password":"diagnostic-secret","password":"duplicate"}',
        '{"password":"diagnostic-secret","value":NaN}',
        '"' + "x" * 65536 + '"',
    ],
    ids=["duplicate-key", "non-finite", "byte-limit"],
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
