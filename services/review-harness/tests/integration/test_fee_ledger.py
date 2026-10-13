"""Real SQLite and send-intent seam; no paid transport."""

import asyncio
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.api.app import create_app
from review_harness.domain.fees import PriceProfile
from review_harness.domain.model import Failure, HarnessError, canonical, utc_now
from review_harness.domain.model_call import ModelCall
from review_harness.domain.real_graph import RealReviewGraphState
from review_harness.domain.snapshot import WorkCancelled
from review_harness.infrastructure.artifacts import ArtifactService
from review_harness.infrastructure.checkpoints import Checkpoints
from review_harness.infrastructure.persistence.fees import FeeLedger
from review_harness.infrastructure.persistence.model_calls import ModelCallStore
from review_harness.infrastructure.persistence.store import RunStore
from review_harness.runtime.context import ContextEngine
from review_harness.runtime.deepseek import DeepSeekAdapter
from tests.integration.test_lifecycle import client_for, settings_for
from tests.integration.test_multipass_contracts import plan_data


def profile():
    return PriceProfile.model_validate(
        {
            "profileVersion": "1.0",
            "priceProfileId": "offline-deepseek-profile-v1",
            "provider": "DEEPSEEK",
            "model": "deepseek-flash",
            "currency": "CNY",
            "sourceUrl": "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/",
            "sourceDocumentSha256": "sha256:" + "a" * 64,
            "verifiedAt": "2026-10-13T00:00:00.000000Z",
            "expiresAt": "2099-01-01T00:00:00.000000Z",
            "inputCnyPerMillion": "2",
            "outputCnyPerMillion": "8",
        }
    )


async def paid_run(database, payload, amount="8"):
    value = profile()
    plan = plan_data()
    plan["feeAuthorization"].update(
        authorizationId=str(uuid4()),
        maxAmount=amount,
        confirmedAt=utc_now(),
        priceProfileId=value.priceProfileId,
        priceProfileDigest=value.digest,
    )
    payload.update(
        protocolVersion="1.1",
        executionContract={"executionMode": "REAL", "resultVersion": "3.0"},
        reviewPlan=plan,
    )
    store = RunStore(database)
    await store.create_run(payload)
    await store.start_run(payload["runId"])
    ledger = FeeLedger(database)
    await ledger.initialize(payload["runId"], value)
    await ledger.begin_phase(payload["runId"], "correctness")
    return store, ledger


def intent(payload):
    at = utc_now()
    return ModelCall.model_validate(
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
                "byteSize": 100,
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
            "policyVersion": "deepseek-compatible-budget-v2",
            "conservativeInputTokens": 1041,
            "uncertaintyReserveTokens": 1024,
        }
    )


async def test_unknown_finances_do_not_fabricate_resource_uncertainty(
    database, payload
):
    import copy

    from review_harness.domain.model import Failure
    from review_harness.domain.provider import ProviderUsage

    runs, ledger = await paid_run(database, payload)
    calls = ModelCallStore(database)
    first = await calls.prepare(intent(payload))
    await calls.dispatch(first.runId, first.modelCallId)
    await calls.close(first.runId, first.modelCallId, "PROVIDER_CALL_INTERRUPTED")
    await runs.complete_failure_safe(
        first.runId, Failure.make("PROVIDER_CALL_INTERRUPTED")
    )
    assert (await runs.get_run(first.runId)).resource_state == "RELEASED"
    assert (await ledger.get(first.runId)).reservedMicros == 34850

    second_payload = copy.deepcopy(payload)
    second_payload.update(runId=str(uuid4()), codingSessionId=str(uuid4()))
    await paid_run(database, second_payload)
    second = await calls.prepare(intent(second_payload))
    await calls.dispatch(second.runId, second.modelCallId)
    await calls.returned(
        second.runId,
        second.modelCallId,
        ProviderUsage(prompt_tokens=17, completion_tokens=2, total_tokens=19),
    )
    await calls.close(second.runId, second.modelCallId)
    assert (await ledger.get(second.runId)).spentMicros == 50
    assert (await ledger.get(first.runId)).reservedMicros == 34850
    await ledger.audit()


