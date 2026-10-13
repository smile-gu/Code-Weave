"""Opt-in paid smoke: one synthetic Run, within the frozen 16-call limit.

Run from the harness directory. Without --execute this only prepares local
materials. The key is entered through getpass, never a command-line argument.
This checks the Python production composition; it does not certify Java/UI or
model review quality. Retain the output directory even on failure/uncertainty.
"""

import argparse
import asyncio
import getpass
import json
import logging
import re
import secrets
import sys
import tempfile
import urllib.request
import warnings
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

import httpx
from pydantic import SecretStr

from review_harness.api.app import create_app
from review_harness.config import Settings
from review_harness.domain.budget import CURRENT_INPUT_BUDGET
from review_harness.domain.model import canonical, sha256
from review_harness.infrastructure.persistence.database import Database, migrate
from review_harness.infrastructure.persistence.model_calls import ModelCallStore
from review_harness.runtime.deepseek_meter import TextMeter
from tests.snapshot_fixture import publish_snapshot

PRICE_URL = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"
MAX_CALLS = 16
MAX_INPUT = CURRENT_INPUT_BUDGET
MAX_OUTPUT = 4096
INPUT_CNY_PER_MILLION = Decimal("2")
OUTPUT_CNY_PER_MILLION = Decimal("8")
BUDGET_CNY = Decimal("1")


def save(root, name, value):
    (root / name).write_bytes(canonical(value))


def verify_price(root, max_calls=8, previous_cost=None, budget=BUDGET_CNY):
    # Public documentation only. Failure or a changed price blocks paid calls.
    with urllib.request.urlopen(PRICE_URL, timeout=20) as response:
        if response.geturl().rstrip("/") != PRICE_URL.rstrip("/"):
            raise RuntimeError("Pricing redirected; manual review required")
        raw = response.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise RuntimeError("Pricing document exceeds capture limit")
    html = raw.decode("utf-8")
    rows = re.findall(
        r"<tr><td>高峰时段</td><td>([0-9.]+)元</td><td>([0-9.]+)元</td></tr>",
        html,
    )
    if "deepseek-flash" not in html or rows != [
        ("0.04", "0.30"),
        ("2", "9.0"),
        ("8", "27.0"),
    ]:
        raise RuntimeError("Official pricing changed or cannot be confirmed")
    if not 1 <= max_calls <= MAX_CALLS:
        raise RuntimeError("Request reservation is outside the approved limit")
    upper_bound = (
        (1 if previous_cost is not None else max_calls)
        * (MAX_INPUT * INPUT_CNY_PER_MILLION + MAX_OUTPUT * OUTPUT_CNY_PER_MILLION)
        / Decimal(1_000_000)
    )
    if previous_cost is not None and (
        not previous_cost.is_finite() or previous_cost < 0
    ):
        raise RuntimeError("Invalid prior cost")
    if not budget.is_finite() or budget <= 0:
        raise RuntimeError("Invalid budget")
    if upper_bound + (previous_cost or Decimal(0)) > budget:
        raise RuntimeError("Worst-case reservation exceeds the approved budget")
    (root / "official-pricing.html").write_bytes(raw)
    return {
        "source": PRICE_URL,
        "documentSha256": sha256(raw),
        "currency": "CNY",
        "cacheMissPeakInputPerMillion": str(INPUT_CNY_PER_MILLION),
        "peakOutputPerMillion": str(OUTPUT_CNY_PER_MILLION),
        "maxRequests": max_calls,
        "maxInputTokensPerRequest": MAX_INPUT,
        "maxOutputTokensPerRequest": MAX_OUTPUT,
        "reservedUpperBoundCny": str(upper_bound),
        "managementBudgetCny": str(budget),
        "reservationMode": "PER_REQUEST" if previous_cost is not None else "FULL_RUN",
        "previousCostUpperBoundCny": str(previous_cost or Decimal(0)),
    }


def guard_paid_calls(root, adapter, previous_cost, budget=BUDGET_CNY):
    """Operator fee guard only: delegate unchanged to the real Adapter.

    Reserve a worst-case request before invoking. Release unused reservation
    only on a schema-validated response with valid Usage. Unknown outcome blocks
    every subsequent request; it never changes a reply or Run verdict.
    """
    if (
        not budget.is_finite()
        or budget <= 0
        or not previous_cost.is_finite()
        or not 0 <= previous_cost <= budget
    ):
        raise RuntimeError("Invalid prior cost")
    invoke = adapter.invoke
    spent = previous_cost
    pending = False
    reserve = (
        MAX_INPUT * INPUT_CNY_PER_MILLION + MAX_OUTPUT * OUTPUT_CNY_PER_MILLION
    ) / Decimal(1_000_000)

    async def guarded(request, signal, **options):
        nonlocal spent, pending
        if pending or spent + reserve > budget:
            raise RuntimeError("Paid budget unavailable or previous outcome unknown")
        save(
            root,
            "paid-budget.json",
            {
                "spentUpperBoundCny": str(spent),
                "reservedCny": str(reserve),
                "outcome": "RESERVED",
                "modelCallId": request.model_call_id,
            },
        )
        pending = True
        try:
            reply = await invoke(request, signal, **options)
        except BaseException:
            save(
                root,
                "paid-budget.json",
                {
                    "spentUpperBoundCny": str(spent),
                    "reservedCny": str(reserve),
                    "outcome": "UNKNOWN",
                    "modelCallId": request.model_call_id,
                },
            )
            raise
        cost = (
            reply.usage.prompt_tokens * INPUT_CNY_PER_MILLION
            + reply.usage.completion_tokens * OUTPUT_CNY_PER_MILLION
        ) / Decimal(1_000_000)
        if cost > reserve:
            raise RuntimeError("Usage exceeded fee reservation")
        save(
            root,
            "paid-budget.json",
            {
                "spentUpperBoundCny": str(spent + cost),
                "reservedCny": "0",
                "outcome": "USAGE_CONFIRMED",
                "modelCallId": request.model_call_id,
            },
        )
        spent += cost
        pending = False
        return reply

    adapter.invoke = guarded


