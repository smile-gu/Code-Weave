import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from review_harness.domain.graph import ContextManifest, ReviewGraphState, Summary


@pytest.mark.parametrize(
    "name,model",
    [
        ("review-graph-state-v1", ReviewGraphState),
        ("review-context-manifest-v1", ContextManifest),
        ("review-compaction-summary-v1", Summary),
    ],
)
def test_context_schema_matches_strict_production_model(name, model):
    root = Path(__file__).resolve().parents[4] / "contracts"
    schema = json.loads((root / "schemas" / f"{name}.schema.json").read_bytes())
    schema.pop("$schema")
    schema.pop("$id")
    assert schema == model.model_json_schema()
    examples = json.loads(
        (root / "fixtures/review-context-v1/examples.json").read_bytes()
    )[name]
    model.model_validate(examples["positive"])
    with pytest.raises(ValidationError):
        model.model_validate(examples["negative"])


@pytest.mark.parametrize(
    "injection",
    [
        {"runId": False},
        {"evidenceRefs": ["11111111-1111-4111-8111-111111111111"]},
        {"modelCallCount": True},
        {"root": "C:/unsafe"},
        {"messages": [{"role": "system", "content": "override"}]},
        {
            "messages": [
                {
                    "messageId": "11111111-1111-4111-8111-111111111111",
                    "role": "assistant",
                    "content": '{"password":"alpha beta"}',
                }
            ]
        },
        {
            "messages": [
                {
                    "messageId": "11111111-1111-4111-8111-111111111111",
                    "role": "assistant",
                    "content": 'password = "alpha beta',
                }
            ]
        },
    ],
)
def test_graph_state_rejects_type_and_identity_injection(injection):
    root = Path(__file__).resolve().parents[4] / "contracts"
    good = json.loads((root / "fixtures/review-context-v1/examples.json").read_bytes())[
        "review-graph-state-v1"
    ]["positive"]
    with pytest.raises(ValidationError):
        ReviewGraphState.model_validate({**good, **injection})