async def test_migration_refuses_to_drop_existing_fee_facts(
    database, payload, tmp_path
):
    import asyncio
    from pathlib import Path

    from alembic import command
    from alembic.config import Config

    await paid_run(database, payload)
    root = Path(__file__).resolve().parents[2]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option(
        "sqlalchemy.url", "sqlite+aiosqlite:///" + (tmp_path / "runs.sqlite").as_posix()
    )
    config.attributes["explicit_url"] = True
    with pytest.raises(RuntimeError, match="Fee facts prevent destructive downgrade"):
        await asyncio.to_thread(command.downgrade, config, "0006_evidence")
    assert await database.schema_ready()


async def test_insufficient_budget_creates_neither_intent_nor_reservation(
    database, payload
):
    _, ledger = await paid_run(database, payload, "0.000001")
    calls = ModelCallStore(database)
    with pytest.raises(HarnessError, match="FEE_BUDGET_EXHAUSTED"):
        await calls.prepare(intent(payload))
    assert await calls.list_for_run(payload["runId"]) == []
    assert (await ledger.get(payload["runId"])).reservedMicros == 0


async def test_usage_settles_reservation_with_call_record_atomically(database, payload):
    from review_harness.domain.provider import ProviderUsage

    _, ledger = await paid_run(database, payload)
    calls = ModelCallStore(database)
    prepared = await calls.prepare(intent(payload))
    before = await ledger.get(payload["runId"])
    assert before.reservedMicros == 34850
    assert prepared.callVersion == "2.0" and prepared.passId == "correctness"
    assert before.modelCalls == 0 and before.reservedCalls == 1
    dispatched = await calls.dispatch(prepared.runId, prepared.modelCallId)
    assert dispatched.remoteOutcomeUnknown
    usage = ProviderUsage(prompt_tokens=17, completion_tokens=2, total_tokens=19)
    await calls.returned(prepared.runId, prepared.modelCallId, usage)
    after = await ledger.get(payload["runId"])
    assert after.spentMicros == 50 and after.reservedMicros == 0
    assert after.modelCalls == 1 and after.phaseCalls == 1
    assert (
        await ledger.reservation(prepared.runId, prepared.modelCallId)
    ).state == "SETTLED"
    await calls.close(prepared.runId, prepared.modelCallId)
    assert (await calls.get(prepared.runId, prepared.modelCallId)).usage == usage
    assert (await ledger.get(payload["runId"])).spentMicros == 50


@pytest.mark.parametrize("dispatched", [False, True])
async def test_restart_refunds_only_confirmed_prepared_and_retains_unknown(
    database, payload, dispatched
):
    _, ledger = await paid_run(database, payload)
    calls = ModelCallStore(database)
    value = await calls.prepare(intent(payload))
    if dispatched:
        await calls.dispatch(value.runId, value.modelCallId)
    await calls.recover(local_resources_released=lambda _: True)
    book = await ledger.get(value.runId)
    row = await ledger.reservation(value.runId, value.modelCallId)
    closed = await calls.get(value.runId, value.modelCallId)
    assert closed.status == "LOCAL_CLOSED"
    assert closed.remoteOutcomeUnknown is dispatched
    assert row.state == ("UNKNOWN" if dispatched else "REFUNDED")
    assert book.reservedMicros == (34850 if dispatched else 0)
    assert book.spentMicros == 0
    await calls.recover(local_resources_released=lambda _: True)
    assert await ledger.get(value.runId) == book
    await ledger.audit()
    with pytest.raises(HarnessError):
        await calls.prepare(intent(payload))


