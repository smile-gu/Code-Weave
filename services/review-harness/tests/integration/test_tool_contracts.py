import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from review_harness.domain.artifact import Artifact, ContentPage
from review_harness.domain.tools import PARAMETERS, ToolEnvelope

MODELS = {
    "review-artifact-metadata-v1": Artifact,
    "review-artifact-content-page-v1": ContentPage,
    "review-tool-envelope-v1": ToolEnvelope,
    **{
        "review-tool-" + name.replace("_", "-") + "-parameters-v1": model
        for name, model in PARAMETERS.items()
    },
}


@pytest.mark.parametrize("name,model", MODELS.items())
def test_machine_schemas_are_derived_from_production_validation(name, model):
    root = Path(__file__).resolve().parents[4] / "contracts/schemas"
    stored = json.loads((root / (name + ".schema.json")).read_bytes())
    stored.pop("$schema")
    stored.pop("$id")
    assert stored == model.model_json_schema(by_alias=True)
    fixture = json.loads(
        (root.parent / "fixtures/review-tools-v1/examples.json").read_bytes()
    )[name]
    model.model_validate(fixture["positive"])
    with pytest.raises(ValidationError):
        model.model_validate(fixture["negative"])


@pytest.mark.parametrize(
    "name,value",
    [
        ("read_file", {"path": "src/Demo.java"}),
        ("search_code", {"query": "literal.*"}),
        ("glob_files", {"pattern": "**/*.java"}),
        ("git_diff", {}),
        ("read_artifact", {"artifactId": "11111111-1111-4111-8111-111111111111"}),
    ],
)
def test_tool_parameter_examples_and_identity_injection_rejection(name, value):
    PARAMETERS[name].model_validate(value)
    with pytest.raises(ValidationError):
        PARAMETERS[name].model_validate({**value, "runId": "forged"})
