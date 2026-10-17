"""Export only new versioned contracts; never overwrite batch 5 fixtures."""

import copy
import json
from pathlib import Path

from review_harness.api.dto import CreateRunV11
from review_harness.domain.multipass import (
    MultipassCapabilities,
    MultipassExecutionContract,
    MultipassPassResult,
    ReviewPlan,
    ReviewResultV3,
)
from tests.integration.test_multipass_contracts import plan_data, result_with_finding

ROOT = Path(__file__).resolve().parents[3] / "contracts"
MODELS = {
    "review-plan-v1": ReviewPlan,
    "review-pass-result-v3": MultipassPassResult,
    "review-execution-contract-v2": MultipassExecutionContract,
    "review-capabilities-v2": MultipassCapabilities,
    "review-harness-create-run-v1.1": CreateRunV11,
    "review-harness-result-v3": ReviewResultV3,
}


def examples():
    contract = {"executionMode": "REAL", "resultVersion": "3.0"}
    result = result_with_finding()
    return {
        "review-plan-v1": plan_data(),
        "review-pass-result-v3": result["passResults"][1],
        "review-execution-contract-v2": contract,
        "review-capabilities-v2": {
            "protocolVersion": "1.1",
            "configuredContract": contract,
            "planVersions": ["1.0"],
            "feeReservation": "NOT_READY",
            "status": "NOT_READY",
            "failureCodes": ["MULTIPASS_RUNTIME_NOT_READY"],
        },
        "review-harness-create-run-v1.1": {
            "protocolVersion": "1.1",
            "runId": result["runId"],
            "codingSessionId": result["resultId"],
            "reviewThreadId": result["snapshotId"],
            "snapshot": {
                "snapshotId": result["snapshotId"],
                "manifestSha256": result["manifestSha256"],
            },
            "input": {"message": "Review fixed synthetic source."},
            "executionContract": contract,
            "reviewPlan": plan_data(),
        },
        "review-harness-result-v3": result,
    }


if __name__ == "__main__":
    fixtures = {}
    for name, positive in examples().items():
        model = MODELS[name]
        model.model_validate(positive)
        schema = model.model_json_schema()
        schema.update(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "$id": f"https://code-weave.local/schemas/{name}.schema.json",
            }
        )
        (ROOT / "schemas" / (name + ".schema.json")).write_text(
            json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        negative = copy.deepcopy(positive)
        negative["unexpected"] = True
        fixtures[name] = {"positive": positive, "negative": negative}
    folder = ROOT / "fixtures/review-multipass-v1"
    folder.mkdir(exist_ok=True)
    (folder / "examples.json").write_text(
        json.dumps(fixtures, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
