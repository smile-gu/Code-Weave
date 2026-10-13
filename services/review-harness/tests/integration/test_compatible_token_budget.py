import asyncio
from dataclasses import replace

import httpx
import pytest
from pydantic import SecretStr

from review_harness.domain.model import HarnessError
from review_harness.runtime.deepseek import DeepSeekAdapter
from review_harness.runtime.token_budget import TokenBudgetPolicy, context_rejection


def test_manifest_versions_are_strict_and_shared_schema_is_current():
    import json
    from pathlib import Path
    from uuid import uuid4

    from jsonschema import Draft202012Validator
    from pydantic import ValidationError

    from review_harness.domain.graph import (
        CompatibleContextManifest,
        ContextManifest,
        ExpandedContextManifest,
        read_context_manifest,
    )
    from review_harness.domain.model import utc_now

    identity = str(uuid4())
    legacy = dict(
        manifestVersion="1.0",
        modelCallId=identity,
        runId=identity,
        snapshotId=identity,
        manifestSha256="sha256:" + "a" * 64,
        passId="correctness",
        investigationId=identity,
        modelId="deepseek-flash",
        callPurpose="INVESTIGATION",
        stateRevision=0,
        summaryVersion=0,
        sourceRefs=[],
        toolSetDigest="sha256:" + "b" * 64,
        requestDigest="sha256:" + "c" * 64,
        meterId="V41_REFERENCE_LOCAL_TOKENS",
        meterVersion="v4.1-2cba9e42-v1",
        estimatedInputTokens=7897,
        inputBudget=27648,
        reservedOutputTokens=4096,
        protocolSafetyMargin=1024,
        createdAt=utc_now(),
    )
    assert type(read_context_manifest(legacy)) is ContextManifest
    current = {
        **legacy,
        "manifestVersion": "1.1",
        "conservativeInputTokens": 8921,
        "uncertaintyReserveTokens": 1024,
        "policyVersion": "deepseek-compatible-budget-v1",
    }
    assert type(read_context_manifest(current)) is CompatibleContextManifest
    root = Path(__file__).resolve().parents[4] / "contracts/schemas"
    schema = json.loads(
        (root / "review-context-manifest-v1.1.schema.json").read_bytes()
    )
    Draft202012Validator.check_schema(schema)
    assert {
        k: v for k, v in schema.items() if k not in {"$id", "$schema"}
    } == CompatibleContextManifest.model_json_schema()
    Draft202012Validator(schema).validate(current)
    expanded = {
        **current,
        "manifestVersion": "1.2",
        "policyVersion": "deepseek-compatible-budget-v2",
        "estimatedInputTokens": 40000,
        "conservativeInputTokens": 44000,
        "uncertaintyReserveTokens": 4000,
        "inputBudget": 257024,
    }
    assert type(read_context_manifest(expanded)) is ExpandedContextManifest
    schema_v2 = json.loads(
        (root / "review-context-manifest-v1.2.schema.json").read_bytes()
    )
    Draft202012Validator.check_schema(schema_v2)
    Draft202012Validator(schema_v2).validate(expanded)
    assert {
        k: v for k, v in schema_v2.items() if k not in {"$id", "$schema"}
    } == ExpandedContextManifest.model_json_schema()
    for invalid in (
        {**expanded, "manifestVersion": "1.1"},
        {**expanded, "policyVersion": "deepseek-compatible-budget-v1"},
        {**expanded, "inputBudget": 27648},
    ):
        with pytest.raises(ValidationError):
            read_context_manifest(invalid)
    for invalid in (
        {**current, "conservativeInputTokens": 8920},
        {**current, "policyVersion": "unknown"},
        {**current, "extra": True},
    ):
        with pytest.raises(ValidationError):
            read_context_manifest(invalid)
    with pytest.raises(ValueError):
        read_context_manifest({**current, "manifestVersion": "2.0"})


@pytest.mark.parametrize(
    "estimate,reserve",
    [
        (0, 1024),
        (7897, 1024),
        (10240, 1024),
        (10241, 1025),
        (25134, 2514),
        (25135, 2514),
        (27648, 2765),
        (54923, 5493),
        (54924, 5493),
        (233658, 23366),
        (233659, 23366),
    ],
)
def test_versioned_reserve_uses_integer_ceiling(estimate, reserve):
    policy = TokenBudgetPolicy()
    assert policy.reserve(estimate) == reserve
    assert policy.conservative(estimate) == estimate + reserve


@pytest.mark.parametrize(
    "actual,code",
    [
        (7897, None),
        (7975, "INPUT_ESTIMATE_UNDERCOUNT"),
        (8921, "INPUT_ESTIMATE_UNDERCOUNT"),
        (8922, "INPUT_ESTIMATE_RESERVE_EXCEEDED"),
        (27648, "INPUT_ESTIMATE_RESERVE_EXCEEDED"),
        (60416, "INPUT_ESTIMATE_RESERVE_EXCEEDED"),
        (257024, "INPUT_ESTIMATE_RESERVE_EXCEEDED"),
    ],
)
def test_usage_warning_distinguishes_reserve_without_changing_policy(actual, code):
    policy = TokenBudgetPolicy()
    warning = policy.warning(7897, actual)
    assert (warning["code"] if warning else None) == code
    assert policy == TokenBudgetPolicy()