def prepare(root, *, minimal=False):
    for name in ("snapshots", "executions", "artifacts"):
        (root / name).mkdir()
    settings = Settings(
        _env_file=None,
        execution_mode="REAL",
        deepseek_api_key=None,
        deepseek_asset_root=None,
        internal_bearer_token=secrets.token_urlsafe(32),
        database_url=f"sqlite+aiosqlite:///{(root / 'runs.sqlite').as_posix()}",
        checkpoint_database_url=f"sqlite+aiosqlite:///{(root / 'cp.sqlite').as_posix()}",
        snapshot_root=root / "snapshots",
        execution_root=root / "executions",
        artifact_root=root / "artifacts",
        graph_max_model_calls=MAX_CALLS,
        graph_max_steps=64,
        run_timeout_seconds=180,
        model_timeout_seconds=45,
        shutdown_grace_seconds=10,
    )
    TextMeter().verify()
    payload = {
        "protocolVersion": "1.0",
        "runId": str(uuid4()),
        "codingSessionId": str(uuid4()),
        "reviewThreadId": str(uuid4()),
        "executionContract": settings.execution_contract,
        "snapshot": {"snapshotId": str(uuid4()), "manifestSha256": ""},
        "input": {
            "message": (
                "Review the correctness of the synthetic src/Demo.java change. "
                "The public method may receive null; null should produce zero. "
                "Read the fixed materials, investigate autonomously, and report "
                "only supported issues with evidence. Do not make performance claims."
            )
        },
    }
    seeds = json.loads(
        (Path(__file__).parent / "fixtures/review-eval-v1.json").read_text("utf-8")
    )["seeds"]
    before = next(s["source"] for s in seeds if s["id"] == "guarded")
    after = next(s["source"] for s in seeds if s["id"] == "nullable")
    # Exercise a tool body above the State's inline threshold using public text.
    padding = "// Synthetic fixture padding; no project source or secrets.\n" * (
        0 if minimal else 200
    )
    location = publish_snapshot(
        settings.snapshot_root,
        payload,
        {"src/Demo.java": (after + padding).encode("utf-8")},
    )
    diff = (
        "diff --git a/src/Demo.java b/src/Demo.java\n"
        "--- a/src/Demo.java\n+++ b/src/Demo.java\n@@ -1 +1 @@\n"
        f"-{before.rstrip()}\n+{after.rstrip()}\n"
    ).encode("utf-8")
    (location / "artifacts/changes.diff").write_bytes(diff)
    manifest = json.loads((location / "manifest.json").read_bytes())
    manifest["changes"]["diff"].update(byteSize=len(diff), diffSha256=sha256(diff))
    raw = canonical(manifest)
    (location / "manifest.json").write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = sha256(raw)
    save(root, "input.json", payload)
    migrate(settings.database_url)
    return settings, payload


async def execute(root, settings, payload, *, previous_cost=None, budget=BUDGET_CNY):
    app = create_app(settings)  # Production Adapter; no transport/executor override.
    headers = {
        "Authorization": "Bearer " + settings.internal_bearer_token.get_secret_value()
    }
    run_url = f"/internal/v1/review-runs/{payload['runId']}"
    async with app.router.lifespan_context(app):
        if previous_cost is not None:
            guard_paid_calls(root, app.state.real_adapter, previous_cost, budget)
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://local-smoke",
            headers=headers,
        ) as client:
            readiness = await client.get("/internal/v1/health")
            save(root, "readiness.json", readiness.json())
            readiness.raise_for_status()
            started = await client.post("/internal/v1/review-runs", json=payload)
            started.raise_for_status()
            try:
                await asyncio.wait_for(app.state.supervisor.join(), 210)
            except (TimeoutError, asyncio.CancelledError):
                await client.post(run_url + "/cancel")
                raise
            run = await client.get(run_url)
            run.raise_for_status()
            save(root, "run.json", run.json())
            result = await client.get(run_url + "/result")
            adapter = app.state.real_adapter
            if adapter is not None and adapter.response_diagnostic is not None:
                save(root, "provider-diagnostic.json", adapter.response_diagnostic)
            if result.status_code == 200:
                save(root, "result.json", result.json())
                for evidence in result.json().get("evidence", []):
                    identity = evidence["evidenceId"]
                    material = await client.get(
                        run_url + f"/evidence/{identity}/materials"
                    )
                    material.raise_for_status()
                    save(root, f"evidence-{identity}.json", material.json())
    if (
        run.json()["status"] != "SUCCEEDED"
        or run.json()["resourceState"] != "RELEASED"
        or result.status_code != 200
    ):
        raise RuntimeError("Run did not produce a safely completed result")
    # Successful return does not itself prove model quality or full coverage.


