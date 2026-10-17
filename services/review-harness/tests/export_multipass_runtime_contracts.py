"""State4 and HTTP1.1 runtime exports, without rewriting historical contracts."""

import json
from pathlib import Path

from review_harness.domain.multipass_state import (
    MultipassContextManifest,
    MultipassState,
    PassInvestigation,
    PassLocalValidationBatch,
)
from review_harness.domain.pass_fact import PassFact, PassProgress
from review_harness.domain.runtime_wire import RunEventV11, RunStatusV11
from review_harness.runtime.dedup import DedupDecision

ROOT = Path(__file__).resolve().parents[3] / "contracts/schemas"
MODELS = {
    "review-graph-state-v4": MultipassState,
    "review-context-manifest-v1.3": MultipassContextManifest,
    "review-pass-fact-v1": PassFact,
    "review-pass-progress-v1": PassProgress,
    "review-harness-status-v1.1": RunStatusV11,
    "review-harness-event-v1.1": RunEventV11,
    "review-local-investigation-v2": PassInvestigation,
    "review-local-validation-batch-v2": PassLocalValidationBatch,
    "review-dedup-decision-v1": DedupDecision,
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