@pytest.mark.parametrize(
    "change",
    [
        {"minimum_reserve": 1},
        {"reserve_percent": 5},
        {"version": "unknown"},
        {"context_window": 524288},
    ],
)
def test_unknown_policy_is_not_implicitly_enabled(change):
    with pytest.raises(HarnessError, match="CONTEXT_BUDGET_POLICY_UNSUPPORTED"):
        replace(TokenBudgetPolicy(), **change).validate()


@pytest.mark.parametrize("estimate,allowed", [(233658, True), (233659, False)])
async def test_adapter_itself_enforces_256k_conservative_boundary(estimate, allowed):
    from uuid import uuid4

    sent = []

    def server(request):
        from review_harness.domain.model import canonical
        from tests.integration.test_deepseek_adapter import response

        sent.append(request)
        return httpx.Response(200, stream=httpx.ByteStream(canonical(response())))

    adapter = DeepSeekAdapter(
        SecretStr("offline-only"),
        transport=httpx.MockTransport(server),
    )
    adapter.count_request = lambda _: estimate
    request = adapter.build_request(
        [{"role": "user", "content": "Fixed input"}],
        [],
        "INVESTIGATION",
        str(uuid4()),
    )
    if allowed:
        await adapter.invoke(request, asyncio.Event())
        assert len(sent) == 1
    else:
        with pytest.raises(HarnessError, match="CONTEXT_REQUEST_EXCEEDS_BUDGET"):
            await adapter.invoke(request, asyncio.Event())
        assert not sent


def test_context_error_classifier_requires_explicit_documented_codes():
    value = {"error": {"code": "documented_mock_window_rejection"}}
    assert context_rejection(400, value, {"documented_mock_window_rejection"})
    assert not context_rejection(400, value, set())
    assert not context_rejection(
        400, {"error": {"message": "context length exceeded"}}, set()
    )
    assert not context_rejection(500, value, {"documented_mock_window_rejection"})
    from review_harness.runtime.provider_profile import PROFILE

    assert PROFILE["contextRecovery"]["status"] == "DISABLED"


@pytest.mark.parametrize("purpose", ["INVESTIGATION", "COMPACTION", "VALIDATION"])
async def test_conservative_minimum_overflow_sends_zero_requests(
    database, tmp_path, payload, workspaces, purpose
):
    from review_harness.domain.real_graph import RealReviewGraphState
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from review_harness.infrastructure.persistence.store import RunStore
    from review_harness.runtime.context import ContextEngine

    runs = RunStore(database)
    await runs.create_run(payload)
    run, _ = await runs.start_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    sent = []
    adapter = DeepSeekAdapter(
        SecretStr("offline-only"),
        transport=httpx.MockTransport(lambda r: sent.append(r)),
    )
    adapter.count_request = lambda _: 233659
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    cp = Checkpoints(tmp_path / "compatible.sqlite")
    await cp.open()
    calls = ModelCallStore(database)
    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        artifacts,
        cp,
        adapter,
        model_calls=calls,
        state_type=RealReviewGraphState,
    )
    try:
        state = await engine.initial(context, signal)
        with pytest.raises(HarnessError, match="CONTEXT_MINIMUM_EXCEEDS_BUDGET"):
            await engine.invoke(
                context,
                state,
                signal,
                purpose=purpose,
                instruction="Return json." if purpose == "VALIDATION" else None,
                structured=purpose == "VALIDATION",
            )
        assert not sent and not await calls.list_for_run(run.run_id)
    finally:
        await engine.cleanup(run.run_id)
        await cp.close()
        await workspaces.cleanup(run)


async def test_historical_drift_survives_app_restart_without_readiness_poison(
    database, tmp_path, payload
):
    import json
    from pathlib import Path
    from uuid import uuid4

    from review_harness.api.app import create_app
    from review_harness.domain.model_call import ModelCall
    from review_harness.domain.provider import ProviderUsage
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from review_harness.infrastructure.persistence.store import RunStore
    from tests.integration.test_lifecycle import client_for, settings_for

    runs = RunStore(database)
    await runs.create_run(payload)
    await runs.start_run(payload["runId"])
    root = Path(__file__).resolve().parents[4] / "contracts"
    original = json.loads(
        (root / "fixtures/review-real-v1/examples.json").read_bytes()
    )["review-model-call-v1"]["positive"]
    intent = ModelCall.model_validate(
        {
            **original,
            "modelCallId": str(uuid4()),
            "runId": payload["runId"],
            "snapshotId": payload["snapshot"]["snapshotId"],
        }
    )
    calls = ModelCallStore(database)
    await calls.prepare(intent)
    await calls.dispatch(intent.runId, intent.modelCallId)
    await calls.returned(
        intent.runId,
        intent.modelCallId,
        ProviderUsage(prompt_tokens=7975, completion_tokens=1, total_tokens=7976),
    )
    closed = await calls.close(
        intent.runId, intent.modelCallId, "PROVIDER_TOKEN_METER_DRIFT"
    )
    saved = closed.record_bytes()
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "execution_mode": "REAL",
            "deepseek_api_key": SecretStr("offline-only"),
            "checkpoint_database_url": f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
        }
    )
    sent = []
    app = create_app(
        settings, provider_transport=httpx.MockTransport(lambda r: sent.append(r))
    )
    async with app.router.lifespan_context(app), client_for(app) as client:
        assert (await client.get("/internal/v1/health")).status_code == 200
        assert await ModelCallStore(app.state.database).has_meter_drift()
        assert (
            await ModelCallStore(app.state.database).get(
                intent.runId, intent.modelCallId
            )
        ).record_bytes() == saved
        assert not sent
