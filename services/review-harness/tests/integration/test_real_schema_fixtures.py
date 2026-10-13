import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError

from tests.export_real_contracts import LOCAL_MODELS, MODELS


@pytest.mark.parametrize("name,model", {**MODELS, **LOCAL_MODELS}.items())
def test_shared_schemas_are_current_and_reject_negative_fixtures(name, model):
    root = Path(__file__).resolve().parents[4] / "contracts"
    schema = json.loads((root / "schemas" / f"{name}.schema.json").read_bytes())
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    expected = model.model_json_schema()
    assert {
        key: value for key, value in schema.items() if key not in {"$id", "$schema"}
    } == expected
    folder = "review-local-handles-v1" if name in LOCAL_MODELS else "review-real-v1"
    fixture = json.loads((root / "fixtures" / folder / "examples.json").read_bytes())[
        name
    ]
    validator.validate(fixture["positive"])
    model.model_validate(fixture["positive"])
    assert list(validator.iter_errors(fixture["negative"]))
    with pytest.raises(ValidationError):
        model.model_validate(fixture["negative"])