async def test_cost_overrun_records_real_usage_then_blocks_further_send(
    database, payload
):
    from review_harness.domain.provider import ProviderUsage

    _, ledger = await paid_run(database, payload)
    calls = ModelCallStore(database)
    value = await calls.prepare(intent(payload))
    await calls.dispatch(value.runId, value.modelCallId)
    usage = ProviderUsage(
        prompt_tokens=100000, completion_tokens=1, total_tokens=100001
    )
    with pytest.raises(HarnessError, match="FEE_RESERVATION_OVERRUN"):
        await calls.returned(value.runId, value.modelCallId, usage)
    assert (await calls.get(value.runId, value.modelCallId)).usage == usage
    book = await ledger.get(value.runId)
    assert book.spentMicros == 200008
    assert book.overrunMicros == 165158
    assert book.reservedMicros == 0
    with pytest.raises(HarnessError, match="FEE_RESERVATION_OVERRUN"):
        await calls.prepare(intent(payload))
    await ledger.audit()


@pytest.mark.parametrize("after_commit", [False, True])
async def test_commit_boundary_confirms_all_intent_fee_participants(
    database, payload, after_commit
):
    from review_harness.infrastructure.persistence.store import commit

    _, ledger = await paid_run(database, payload)

    async def boundary(connection):
        if after_commit:
            await commit(connection)
        raise OSError("offline acknowledgement failure")

    calls = ModelCallStore(database, commit_transaction=boundary)
    value = intent(payload)
    if after_commit:
        await calls.prepare(value)
        assert (await ledger.get(value.runId)).reservedMicros == 34850
        assert (await calls.get(value.runId, value.modelCallId)).status == "PREPARED"
        assert (
            await ledger.reservation(value.runId, value.modelCallId)
        ).state == "RESERVED"
    else:
        with pytest.raises(HarnessError, match="FEE_LEDGER_STATE_WRITE_UNCERTAIN"):
            await calls.prepare(value)
        assert await calls.list_for_run(value.runId) == []
        assert (await ledger.get(value.runId)).reservedMicros == 0


@pytest.mark.parametrize("amount,sent_expected", [("0.000001", 0), ("8", 1)])
async def test_production_context_uses_fee_gate_before_mock_http(
    database, payload, workspaces, tmp_path, amount, sent_expected
):
    import asyncio

    import httpx
    from pydantic import SecretStr

    from review_harness.domain.model import canonical
    from review_harness.domain.real_graph import RealReviewGraphState
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.deepseek import DeepSeekAdapter
    from tests.integration.test_deepseek_adapter import response

    runs, ledger = await paid_run(database, payload, amount)
    run = await runs.get_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    checkpoints = Checkpoints(tmp_path / "fee-context.sqlite")
    await checkpoints.open()
    sent = []

    def server(request):
        sent.append(request)
        return httpx.Response(200, stream=httpx.ByteStream(canonical(response())))

    adapter = DeepSeekAdapter(
        SecretStr("offline-only"), transport=httpx.MockTransport(server)
    )
    calls = ModelCallStore(database)
    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        ArtifactService(workspaces.settings, database, runs, workspaces),
        checkpoints,
        adapter,
        model_calls=calls,
        state_type=RealReviewGraphState,
    )
    try:
        state = await engine.initial(context, signal)
        if not sent_expected:
            with pytest.raises(HarnessError, match="FEE_BUDGET_EXHAUSTED"):
                await engine.invoke(context, state, signal)
            assert await calls.list_for_run(run.run_id) == []
        else:
            await engine.invoke(context, state, signal)
            values = await calls.list_for_run(run.run_id)
            assert values[0].callVersion == "2.0"
            assert values[0].status == "LOCAL_CLOSED"
            assert (await ledger.get(run.run_id)).reservedMicros == 0
        assert len(sent) == sent_expected
        await ledger.audit()
    finally:
        await engine.cleanup(run.run_id)
        await workspaces.cleanup(run)
        await checkpoints.close()


@pytest.mark.parametrize(
    "elapsed,code",
    [(1800, "PASS_TIME_BUDGET_EXHAUSTED"), (10800, "RUN_TIME_BUDGET_EXHAUSTED")],
)
async def test_persistent_deadline_expires_before_preparing_or_sending(
    database, payload, elapsed, code
):
    from datetime import datetime, timedelta

    _, ledger = await paid_run(database, payload)
    book = await ledger.get(payload["runId"])
    base = book.phaseStartedAt if elapsed == 1800 else book.runStartedAt
    at = (
        (datetime.fromisoformat(base) + timedelta(seconds=elapsed))
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )
    timed = FeeLedger(database, clock=lambda: at)
    with pytest.raises(HarnessError, match=code):
        await timed.prepare(intent(payload))
    assert await ModelCallStore(database).list_for_run(payload["runId"]) == []


