"""Versioned fee wire exports; old call and price-independent contracts are untouched."""

import copy
import json
from pathlib import Path

from review_harness.domain.fees import (
    CallReservation,
    FeeBudget,
    PriceProfile,
    PriceProfileView,
)
from review_harness.domain.model_call import FeeModelCall
from tests.integration.test_fee_ledger import profile
from tests.integration.test_multipass_contracts import plan_data

ROOT = Path(__file__).resolve().parents[3] / "contracts"
PUBLIC = {
    "review-price-profile-v1": PriceProfile,
    "review-price-profile-view-v1": PriceProfileView,
    "review-model-call-v2": FeeModelCall,
}
INTERNAL = {
    "review-fee-budget-v1": FeeBudget,
    "review-call-reservation-v1": CallReservation,
}
MODELS = {**PUBLIC, **INTERNAL}


def examples():
    price = profile()
    plan = plan_data()
    plan["feeAuthorization"].update(
        priceProfileId=price.priceProfileId, priceProfileDigest=price.digest
    )
    identity = "11111111-1111-4111-8111-111111111111"
    call = {
        "callVersion": "2.0",
        "passId": "correctness",
        "modelCallId": identity,
        "runId": identity,
        "snapshotId": identity,
        "purpose": "INVESTIGATION",
        "provider": "DEEPSEEK",
        "model": "deepseek-flash",
        "requestDigest": "sha256:" + "a" * 64,
        "manifestRef": {
            "artifactId": identity,
            "sha256": "sha256:" + "b" * 64,
            "byteSize": 100,
            "coverage": "FULL",
            "redactionState": "NONE",
        },
        "meterVersion": "v4.1-2cba9e42-v1",
        "estimatedInputTokens": 17,
        "reservedOutputTokens": 4096,
        "createdAt": "2026-10-13T09:00:00.000000Z",
        "updatedAt": "2026-10-13T09:00:00.000000Z",
        "status": "PREPARED",
        "usage": None,
        "failureCode": None,
        "remoteOutcomeUnknown": False,
        "policyVersion": "deepseek-compatible-budget-v2",
        "conservativeInputTokens": 1041,
        "uncertaintyReserveTokens": 1024,
    }
    budget = {
        "runId": identity,
        "reviewPlan": plan,
        "priceProfile": price.model_dump(),
        "runStartedAt": "2026-10-13T09:00:00.000000Z",
        "activePhase": "correctness",
        "phaseStartedAt": "2026-10-13T09:00:00.000000Z",
        "revision": 2,
        "spentMicros": 0,
        "reservedMicros": 34850,
        "overrunMicros": 0,
        "reservedCalls": 1,
        "modelCalls": 0,
        "phaseCalls": 0,
        "dedupCalls": 0,
        "stopReason": None,
    }
    return {
        "review-price-profile-v1": price.model_dump(),
        "review-price-profile-view-v1": {
            "priceProfile": price.model_dump(),
            "digest": price.digest,
        },
        "review-model-call-v2": call,
        "review-fee-budget-v1": budget,
        "review-call-reservation-v1": {
            "runId": identity,
            "modelCallId": identity,
            "passId": "correctness",
            "state": "RESERVED",
            "reservedMicros": 34850,
            "actualMicros": None,
            "overrunMicros": 0,
            "revision": 1,
        },
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
    target = ROOT / "fixtures/review-fee-v1"
    target.mkdir(exist_ok=True)
    for file, models in (
        ("examples.json", PUBLIC),
        ("internal-examples.json", INTERNAL),
    ):
        (target / file).write_text(
            json.dumps(
                {name: fixtures[name] for name in models}, ensure_ascii=False, indent=2
            )
            + "\n",
            encoding="utf-8",
        )
