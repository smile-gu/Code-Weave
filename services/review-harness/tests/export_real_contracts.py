"""Explicit developer export; production never generates or repairs contracts."""

import copy
import json
from pathlib import Path

from review_harness.domain.graph import (
    CompatibleContextManifest,
    ContextManifest,
    ExpandedContextManifest,
    ReviewGraphState,
    Summary,
)
from review_harness.domain.handles import (
    HandleRegistry,
    LocalInvestigation,
    LocalValidationBatch,
)
from review_harness.domain.model_call import (
    ExecutionContract,
    ModelCall,
    ReviewCapabilities,
)
from review_harness.domain.provider import (
    ProviderCallMapping,
    ProviderRequest,
    ProviderResponse,
)
from review_harness.domain.real_graph import (
    HandleReviewGraphState,
    RealReviewGraphState,
)
from review_harness.domain.review import ReviewResult
from review_harness.domain.tools import PARAMETERS
from tests.integration.test_deepseek_adapter import response
from tests.integration.test_real_contracts import incomplete_result

ROOT = Path(__file__).resolve().parents[3] / "contracts"
MODELS = {
    "review-provider-request-v1": ProviderRequest,
    "review-provider-response-v1": ProviderResponse,
    "review-provider-call-mapping-v1": ProviderCallMapping,
    "review-model-call-v1": ModelCall,
    "review-execution-contract-v1": ExecutionContract,
    "review-capabilities-v1": ReviewCapabilities,
    "review-harness-result-v2": ReviewResult,
    "review-graph-state-v2": RealReviewGraphState,
}
LOCAL_MODELS = {
    "review-graph-state-v3": HandleReviewGraphState,
    "review-handle-registry-v1": HandleRegistry,
    "review-local-investigation-v1": LocalInvestigation,
    "review-local-validation-v1": LocalValidationBatch,
}


