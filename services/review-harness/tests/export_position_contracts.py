"""Add position-only schemas; never regenerate a historical schema in place."""

import json
from pathlib import Path

from review_harness.domain.position import (
    PassPositionValidationBatch,
    PositionMultipassState,
    PositionReviewState,
    PositionValidationBatch,
)

ROOT = Path(__file__).resolve().parents[3] / "contracts/schemas"
MODELS = {
    "review-graph-state-v5": PositionReviewState,
    "review-graph-state-v6": PositionMultipassState,
    "review-position-validation-v1": PositionValidationBatch,
    "review-position-validation-multipass-v1": PassPositionValidationBatch,
}


if __name__ == "__main__":
    for name, model in MODELS.items():
        schema = model.model_json_schema()
        schema.update(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "$id": f"https://code-weave.local/schemas/{name}.schema.json",
            }
        )
        (ROOT / (name + ".schema.json")).write_text(
            json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
