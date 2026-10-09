import json
import re
from pathlib import Path

import pytest

from review_harness.domain.model import Failure
from review_harness.infrastructure.persistence.store import RunStore

SCHEMAS = Path(__file__).resolve().parents[4] / "contracts" / "schemas"


def schema(name):
    return json.loads(
        (SCHEMAS / f"review-harness-{name}-v1.schema.json").read_text(encoding="utf-8")
    )


def assert_object_contract(value, contract):
    """Check object fields and frozen scalar definitions directly from repo schemas."""
    assert isinstance(value, dict)
    assert set(contract.get("required", [])) <= set(value)
    properties = contract["properties"]
    if contract.get("additionalProperties") is False:
        assert set(value) <= set(properties)
    for name, item in value.items():
        field = properties[name]
        if "oneOf" in field:
            field = next(
                option
                for option in field["oneOf"]
                if (item is None) == (option.get("type") == "null")
            )
        if "$ref" in field:
            field = schema("common")["$defs"][field["$ref"].split("/")[-1]]
        if item is None:
            assert field.get("type") == "null"
            continue
        if "const" in field:
            assert item == field["const"]
        if "enum" in field:
            assert item in field["enum"]
        if "pattern" in field:
            assert isinstance(item, str) and re.fullmatch(field["pattern"], item)
        if field.get("type") == "object" and "properties" in field:
            assert_object_contract(item, field)
        if field.get("type") == "array":
            assert isinstance(item, list)
        if field.get("type") == "integer":
            assert type(item) is int and item >= field.get("minimum", item)
        if field.get("type") == "boolean":
            assert type(item) is bool
        if "minLength" in field:
            assert len(item) >= field["minLength"]
        if "maxLength" in field:
            assert len(item) <= field["maxLength"]


@pytest.mark.parametrize(
    "outcome",
    ["success", "pending_cancel", "running_cancel", "failure", "uncertain_release"],
)
async def test_all_lifecycle_envelopes_follow_frozen_schemas(
    database, payload, outcome
):
    store = RunStore(database)
    await store.create_run(payload)
    if outcome == "pending_cancel":
        await store.cancel_pending(payload["runId"])
    else:
        await store.start_run(payload["runId"])
        if outcome == "success":
            await store.complete_success(payload["runId"])
        elif outcome == "running_cancel":
            await store.request_cancel(payload["runId"])
            await store.complete_cancel(payload["runId"])
        elif outcome == "failure":
            await store.complete_failure_safe(
                payload["runId"], Failure.make("REVIEW_EXECUTION_FAILED")
            )
        else:
            await store.fail_cleanup_uncertain(
                payload["runId"], Failure.make("REVIEW_RESOURCE_CLEANUP_FAILED")
            )
            await store.confirm_resources_released(
                payload["runId"], "REVIEW_RESOURCES_CONFIRMED"
            )
    run = await store.get_run(payload["runId"])
    assert_object_contract(run.wire(), schema("run-status"))
    event_schema = schema("event")
    for event in await store.read_events(run.run_id, 0, 10):
        assert_object_contract(event, event_schema)
        variant = next(
            option["properties"]
            for option in event_schema["oneOf"]
            if option["properties"]["eventType"]["const"] == event["eventType"]
        )
        assert_object_contract(event["payload"], variant["payload"])
    if outcome == "success":
        result, _ = await store.get_result(run.run_id)
        assert_object_contract(json.loads(result), schema("result"))