async def test_summary_has_its_own_ten_calls_and_ten_minutes(database, payload):
    from datetime import datetime, timedelta

    from review_harness.domain.model_call import FeeModelCall
    from review_harness.domain.multipass import PASS_ORDER
    from review_harness.domain.provider import ProviderUsage

    _, ledger = await paid_run(database, payload)
    for phase in [*PASS_ORDER[1:], "dedup"]:
        await ledger.begin_phase(payload["runId"], phase)
    assert await ledger.request_timeout(payload["runId"]) == 120
    for _ in range(10):
        value = FeeModelCall.model_validate(
            {
                **intent(payload).record_dict(),
                "callVersion": "2.0",
                "passId": "dedup",
                "purpose": "DEDUP",
            }
        )
        calls = ModelCallStore(database)
        await calls.prepare(value)
        await calls.dispatch(value.runId, value.modelCallId)
        await calls.returned(
            value.runId,
            value.modelCallId,
            ProviderUsage(prompt_tokens=1, completion_tokens=0, total_tokens=1),
        )
        await calls.close(value.runId, value.modelCallId)
    with pytest.raises(HarnessError, match="DEDUP_CALL_BUDGET_EXHAUSTED"):
        await calls.prepare(value.model_copy(update={"modelCallId": str(uuid4())}))
    book = await ledger.get(payload["runId"])
    at = (
        (datetime.fromisoformat(book.phaseStartedAt) + timedelta(seconds=600))
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )
    with pytest.raises(HarnessError, match="SUMMARY_TIME_BUDGET_EXHAUSTED"):
        await FeeLedger(database, clock=lambda: at).request_timeout(payload["runId"])
    assert book.modelCalls == book.dedupCalls == 10
    await ledger.audit()


async def test_current_profile_query_is_authenticated_and_does_not_enable_multipass(
    database, payload, tmp_path
):
    from review_harness.api.app import create_app
    from review_harness.domain.model import canonical
    from tests.integration.test_lifecycle import client_for, settings_for

    path = tmp_path / "operator-price.json"
    path.write_bytes(canonical(profile().model_dump()))
    settings = settings_for(database, tmp_path).model_copy(
        update={"price_profile_path": path}
    )
    app = create_app(settings)
    async with app.router.lifespan_context(app), client_for(app) as client:
        value = (await client.get("/internal/v1/review-price-profile")).json()
        assert value["digest"] == profile().digest
        assert value["priceProfile"]["currency"] == "CNY"
        capability = (
            await client.get("/internal/v1/review-capabilities?protocolVersion=1.1")
        ).json()
        assert capability["feeReservation"] == "READY"
        assert capability["status"] == "NOT_READY"
        assert (
            await client.get(
                "/internal/v1/review-price-profile",
                headers={"Authorization": "Bearer wrong"},
            )
        ).status_code == 401
        expired = profile().model_dump()
        expired.update(
            verifiedAt="2020-01-01T00:00:00.000000Z",
            expiresAt="2020-01-02T00:00:00.000000Z",
        )
        path.write_bytes(canonical(expired))
        assert (
            await client.get("/internal/v1/review-price-profile")
        ).status_code == 503
        assert (
            await client.get("/internal/v1/review-capabilities?protocolVersion=1.1")
        ).json()["feeReservation"] == "NOT_READY"


async def test_price_change_does_not_reprice_or_authorize_existing_run(
    database, payload, tmp_path
):
    from review_harness.domain.model import canonical
    from review_harness.infrastructure.pricing import PriceCatalog

    _, ledger = await paid_run(database, payload)
    path = tmp_path / "price.json"
    changed = profile().model_dump()
    changed["inputCnyPerMillion"] = "3"
    path.write_bytes(canonical(changed))
    calls = ModelCallStore(database, price_catalog=PriceCatalog(path))
    with pytest.raises(HarnessError, match="PRICE_PROFILE_CHANGED"):
        await calls.prepare(intent(payload))
    assert (await ledger.get(payload["runId"])).priceProfile.inputCnyPerMillion == "2"
    assert await calls.list_for_run(payload["runId"]) == []


