import copy
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError

from tests.export_position_contracts import MODELS, ROOT
from tests.integration.test_multipass_contracts import plan_data


@pytest.mark.parametrize("name,model", MODELS.items())
def test_position_schemas_are_explicit_strict_additions(name, model):
    schema = json.loads((ROOT / (name + ".schema.json")).read_bytes())
    Draft202012Validator.check_schema(schema)
    assert {
        k: v for k, v in schema.items() if k not in {"$schema", "$id"}
    } == model.model_json_schema()
    if "state" in name:
        fixtures = (
            Path(__file__).resolve().parents[4]
            / "contracts/fixtures/review-local-handles-v1/examples.json"
        )
        example = json.loads(fixtures.read_bytes())["review-graph-state-v3"]["positive"]
        example.update(
            stateVersion="6.0" if name.endswith("6") else "5.0",
            validationProtocol="POSITION_V1",
            validationRecordRefs=[],
        )
        if name.endswith("6"):
            example.update(
                passId="correctness",
                activePass="correctness",
                reviewPlan=plan_data(),
                runStartedAt="2026-10-16T00:00:00.000000Z",
            )
            example["handleRegistry"]["activePass"] = "correctness"
            for source in example["handleRegistry"]["sources"]:
                source["passId"] = "correctness"
    else:
        example = {
            "validationVersion": "POSITION_V1",
            "validations": [
                {
                    "candidateHandle": "c1",
                    "disposition": "REJECTED",
                    "validationSummary": {
                        "basis": "Benign",
                        "counterexampleChecked": "No defect",
                        "counterexampleHolds": False,
                        "assumptionsResolved": True,
                    },
                }
            ],
        }
    Draft202012Validator(schema).validate(example)
    model.model_validate(example)
    bad = copy.deepcopy(example)
    bad["validationProtocol" if "state" in name else "validationVersion"] = "UNKNOWN"
    assert list(Draft202012Validator(schema).iter_errors(bad))
    with pytest.raises(ValidationError):
        model.model_validate(bad)