async def collect_calls(root, settings):
    database = Database(settings.database_url)
    try:
        payload = json.loads((root / "input.json").read_bytes())
        calls = await ModelCallStore(database).list_for_run(payload["runId"])
        save(root, "model-calls.json", [call.model_dump() for call in calls])
        usage = [call.usage for call in calls if call.usage is not None]
        observed = (
            sum(u.prompt_tokens for u in usage) * INPUT_CNY_PER_MILLION
            + sum(u.completion_tokens for u in usage) * OUTPUT_CNY_PER_MILLION
        ) / Decimal(1_000_000)
        save(
            root,
            "accounting.json",
            {
                "recordedCalls": len(calls),
                "returnedUsageRecords": len(usage),
                "observedUsagePeakCacheMissEstimateCny": str(observed),
                "billingConfirmed": False,
                "unknownUsageOrRemoteOutcome": any(
                    call.remoteOutcomeUnknown or call.usage is None for call in calls
                ),
                "purposesObserved": sorted({call.purpose for call in calls}),
                "scope": "Python production composition; synthetic input only",
                "limitations": [
                    "One autonomous run does not certify review quality.",
                    "Compaction and multiple-tool continuation may not be observed.",
                    "Java/UI, POSIX and remote cancellation are not certified here.",
                    "Provider billing requires independent account confirmation.",
                ],
            },
        )
    finally:
        await database.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="One paid synthetic run")
    parser.add_argument(
        "--max-calls",
        type=int,
        choices=range(1, MAX_CALLS + 1),
        default=8,
        help="Per-run request ceiling; account for prior paid attempts separately",
    )
    parser.add_argument(
        "--minimal",
        action="store_true",
        help="Small closure fixture; excludes large-output padding",
    )
    parser.add_argument(
        "--budget-cny",
        type=Decimal,
        default=BUDGET_CNY,
        help="Authorized cumulative fee budget",
    )
    parser.add_argument(
        "--previous-cost",
        type=Decimal,
        help="Prior confirmed/upper-bound cost; reserve each request against the authorized cumulative fee budget",
    )
    parser.add_argument(
        "--diagnose",
        action="store_true",
        help="Limit this paid run to one request and save safe protocol diagnostics",
    )
    args = parser.parse_args()
    if args.diagnose and not args.execute:
        parser.error("--diagnose requires --execute")
    logging.disable(logging.CRITICAL)  # Never log SDK requests/credentials.
    root = Path(tempfile.mkdtemp(prefix="code-weave-real-smoke-")).resolve()
    print(f"Evidence directory: {root}", flush=True)
    settings = None
    exit_code = 0
    try:
        settings, payload = prepare(root, minimal=args.minimal)
        settings.graph_max_model_calls = 1 if args.diagnose else args.max_calls
        if args.execute:
            pricing = verify_price(
                root,
                settings.graph_max_model_calls,
                args.previous_cost,
                args.budget_cny,
            )
            save(root, "pricing.json", pricing)
            print(
                f"Official DeepSeek; <={pricing['maxRequests']} requests; "
                f"{pricing['reservationMode']} reservation CNY {pricing['reservedUpperBoundCny']}; total budget CNY {args.budget_cny}."
            )
            if not sys.stdin.isatty():
                raise RuntimeError(
                    "Run in an interactive terminal for hidden key input"
                )
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)
                key = getpass.getpass("DeepSeek API Key (hidden): ")
            if not key.strip():
                raise RuntimeError("Empty credential")
            settings.deepseek_api_key = SecretStr(key)
            del key
            asyncio.run(
                execute(
                    root,
                    settings,
                    payload,
                    previous_cost=args.previous_cost,
                    budget=args.budget_cny,
                )
            )
            print(
                "Run finished; inspect run/result/accounting before declaring success."
            )
        else:
            print("Local preparation passed. Provider requests: 0.")
    except (Exception, KeyboardInterrupt) as error:
        # Do not dump exception text/tracebacks, which can include request data.
        code = getattr(error, "code", None)
        save(root, "runner-failure.json", {"type": type(error).__name__, "code": code})
        print("Stopped. Failure evidence saved; do not automatically rerun.")
        exit_code = 1
    finally:
        if settings is not None:
            try:
                asyncio.run(collect_calls(root, settings))
            except Exception as error:
                save(root, "collection-failure.json", {"type": type(error).__name__})
                print("Accounting collection failed; retain the original database.")
                exit_code = 1
            finally:
                settings.deepseek_api_key = None
        print(f"Retain this directory for review: {root}", flush=True)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