if __name__ == "__main__":
    schemas = {
        **MODELS,
        **LOCAL_MODELS,
        "review-context-manifest-v1": ContextManifest,
        "review-context-manifest-v1.1": CompatibleContextManifest,
        "review-context-manifest-v1.2": ExpandedContextManifest,
        "review-graph-state-v1": ReviewGraphState,
        "review-compaction-summary-v1": Summary,
    }
    for name, model in schemas.items():
        value = model.model_json_schema()
        value["$schema"] = "https://json-schema.org/draft/2020-12/schema"
        value["$id"] = f"https://code-weave.local/schemas/{name}.schema.json"
        (ROOT / "schemas" / f"{name}.schema.json").write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    identity = "11111111-1111-4111-8111-111111111111"
    ref = {
        "artifactId": identity,
        "sha256": "sha256:" + "a" * 64,
        "byteSize": 256,
        "coverage": "FULL",
        "redactionState": "NONE",
    }
    intent = {
        "modelCallId": identity,
        "runId": identity,
        "snapshotId": identity,
        "purpose": "INVESTIGATION",
        "provider": "DEEPSEEK",
        "model": "deepseek-flash",
        "requestDigest": "sha256:" + "a" * 64,
        "manifestRef": ref,
        "meterVersion": "v4.1-2cba9e42-v1",
        "estimatedInputTokens": 17,
        "reservedOutputTokens": 4096,
        "status": "PREPARED",
        "createdAt": "2026-10-10T14:00:00.000000Z",
        "updatedAt": "2026-10-10T14:00:00.000000Z",
        "usage": None,
        "failureCode": None,
        "remoteOutcomeUnknown": False,
    }
    examples = {
        "review-provider-request-v1": {
            "model": "deepseek-flash",
            "messages": [{"role": "user", "content": "Reply OK."}],
            "thinking": {"type": "disabled"},
            "stream": False,
            "max_tokens": 4096,
            "tools": [
                {
                    "type": "function",
                    "function": {
                        "name": "read_file",
                        "description": "Read fixed source",
                        "parameters": PARAMETERS["read_file"].model_json_schema(),
                    },
                }
            ],
            "tool_choice": "auto",
        },
        "review-provider-response-v1": response(),
        "review-provider-call-mapping-v1": {
            "modelCallId": identity,
            "toolCallId": identity,
            "providerToolCallId": "provider-call-1",
        },
        "review-model-call-v1": intent,
        "review-execution-contract-v1": {
            "executionMode": "REAL",
            "resultVersion": "2.0",
        },
        "review-capabilities-v1": {
            "protocolVersion": "1.0",
            "configuredContract": {"executionMode": "REAL", "resultVersion": "2.0"},
            "status": "NOT_READY",
            "provider": "DEEPSEEK",
            "model": "deepseek-flash",
            "meterVersion": "v4.1-2cba9e42-v1",
            "failureCodes": ["PROVIDER_CREDENTIAL_UNAVAILABLE"],
        },
        "review-harness-result-v2": incomplete_result(),
        "review-graph-state-v2": {
            "stateVersion": "2.0",
            "runId": identity,
            "reviewThreadId": identity,
            "snapshotId": identity,
            "manifestSha256": "sha256:" + "a" * 64,
            "investigationId": identity,
            "userGoalDigest": "sha256:" + "a" * 64,
            "scopeDigest": "sha256:" + "a" * 64,
            "toolSetDigest": "sha256:" + "a" * 64,
        },
    }
    registry = {
        "runId": identity,
        "snapshotId": identity,
        "sources": [
            {
                "sourceHandle": "s1",
                "source": {
                    "sourceType": "CODE",
                    "path": "src/Demo.java",
                    "side": "final",
                    "sha256": "sha256:" + "a" * 64,
                    "startLine": 1,
                    "endLine": 1,
                },
                "observedRanges": [[1, 1]],
                "coverage": "FULL",
                "redactionState": "NONE",
            }
        ],
    }
    selection = {"sourceHandle": "s1", "startLine": 1, "endLine": 1}
    candidate = {
        "passId": "correctness",
        "category": "Static correctness",
        "title": "A hypothesis",
        "claim": "A hypothesis requires verification",
        "severity": "LOW",
        "confidence": 0.5,
        "evidenceLinks": [{"kind": "NEW", "key": "n1"}],
        "codeSelections": [selection],
        "reasoningSummary": {
            "rule": "Check original material",
            "trigger": "Read code",
            "executionPath": "Declaration",
            "assumptions": [],
            "counterexamples": ["The behavior may be intended"],
            "conclusion": "Unverified",
        },
        "suggestion": "Verify against requirements",
        "claimKind": "STATIC_CORRECTNESS",
    }
    examples.update(
        {
            "review-handle-registry-v1": registry,
            "review-graph-state-v3": {
                **examples["review-graph-state-v2"],
                "stateVersion": "3.0",
                "handleRegistry": registry,
            },
            "review-local-investigation-v1": {
                "decision": "COMPLETE",
                "evidenceCandidates": [
                    {
                        "localKey": "n1",
                        "evidenceType": "CODE",
                        "selections": [selection],
                        "coverage": "FULL",
                        "summary": "Observed declaration",
                    }
                ],
                "candidates": [candidate],
                "checkedScope": ["src/Demo.java"],
                "scopeKind": "FINAL_FILES",
                "coverage": "FULL",
                "limitations": ["Only this declaration was observed"],
                "incompleteReasons": [],
            },
            "review-local-validation-v1": {
                "validations": [
                    {
                        "candidateHandle": "c1",
                        "disposition": "REJECTED",
                        "validationSummary": {
                            "basis": "Insufficient support",
                            "counterexampleChecked": "Checked intended behavior",
                            "counterexampleHolds": True,
                            "assumptionsResolved": False,
                        },
                    }
                ]
            },
        }
    )
    fixtures = {}
    for name, positive in examples.items():
        {**MODELS, **LOCAL_MODELS}[name].model_validate(positive)
        negative = copy.deepcopy(positive)
        target = (
            negative["diagnostics"] if name == "review-harness-result-v2" else negative
        )
        target["unexpected"] = "reject"
        if name == "review-provider-request-v1":
            target.pop("unexpected")
            negative["tools"][0]["function"]["parameters"] = PARAMETERS[
                "glob_files"
            ].model_json_schema()
        fixtures[name] = {"positive": positive, "negative": negative}
    for folder, models in (
        ("review-real-v1", MODELS),
        ("review-local-handles-v1", LOCAL_MODELS),
    ):
        destination = ROOT / "fixtures" / folder
        destination.mkdir(exist_ok=True)
        (destination / "examples.json").write_text(
            json.dumps(
                {name: fixtures[name] for name in models}, ensure_ascii=False, indent=2
            )
            + "\n",
            encoding="utf-8",
        )