async def test_damaged_reservation_or_orphan_intent_is_not_accepted(database, payload):
    from sqlalchemy import delete

    from review_harness.infrastructure.persistence.tables import model_call_reservations

    _, ledger = await paid_run(database, payload)
    calls = ModelCallStore(database)
    value = await calls.prepare(intent(payload))
    async with database.engine.begin() as connection:
        await connection.execute(
            delete(model_call_reservations).where(
                model_call_reservations.c.model_call_id == value.modelCallId
            )
        )
    with pytest.raises(HarnessError, match="FEE_LEDGER_INTEGRITY_FAILED"):
        await calls.dispatch(value.runId, value.modelCallId)
    with pytest.raises(HarnessError, match="FEE_LEDGER_INTEGRITY_FAILED"):
        await ledger.audit()


async def test_pass_quota_counts_all_purposes_without_borrowing_next_pass(
    database, payload
):
    from review_harness.domain.provider import ProviderUsage

    _, ledger = await paid_run(database, payload)
    calls = ModelCallStore(database)
    for index in range(300):
        value = intent(payload).model_copy(
            update={"purpose": ["INVESTIGATION", "COMPACTION", "VALIDATION"][index % 3]}
        )
        await calls.prepare(value)
        await calls.dispatch(value.runId, value.modelCallId)
        await calls.returned(
            value.runId,
            value.modelCallId,
            ProviderUsage(prompt_tokens=1, completion_tokens=0, total_tokens=1),
        )
        await calls.close(value.runId, value.modelCallId)
    assert len(await calls.list_for_run(payload["runId"])) == 300
    with pytest.raises(HarnessError, match="PASS_CALL_BUDGET_EXHAUSTED"):
        await calls.prepare(intent(payload))
    await ledger.begin_phase(payload["runId"], "consistency")
    assert (await ledger.get(payload["runId"])).phaseCalls == 0
    assert (await ledger.get(payload["runId"])).modelCalls == 300
    await ledger.audit()


@pytest.mark.parametrize("stage", ["dispatch", "returned"])
@pytest.mark.parametrize("after_commit", [False, True])
async def test_dispatch_and_usage_acknowledgements_cannot_split_fee_facts(
    database, payload, stage, after_commit
):
    from review_harness.domain.provider import ProviderUsage
    from review_harness.infrastructure.persistence.store import commit

    _, ledger = await paid_run(database, payload)
    normal = ModelCallStore(database)
    value = await normal.prepare(intent(payload))
    if stage == "returned":
        await normal.dispatch(value.runId, value.modelCallId)

    async def boundary(connection):
        if after_commit:
            await commit(connection)
        raise OSError("offline boundary fault")

    guarded = ModelCallStore(database, commit_transaction=boundary)
    usage = ProviderUsage(prompt_tokens=17, completion_tokens=2, total_tokens=19)
    if after_commit:
        if stage == "dispatch":
            await guarded.dispatch(value.runId, value.modelCallId)
            assert (await ledger.get(value.runId)).modelCalls == 1
            assert (
                await ledger.reservation(value.runId, value.modelCallId)
            ).state == "DISPATCHING"
        else:
            await guarded.returned(value.runId, value.modelCallId, usage)
            assert (await ledger.get(value.runId)).spentMicros == 50
            assert (
                await ledger.reservation(value.runId, value.modelCallId)
            ).state == "SETTLED"
    else:
        with pytest.raises(HarnessError, match="FEE_LEDGER_STATE_WRITE_UNCERTAIN"):
            if stage == "dispatch":
                await guarded.dispatch(value.runId, value.modelCallId)
            else:
                await guarded.returned(value.runId, value.modelCallId, usage)
        assert (await ledger.get(value.runId)).spentMicros == 0
        assert (await ledger.get(value.runId)).reservedMicros == 34850
        assert (await normal.get(value.runId, value.modelCallId)).status == (
            "PREPARED" if stage == "dispatch" else "DISPATCHING"
        )
    await ledger.audit()


