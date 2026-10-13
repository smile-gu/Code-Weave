from uuid import uuid4

import pytest

from review_harness.domain.model import HarnessError, utc_now
from review_harness.domain.model_call import ModelCall
from review_harness.infrastructure.persistence.model_calls import ModelCallStore
from review_harness.infrastructure.persistence.store import RunStore


@pytest.mark.parametrize("lost_ack", [False, True])
async def test_intent_is_confirmed_and_never_replayable_after_dispatch(
    database, payload, lost_ack
):
    from review_harness.infrastructure.persistence.store import commit

    async def commit_boundary(connection):
        await commit(connection)
        if lost_ack:
            raise OSError("offline commit acknowledgement lost")

    runs = RunStore(database)
    await runs.create_run(payload)
    await runs.start_run(payload["runId"])
    at = utc_now()
    intent = ModelCall.model_validate(
        {
            "modelCallId": str(uuid4()),
            "runId": payload["runId"],
            "snapshotId": payload["snapshot"]["snapshotId"],
            "purpose": "INVESTIGATION",
            "provider": "DEEPSEEK",
            "model": "deepseek-flash",
            "requestDigest": "sha256:" + "a" * 64,
            "manifestRef": {
                "artifactId": str(uuid4()),
                "sha256": "sha256:" + "b" * 64,
                "byteSize": 200,
                "coverage": "FULL",
                "redactionState": "NONE",
            },
            "meterVersion": "v4.1-2cba9e42-v1",
            "estimatedInputTokens": 17,
            "reservedOutputTokens": 4096,
            "status": "PREPARED",
            "createdAt": at,
            "updatedAt": at,
            "usage": None,
            "failureCode": None,
            "remoteOutcomeUnknown": False,
        }
    )
    calls = ModelCallStore(database, commit_transaction=commit_boundary)
    await calls.prepare(intent)
    assert (
        "policyVersion"
        not in (await calls.get(intent.runId, intent.modelCallId)).record_dict()
    )
    assert await calls.get(intent.runId, intent.modelCallId) == intent
    dispatched = await calls.dispatch(intent.runId, intent.modelCallId)
    assert dispatched.status == "DISPATCHING" and dispatched.remoteOutcomeUnknown
    with pytest.raises(HarnessError, match="PROVIDER_CALL_ALREADY_DISPATCHED"):
        await calls.dispatch(intent.runId, intent.modelCallId)
    await calls.recover(local_resources_released=lambda _: False)
    assert (await calls.get(intent.runId, intent.modelCallId)).status == "DISPATCHING"
    await calls.recover(local_resources_released=lambda _: True)
    closed = await calls.get(intent.runId, intent.modelCallId)
    assert closed.status == "LOCAL_CLOSED" and closed.remoteOutcomeUnknown
    assert closed.failureCode == "PROVIDER_CALL_INTERRUPTED"
    assert "policyVersion" not in closed.record_dict()
    assert len(await calls.list_for_run(payload["runId"])) == 1
    from review_harness.domain.provider import ProviderUsage
    from review_harness.infrastructure.persistence.database import Database

    drift_intent = intent.model_copy(update={"modelCallId": str(uuid4())})
    await calls.prepare(drift_intent)
    await calls.dispatch(drift_intent.runId, drift_intent.modelCallId)
    await calls.returned(
        drift_intent.runId,
        drift_intent.modelCallId,
        ProviderUsage(prompt_tokens=40000, completion_tokens=1, total_tokens=40001),
    )
    await calls.close(
        drift_intent.runId, drift_intent.modelCallId, "PROVIDER_TOKEN_METER_DRIFT"
    )
    rebuilt = Database(str(database.engine.url))
    try:
        assert await ModelCallStore(rebuilt).has_meter_drift()
        await ModelCallStore(rebuilt).recover(local_resources_released=lambda _: True)
        assert await ModelCallStore(rebuilt).has_meter_drift()
    finally:
        await rebuilt.close()
