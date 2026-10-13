import asyncio
import json
from decimal import Decimal
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.domain.model import HarnessError
from review_harness.runtime.deepseek import DeepSeekAdapter
from tests.integration.test_deepseek_adapter import response
from tests.manual_real_smoke import guard_paid_calls


@pytest.mark.parametrize("failure", [False, True])
async def test_operator_reserves_before_real_adapter_and_stops_unknown_usage(
    tmp_path, failure
):
    sent = []

    def server(request):
        ledger = json.loads((tmp_path / "paid-budget.json").read_bytes())
        assert ledger["outcome"] == "RESERVED"
        assert Decimal(ledger["reservedCny"]) == Decimal("0.546816")
        sent.append(request)
        return (
            httpx.Response(400)
            if failure
            else httpx.Response(
                200, stream=httpx.ByteStream(json.dumps(response()).encode())
            )
        )

    adapter = DeepSeekAdapter(
        SecretStr("offline-only"), transport=httpx.MockTransport(server)
    )
    guard_paid_calls(tmp_path, adapter, Decimal("0.40"))
    request = adapter.build_request(
        [{"role": "user", "content": "Public synthetic test"}],
        [],
        "INVESTIGATION",
        str(uuid4()),
    )
    if failure:
        with pytest.raises(HarnessError) as error:
            await adapter.invoke(request, asyncio.Event())
        assert error.value.code == "PROVIDER_REQUEST_REJECTED"
        with pytest.raises(RuntimeError):
            await adapter.invoke(request, asyncio.Event())
        assert len(sent) == 1
        assert (
            json.loads((tmp_path / "paid-budget.json").read_bytes())["outcome"]
            == "UNKNOWN"
        )
    else:
        first = await adapter.invoke(request, asyncio.Event())
        second = await adapter.invoke(request, asyncio.Event())
        assert (
            first.choices[0].message.content
            == second.choices[0].message.content
            == '{"ok":true}'
        )
        assert len(sent) == 2
        ledger = json.loads((tmp_path / "paid-budget.json").read_bytes())
        assert Decimal(ledger["spentUpperBoundCny"]) == Decimal("0.400084")
        assert ledger["outcome"] == "USAGE_CONFIRMED"


async def test_operator_budget_rejection_sends_no_request(tmp_path):
    sent = []
    adapter = DeepSeekAdapter(
        SecretStr("offline-only"),
        transport=httpx.MockTransport(lambda request: sent.append(request)),
    )
    guard_paid_calls(tmp_path, adapter, Decimal("0.95"))
    request = adapter.build_request(
        [{"role": "user", "content": "Public synthetic test"}],
        [],
        "INVESTIGATION",
        str(uuid4()),
    )
    with pytest.raises(RuntimeError):
        await adapter.invoke(request, asyncio.Event())
    assert sent == []


async def test_explicit_eight_yuan_budget_accepts_prior_cost_above_one(tmp_path):
    sent = []

    def server(request):
        sent.append(request)
        return httpx.Response(
            200, stream=httpx.ByteStream(json.dumps(response()).encode())
        )

    adapter = DeepSeekAdapter(
        SecretStr("offline-only"), transport=httpx.MockTransport(server)
    )
    guard_paid_calls(tmp_path, adapter, Decimal("1.05"), Decimal("8"))
    request = adapter.build_request(
        [{"role": "user", "content": "Public synthetic test"}],
        [],
        "INVESTIGATION",
        str(uuid4()),
    )
    await adapter.invoke(request, asyncio.Event())
    assert len(sent) == 1
    ledger = json.loads((tmp_path / "paid-budget.json").read_bytes())
    assert Decimal(ledger["spentUpperBoundCny"]) == Decimal("1.050042")
