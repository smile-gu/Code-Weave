from types import SimpleNamespace
from uuid import uuid4

import pytest

from review_harness.application.source_submission import compile_sources
from review_harness.domain.handles import HandleRegistry
from review_harness.domain.source_submission import SourceInvestigation
from review_harness.domain.submission import SubmissionIssue


def intake():
    return {
        "submissionVersion": "SOURCE_V1",
        "decision": "COMPLETE",
        "candidates": [
            {
                "passId": "correctness",
                "category": "Null handling",
                "title": "Null input throws",
                "claim": "Null input throws",
                "severity": "MEDIUM",
                "confidence": 0.9,
                "claimKind": "STATIC_CORRECTNESS",
                "suggestion": "Handle null",
                "reasoningSummary": {
                    "rule": "Handle null",
                    "trigger": "x is null",
                    "executionPath": "x.intValue()",
                    "assumptions": [],
                    "counterexamples": ["Caller may reject null"],
                    "conclusion": "Null throws",
                },
                "location": {"sourceHandle": "s1", "startLine": 12, "endLine": 12},
                "supportingSources": [
                    {"sourceHandle": "s2", "startLine": 12, "endLine": 12}
                ],
            }
        ],
        "checkedScope": ["src/A.java"],
        "scopeKind": "FINAL_FILES",
        "coverage": "FULL",
        "limitations": ["Static review"],
        "incompleteReasons": [],
    }


def registry():
    result = HandleRegistry(runId=str(uuid4()), snapshotId=str(uuid4()))
    for side in ("final", "diff"):
        result, _ = result.register_source(
            {
                "sourceType": "CODE",
                "path": "src/A.java",
                "side": side,
                "sha256": "sha256:" + "a" * 64,
                "startLine": 8,
                "endLine": 17,
            },
            "FULL",
            "NONE",
            [[8, 17]],
        )
    return result


def test_platform_compiles_location_and_support_without_model_foreign_keys():
    value = compile_sources(
        SimpleNamespace(handleRegistry=registry()),
        SourceInvestigation.model_validate(intake()),
    )
    candidate = value.candidates[0]
    assert candidate.evidenceLinks[0].key == "n1"
    assert len(value.evidenceCandidates[0].selections) == 2
    assert candidate.codeSelections[0].sourceHandle == "s1"
    schema = SourceInvestigation.model_json_schema()
    assert (
        "evidenceLinks" not in str(schema)
        and "evidenceCandidates" not in schema["properties"]
    )


@pytest.mark.parametrize("field,value", [("sourceHandle", "s99"), ("endLine", 18)])
def test_source_intake_still_rejects_unknown_or_unobserved_positions(field, value):
    data = intake()
    data["candidates"][0]["location"][field] = value
    with pytest.raises(SubmissionIssue):
        compile_sources(
            SimpleNamespace(handleRegistry=registry()),
            SourceInvestigation.model_validate(data),
        )


def test_duplicate_support_is_normalized_without_changing_meaning():
    data = intake()
    data["candidates"][0]["supportingSources"].append(data["candidates"][0]["location"])
    result = compile_sources(
        SimpleNamespace(handleRegistry=registry()),
        SourceInvestigation.model_validate(data),
    )
    assert len(result.evidenceCandidates[0].selections) == 2


def test_previous_pass_sources_are_not_adopted():
    from review_harness.domain.multipass_state import MultipassRegistry, MultipassState

    old = registry().model_dump()
    old["activePass"] = "security"
    for binding in old["sources"]:
        binding["passId"] = "correctness"
    state = MultipassState.model_construct(
        activePass="security", handleRegistry=MultipassRegistry.model_validate(old)
    )
    value = intake()
    value["candidates"][0]["passId"] = "security"
    with pytest.raises(SubmissionIssue, match="EVIDENCE_REFERENCE_INVALID") as error:
        compile_sources(state, SourceInvestigation.model_validate(value))
    assert error.value.reason == "SOURCE_NOT_OBSERVED_IN_PASS"


def test_exported_source_schema_matches_production():
    import json

    from jsonschema import Draft202012Validator

    from tests.export_position_contracts import ROOT

    schema = json.loads(
        (ROOT / "review-source-investigation-v1.schema.json").read_bytes()
    )
    Draft202012Validator.check_schema(schema)
    assert {
        k: v for k, v in schema.items() if k not in {"$id", "$schema"}
    } == SourceInvestigation.model_json_schema()