async def test_invalid_usage_keeps_reservation_and_cannot_fake_settlement(
    database, payload
):
    from pydantic import ValidationError

    from review_harness.domain.provider import ProviderUsage

    _, ledger = await paid_run(database, payload)
    calls = ModelCallStore(database)
    value = await calls.prepare(intent(payload))
    await calls.dispatch(value.runId, value.modelCallId)
    usage = ProviderUsage(prompt_tokens=17, completion_tokens=1, total_tokens=18)
    with pytest.raises(ValidationError):
        await calls.returned(
            value.runId,
            value.modelCallId,
            usage.model_copy(update={"total_tokens": 99}),
        )
    await calls.close(value.runId, value.modelCallId, "PROVIDER_RESPONSE_INVALID")
    book = await ledger.get(value.runId)
    assert book.reservedMicros == 34850 and book.spentMicros == 0
    assert (await ledger.reservation(value.runId, value.modelCallId)).state == "UNKNOWN"


@pytest.mark.parametrize(
    "name",
    [
        "review-price-profile-v1",
        "review-price-profile-view-v1",
        "review-model-call-v2",
        "review-fee-budget-v1",
        "review-call-reservation-v1",
    ],
)
def test_fee_schemas_match_production_types_and_reject_extra_fields(name):
    import json

    from jsonschema import Draft202012Validator

    from tests.export_fee_contracts import MODELS, PUBLIC, ROOT

    schema = json.loads((ROOT / "schemas" / (name + ".schema.json")).read_text("utf-8"))
    assert {k: v for k, v in schema.items() if k not in {"$id", "$schema"}} == MODELS[
        name
    ].model_json_schema()
    file = "examples.json" if name in PUBLIC else "internal-examples.json"
    fixture = json.loads((ROOT / "fixtures/review-fee-v1" / file).read_text("utf-8"))[
        name
    ]
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(fixture["positive"])
    assert list(Draft202012Validator(schema).iter_errors(fixture["negative"]))


async def test_cancelled_fee_commit_remains_owned_until_real_transaction_finishes(
    database, payload, workspaces, tmp_path
):
    import asyncio

    import httpx
    from pydantic import SecretStr

    from review_harness.domain.real_graph import RealReviewGraphState
    from review_harness.domain.snapshot import WorkCancelled
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.store import commit
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.deepseek import DeepSeekAdapter

    runs, ledger = await paid_run(database, payload)
    run = await runs.get_run(payload["runId"])
    signal, entered, release = asyncio.Event(), asyncio.Event(), asyncio.Event()

    async def boundary(connection):
        entered.set()
        await release.wait()
        await commit(connection)

    def server(request):
        raise AssertionError("No HTTP send after cancelled prepare")

    calls = ModelCallStore(database, commit_transaction=boundary)
    checkpoints = Checkpoints(tmp_path / "cancel-fee.sqlite")
    await checkpoints.open()
    context = await workspaces.prepare(run, signal)
    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        ArtifactService(workspaces.settings, database, runs, workspaces),
        checkpoints,
        DeepSeekAdapter(
            SecretStr("offline-only"), transport=httpx.MockTransport(server)
        ),
        model_calls=calls,
        state_type=RealReviewGraphState,
    )
    task = None
    try:
        state = await engine.initial(context, signal)
        task = asyncio.create_task(engine.invoke(context, state, signal))
        await asyncio.wait_for(entered.wait(), 5)
        signal.set()
        assert workspaces.runtime_tasks_active(run.run_id)
        release.set()
        with pytest.raises(WorkCancelled):
            await asyncio.wait_for(task, 5)
        assert not workspaces.runtime_tasks_active(run.run_id)
        values = await ModelCallStore(database).list_for_run(run.run_id)
        assert len(values) == 1 and values[0].status == "LOCAL_CLOSED"
        assert (
            await ledger.reservation(run.run_id, values[0].modelCallId)
        ).state == "REFUNDED"
        assert (await ledger.get(run.run_id)).reservedMicros == 0
    finally:
        release.set()
        if task is not None and not task.done():
            await asyncio.gather(task, return_exceptions=True)
        await engine.cleanup(run.run_id)
        await workspaces.cleanup(run)
        await checkpoints.close()


async def test_failed_recovery_must_not_advertise_fee_ready(
    database, tmp_path, monkeypatch
):
    path = tmp_path / "price.json"
    path.write_bytes(canonical(profile().model_dump()))
    settings = settings_for(database, tmp_path).model_copy(
        update={"price_profile_path": path}
    )

    async def corrupt_audit(self):
        raise RuntimeError("injected ledger integrity failure")

    monkeypatch.setattr(FeeLedger, "audit", corrupt_audit)
    app = create_app(settings)
    async with app.router.lifespan_context(app), client_for(app) as client:
        assert not app.state.recovery_complete
        response = await client.get(
            "/internal/v1/review-capabilities?protocolVersion=1.1"
        )
        assert response.json()["feeReservation"] == "NOT_READY", response.json()


@pytest.mark.parametrize("exit_path", ["cancel", "rebuild_error", "request_changed"])
async def test_exit_after_confirmed_prepare_refunds_without_restart(
    database, payload, workspaces, tmp_path, monkeypatch, exit_path
):
    runs, ledger = await paid_run(database, payload)
    run = await runs.get_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    checkpoints = Checkpoints(tmp_path / "probe-checkpoints.sqlite")
    await checkpoints.open()

    prepared = False

    class CancelAfterPrepare(ModelCallStore):
        async def prepare(self, value):
            nonlocal prepared
            result = await super().prepare(value)
            prepared = True
            if exit_path == "cancel":
                signal.set()
            return result

    sends = []

    def server(request):
        sends.append(request)
        raise AssertionError("must not send")

    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        ArtifactService(workspaces.settings, database, runs, workspaces),
        checkpoints,
        DeepSeekAdapter(
            SecretStr("offline-only"), transport=httpx.MockTransport(server)
        ),
        model_calls=CancelAfterPrepare(database),
        state_type=RealReviewGraphState,
    )
    original_build = engine.build

    async def rebuild(*args, **kwargs):
        from dataclasses import replace

        if prepared and exit_path == "rebuild_error":
            raise HarnessError("CONTEXT_MANIFEST_INCOMPLETE", 503, "offline")
        request = await original_build(*args, **kwargs)
        return (
            replace(request, wire=request.wire + b" ")
            if prepared and exit_path == "request_changed"
            else request
        )

    monkeypatch.setattr(engine, "build", rebuild)
    try:
        state = await engine.initial(context, signal)
        expected = WorkCancelled if exit_path == "cancel" else HarnessError
        with pytest.raises(expected) as caught:
            await engine.invoke(context, state, signal)
        if exit_path != "cancel":
            assert caught.value.code == (
                "CONTEXT_MANIFEST_INCOMPLETE"
                if exit_path == "rebuild_error"
                else "CONTEXT_REQUEST_CHANGED"
            )
        await engine.cleanup(run.run_id)
        await workspaces.cleanup(run)
        await runs.complete_failure_safe(
            run.run_id, Failure.make("PROVIDER_CALL_INTERRUPTED")
        )
        assert sends == []
        assert (await runs.get_run(run.run_id)).resource_state == "RELEASED"
        values = await ModelCallStore(database).list_for_run(run.run_id)
        assert len(values) == 1 and values[0].status == "LOCAL_CLOSED"
        assert not values[0].remoteOutcomeUnknown
        assert (
            await ledger.reservation(run.run_id, values[0].modelCallId)
        ).state == "REFUNDED"
        assert (await ledger.get(run.run_id)).reservedCalls == 0
        assert (await ledger.get(run.run_id)).reservedMicros == 0, [
            (c.status, c.remoteOutcomeUnknown) for c in values
        ]
    finally:
        await checkpoints.close()
