import asyncio
import json
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from review_harness.domain.multipass_state import PassFinding
from review_harness.runtime.dedup import recall_groups


def real_findings():
    data = json.loads(
        (
            Path(__file__).parents[1] / "fixtures/position-real-dedup-findings.json"
        ).read_bytes()
    )
    return [
        PassFinding.model_validate({k: v for k, v in f.items() if k != "contributions"})
        for f in data["findings"]
    ]


def test_real_different_wordings_recall_one_five_member_group():
    findings = real_findings()
    assert len({f.reasoningSummary.rule for f in findings}) == 5
    assert len({f.reasoningSummary.trigger for f in findings}) == 5
    assert len({f.suggestion for f in findings}) == 5
    groups = recall_groups(list(reversed(findings)))
    assert [[f.candidateId for f in g] for g in groups] == [
        [f.candidateId for f in findings]
    ]


def test_real_wordings_merge_only_after_exact_group_confirmation():
    import pytest

    from review_harness.runtime.dedup import DedupDecision, merge_group

    group = real_findings()
    handles = {f.candidateId: "c" + str(i + 1) for i, f in enumerate(group)}
    with pytest.raises(ValueError):
        merge_group(group)
    decision = DedupDecision(
        candidateHandles=list(handles.values()),
        sameRootCause=True,
        sameTrigger=True,
        sameFix=True,
        rationale="All remove the same nullableLength null guard; null follows the same path; restoring that guard fixes each claim.",
    )
    merged = merge_group(list(reversed(group)), decision=decision, handles=handles)
    assert merged.candidateId == group[0].candidateId
    assert len(merged.contributions) == 5
    assert set(merged.evidenceIds) == {e for f in group for e in f.evidenceIds}
    assert all(r in merged.codeReferences for f in group for r in f.codeReferences)


@pytest.fixture
async def aggregation_case(database, payload, workspaces, tmp_path):
    from review_harness.application.evidence import EvidenceService
    from review_harness.domain.model import canonical, sha256
    from review_harness.domain.multipass_state import MultipassRegistry, PassOutcome
    from review_harness.domain.position import PositionMultipassState
    from review_harness.domain.provider import ProviderUsage
    from review_harness.domain.review import EvidenceRecord
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from review_harness.infrastructure.persistence.passes import PassStore
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.deepseek import DeepSeekAdapter
    from review_harness.runtime.multipass import MultipassExecutor
    from tests.integration.test_deepseek_adapter import response
    from tests.integration.test_fee_ledger import intent, paid_run
    from tests.snapshot_fixture import publish_snapshot

    fixtures = Path(__file__).parents[1] / "fixtures"
    data = json.loads((fixtures / "position-real-dedup-findings.json").read_bytes())
    source = (fixtures / "position-real-TextLengths.java").read_bytes()
    diff = (fixtures / "position-real-changes.diff").read_bytes()
    payload["snapshot"]["snapshotId"] = str(uuid4())
    location = publish_snapshot(
        workspaces.settings.snapshot_root,
        payload,
        {"src/main/java/acceptance/TextLengths.java": source},
    )
    manifest = json.loads((location / "manifest.json").read_bytes())
    manifest["changes"]["diff"].update(byteSize=len(diff), diffSha256=sha256(diff))
    (location / "artifacts/changes.diff").write_bytes(diff)
    raw = canonical(manifest)
    (location / "manifest.json").write_bytes(raw)
    payload["snapshot"]["manifestSha256"] = sha256(raw)
    runs, fees = await paid_run(database, payload)
    run = await runs.get_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    evidence = EvidenceService(
        workspaces.settings, database, runs, workspaces, artifacts
    )
    checkpoints = Checkpoints(tmp_path / "dedup.sqlite")
    await checkpoints.open()
    calls = ModelCallStore(database)
    control = {"sent": [], "decision": {}, "before_response": None}

    def server(request):
        if control.get("transport_error"):
            raise httpx.ReadTimeout("unknown remote outcome")
        body = json.loads(request.content)
        control["sent"].append(body)
        if control["before_response"]:
            control["before_response"]()
        content = {
            "candidateHandles": ["c1", "c2", "c3", "c4", "c5"],
            "sameRootCause": True,
            "sameTrigger": True,
            "sameFix": True,
            "rationale": "All five remove the nullableLength null guard; null enters the same dereference; restoring the guard fixes the shared regression. Optional testing advice differs.",
        }
        content.update(control["decision"])
        value = response()
        value["choices"][0]["message"]["content"] = control.get(
            "raw", canonical(content).decode()
        )
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        artifacts,
        checkpoints,
        DeepSeekAdapter(
            SecretStr("offline-only"), transport=httpx.MockTransport(server)
        ),
        evidence=evidence,
        model_calls=calls,
        state_type=PositionMultipassState,
    )
    executor = MultipassExecutor(engine, evidence, PassStore(database, runs), fees)
    state = await engine.initial(context, signal)
    originals = real_findings()
    records = []
    for item in data["evidence"]:
        record = EvidenceRecord.model_validate(
            {**item, "runId": run.run_id, "snapshotId": run.snapshot_id}
        )
        await evidence.store.save(run, record)
        records.append(record)
    for phase in state.reviewPlan.selectedPasses:
        if phase != "correctness":
            await fees.begin_phase(run.run_id, phase)
        for f in originals:
            if f.passId != phase:
                continue
            call = intent(payload).model_copy(
                update={"modelCallId": f.validationModelCallId, "purpose": "VALIDATION"}
            )
            call = await calls.prepare(call)
            await calls.dispatch(run.run_id, call.modelCallId)
            await calls.returned(
                run.run_id,
                call.modelCallId,
                ProviderUsage(prompt_tokens=17, completion_tokens=2, total_tokens=19),
            )
            await calls.close(run.run_id, call.modelCallId)
    outcomes = [
        PassOutcome.model_validate(
            {
                "passResult": p,
                "findings": [
                    f.model_dump() for f in originals if f.passId == p["passId"]
                ],
            }
        )
        for p in data["passResults"]
    ]
    registry = MultipassRegistry(
        runId=run.run_id,
        snapshotId=run.snapshot_id,
        activePass="testing",
        candidates=[
            {"candidateHandle": "c" + str(i + 1), "candidateId": f.candidateId}
            for i, f in enumerate(originals)
        ],
    )
    bindings = []
    for i, record in enumerate(records):
        selections = []
        for ref in record.sourceRefs:
            registry, handle = registry.register_source(
                ref.model_dump(), "FULL", "NONE", [[ref.startLine, ref.endLine]]
            )
            selections.append(
                {
                    "sourceHandle": handle,
                    "startLine": ref.startLine,
                    "endLine": ref.endLine,
                }
            )
        bindings.append(
            {
                "evidenceHandle": "e" + str(i + 1),
                "evidenceId": record.evidenceId,
                "selections": selections,
            }
        )
    registry = type(registry).model_validate(
        {**registry.model_dump(), "evidence": bindings}
    )
    state = state.updated(
        evidenceRefs=[r.evidenceId for r in records],
        activePass="testing",
        passId="testing",
        handleRegistry=registry,
        candidateRefs=[f.candidateId for f in originals],
        candidates=[
            {
                k: v
                for k, v in f.model_dump().items()
                if k
                not in {
                    "findingId",
                    "validationModelCallId",
                    "disposition",
                    "validationSummary",
                }
            }
            for f in originals
        ],
        passLedger=outcomes,
    )
    await engine.persist(state, signal)
    try:
        yield executor, context, state, outcomes, signal, control
    finally:
        await engine.cleanup(run.run_id)
        await workspaces.cleanup(run)
        await checkpoints.close()


async def test_production_aggregation_replays_five_original_judgments_and_confirmed_audit(
    aggregation_case,
):
    from review_harness.domain.model import canonical, sha256

    executor, context, state, outcomes, signal, control = aggregation_case
    final_state, findings, limitations = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert not limitations and len(findings) == 1
    assert len(findings[0].contributions) == 5
    assert len(control["sent"]) == 1
    assert findings[0].claim == real_findings()[0].claim
    ref = final_state.artifactRefs[-1]
    record = await read_record(executor, context, ref.artifactId)
    assert record["recordType"] == "dedup_decision" and record["action"] == "MERGED"
    assert [item["finding"] for item in record["originals"]] == [
        f.model_dump() for f in real_findings()
    ]
    assert record["mergedFindingSha256"] == sha256(canonical(findings[0].model_dump()))
    assert len(record["grouping"]["overlapEdges"]) == 10
    checkpoints = executor.engine.checkpoints
    async with checkpoints.connection.execute(
        "SELECT checkpoint_id FROM checkpoints WHERE json_extract(metadata, '$.stateDigest')=?",
        (final_state.digest(),),
    ) as cursor:
        identity = (await cursor.fetchone())[0]
    saved = await checkpoints.read(final_state, identity)
    assert ref in saved.artifactRefs


@pytest.mark.parametrize("field", ["sameRootCause", "sameTrigger", "sameFix"])
async def test_entire_group_retained_for_different_or_uncertain_member(
    aggregation_case, field
):
    executor, context, state, outcomes, signal, control = aggregation_case
    control["decision"][field] = False
    saved, findings, limits = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert len(findings) == 5 and limits and len(control["sent"]) == 1
    assert (await read_record(executor, context, saved.artifactRefs[-1].artifactId))[
        "action"
    ] == "RETAINED"


async def read_record(executor, context, identity):
    text, cursor = "", None
    while True:
        page = await executor.engine.artifacts.content(context.run_id, identity, cursor)
        text += page.text
        if not page.hasMore:
            return json.loads(text)
        cursor = page.nextCursor


@pytest.mark.parametrize("mutation", ["hash", "side", "disjoint"])
def test_source_recall_respects_coordinate_identity(mutation):
    from review_harness.runtime.dedup import recall_groups

    first, second = real_findings()[:2]
    changes = {
        "hash": {"sha256": "sha256:" + "f" * 64},
        "side": {"side": "base"},
        "disjoint": {"startLine": 100, "endLine": 101},
    }[mutation]
    second = second.model_copy(
        update={
            "codeReferences": [
                r.model_copy(update=changes) for r in second.codeReferences
            ]
        }
    )
    assert recall_groups([first, second]) == []


def test_overlap_chain_is_one_recall_group_without_transitive_semantic_merge():
    from review_harness.runtime.dedup import DedupDecision, merge_group

    group = real_findings()[:3]
    refs = [
        f.codeReferences[0].model_copy(update={"startLine": a, "endLine": b})
        for f, (a, b) in zip(group, [(1, 2), (2, 3), (3, 4)], strict=True)
    ]
    group = [
        f.model_copy(update={"codeReferences": [r]})
        for f, r in zip(group, refs, strict=True)
    ]
    assert recall_groups(group) == [group]
    with pytest.raises(ValueError):
        merge_group(
            group,
            handles={f.candidateId: "c" + str(i + 1) for i, f in enumerate(group)},
            decision=DedupDecision(
                candidateHandles=["c1", "c2", "c3"],
                sameRootCause=False,
                sameTrigger=True,
                sameFix=True,
                rationale="A and C have distinct defects even though B overlaps both.",
            ),
        )


@pytest.mark.parametrize(
    "handles", [["c1"], ["c1", "c1", "c3", "c4", "c5"], ["c1", "c2", "c3", "c4", "c9"]]
)
async def test_invalid_group_handles_exhaust_existing_corrections_without_merge(
    aggregation_case, handles
):
    executor, context, state, outcomes, signal, control = aggregation_case
    control["decision"]["candidateHandles"] = handles
    saved, findings, limits = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert len(findings) == 5 and limits and len(control["sent"]) == 3
    assert saved.correctionCount == 2


@pytest.mark.parametrize(
    "failure",
    [
        "write",
        "partial",
        "hash",
        "checkpoint",
        "cancel",
        "quota",
        "audit_size",
        "state_capacity",
    ],
)
async def test_audit_failure_never_adopts_unconfirmed_merge(
    aggregation_case, monkeypatch, failure
):
    from review_harness.domain.model import HarnessError
    from review_harness.domain.snapshot import WorkCancelled

    executor, context, state, outcomes, signal, control = aggregation_case
    artifacts = executor.engine.artifacts
    original = artifacts.publish

    async def publish(*args, **kwargs):
        if args[3] != "dedup_decision":
            return await original(*args, **kwargs)
        if failure == "write":
            raise HarnessError("ARTIFACT_WRITE_FAILED", 503, "test")
        if failure == "cancel":
            raise WorkCancelled()
        if failure == "quota":
            raise HarnessError("ARTIFACT_LIMIT_EXCEEDED", 409, "test")
        value = await original(*args, **kwargs)
        if failure == "partial":
            return value.model_copy(update={"coverage": "PARTIAL"})
        if failure == "hash":
            return value.model_copy(update={"sha256": "sha256:" + "f" * 64})
        return value

    monkeypatch.setattr(artifacts, "publish", publish)
    if failure == "checkpoint":
        read = executor.engine.checkpoints.read

        async def unreadable(expected, identity):
            if expected.artifactRefs:
                raise HarnessError("CHECKPOINT_STATE_WRITE_UNCERTAIN", 503, "test")
            return await read(expected, identity)

        monkeypatch.setattr(executor.engine.checkpoints, "read", unreadable)
    if failure == "audit_size":
        # Enough for the response/manifest, too small for all immutable originals.
        executor.engine.settings.artifact_max_bytes = 20000
    if failure == "state_capacity":
        from review_harness.domain.graph import ArtifactRef

        # Real confirmed artifacts fill the existing 64-reference contract.
        refs = []
        for i in range(64):
            value = await original(
                context, "kept " + str(i), "TOOL_OUTPUT", "existing", signal
            )
            refs.append(
                ArtifactRef.model_validate(
                    {k: value.wire()[k] for k in ArtifactRef.model_fields}
                )
            )
        state = state.updated(artifactRefs=refs)
    if failure in {"quota", "audit_size", "state_capacity"}:
        _, findings, limits = await executor._deduplicate(
            context, state, outcomes, signal
        )
        assert len(findings) == 5 and limits
    else:
        with pytest.raises(WorkCancelled if failure == "cancel" else HarnessError):
            await executor._deduplicate(context, state, outcomes, signal)
    assert len(control["sent"]) == 1


def test_large_components_are_bounded_deterministically_without_cross_block_merge():
    from review_harness.runtime.dedup import comparison_groups

    first = real_findings()[0]
    group = [
        first.model_copy(
            update={
                "candidateId": f"00000000-0000-0000-0000-{i:012d}",
                "findingId": str(uuid4()),
            }
        )
        for i in range(65)
    ]
    comparisons, limits = comparison_groups(list(reversed(group)))
    assert [len(g) for g, _ in comparisons] == [32, 32]
    assert limits and comparisons[1][1]["blockIndex"] == 1


@pytest.mark.parametrize("mutation", ["evidence", "refs", "kind", "disposition"])
def test_semantic_true_cannot_bypass_capacity_or_kind_gate(mutation):
    from review_harness.runtime.dedup import DedupDecision, merge_group

    group = real_findings()[:2]
    if mutation == "evidence":
        group = [
            f.model_copy(update={"evidenceIds": [str(uuid4()) for _ in range(9)]})
            for f in group
        ]
    elif mutation == "refs":
        group = [
            f.model_copy(
                update={
                    "codeReferences": [
                        f.codeReferences[0].model_copy(
                            update={"startLine": i, "endLine": i}
                        )
                        for i in range(offset, offset + 9)
                    ]
                }
            )
            for f, offset in zip(group, [1, 9], strict=True)
        ]
    elif mutation == "kind":
        group[1] = group[1].model_copy(update={"claimKind": "ENVIRONMENT_DEPENDENT"})
    else:
        group[1] = group[1].model_copy(update={"disposition": "RISK"})
    with pytest.raises(ValueError) as rejected:
        merge_group(
            group,
            handles={group[0].candidateId: "c1", group[1].candidateId: "c2"},
            decision=DedupDecision(
                candidateHandles=["c1", "c2"],
                sameRootCause=True,
                sameTrigger=True,
                sameFix=True,
                rationale="Semantic agreement does not authorize capacity or judgment changes.",
            ),
        )
    if mutation in {"evidence", "refs"}:
        from pydantic import ValidationError

        assert isinstance(rejected.value, ValidationError)
        field = "evidenceIds" if mutation == "evidence" else "codeReferences"
        assert any(error["loc"] == (field,) for error in rejected.value.errors())


@pytest.mark.parametrize("raw", ['{"candidateHandles":', "{}"])
async def test_invalid_decision_json_or_missing_fields_is_bounded(
    aggregation_case, raw
):
    executor, context, state, outcomes, signal, control = aggregation_case
    control["raw"] = raw
    saved, findings, limits = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert len(findings) == 5 and limits and saved.correctionCount == 2
    assert len(control["sent"]) == 3


async def test_lost_checkpoint_acknowledgement_is_confirmed_exactly_without_resend(
    aggregation_case,
):
    executor, context, state, outcomes, signal, control = aggregation_case

    async def lost_ack():
        raise OSError("commit acknowledgement lost")

    executor.engine.checkpoints.after_commit = lost_ack
    saved, findings, limits = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert len(findings) == 1 and not limits and len(control["sent"]) == 1
    assert saved.artifactRefs


async def test_audit_reference_is_kept_by_dedup_context_fit_restart_and_integrity_checks(
    aggregation_case,
):
    from review_harness.domain.graph import GraphMessage
    from review_harness.domain.model import HarnessError
    from review_harness.runtime.compaction import Compactor

    executor, context, state, outcomes, signal, control = aggregation_case
    saved, findings, _ = await executor._deduplicate(context, state, outcomes, signal)
    ref = saved.artifactRefs[-1]
    saved = saved.updated(
        messages=[
            GraphMessage(
                messageId=str(uuid4()),
                role="user",
                content="Closed note " + str(i) + ". " * 40,
            )
            for i in range(96)
        ]
    )
    fitted = await Compactor(executor.engine).fit(
        context,
        saved,
        signal,
        purpose="DEDUP",
        structured=True,
        instruction="Compare the group and return json.",
        messages=[{"role": "user", "content": "Explicit untrusted comparison group"}],
    )
    assert fitted.summaryVersion == 0 and ref in fitted.artifactRefs
    checkpoints = executor.engine.checkpoints
    await checkpoints.close()
    await checkpoints.open()
    await executor.engine.validate(context, fitted, signal)
    with pytest.raises(HarnessError, match="CHECKPOINT_REPLAY_FORBIDDEN"):
        await checkpoints.assert_fresh(fitted)
    location = (
        executor.engine.settings.artifact_root
        / context.run_id
        / ref.artifactId
        / "content"
    )
    location.write_bytes(location.read_bytes() + b"changed")
    with pytest.raises(HarnessError):
        await executor.engine.validate(context, fitted, signal)


def test_severity_difference_keeps_deterministic_primary_without_regrading():
    from review_harness.runtime.dedup import DedupDecision, merge_group

    group = real_findings()[:2]
    group[0] = group[0].model_copy(update={"severity": "LOW"})
    group[1] = group[1].model_copy(update={"severity": "CRITICAL"})
    merged = merge_group(
        list(reversed(group)),
        handles={group[0].candidateId: "c1", group[1].candidateId: "c2"},
        decision=DedupDecision(
            candidateHandles=["c1", "c2"],
            sameRootCause=True,
            sameTrigger=True,
            sameFix=True,
            rationale="Same guard regression and null trigger, restored by the same guard despite different grades.",
        ),
    )
    assert merged.severity == "LOW" and merged.candidateId == group[0].candidateId


async def test_unknown_remote_outcome_stops_instead_of_retaining_as_budget(
    aggregation_case,
):
    from review_harness.domain.model import HarnessError

    executor, context, state, outcomes, signal, control = aggregation_case
    control["transport_error"] = True
    with pytest.raises(HarnessError):
        await executor._deduplicate(context, state, outcomes, signal)
    calls = await executor.engine.model_calls.list_for_run(context.run_id)
    assert any(c.remoteOutcomeUnknown for c in calls)
    book = await executor.fees.get(context.run_id)
    assert book.reservedMicros > 0


async def test_exhausted_run_budget_retains_all_without_sending(aggregation_case):
    from datetime import datetime, timedelta

    executor, context, state, outcomes, signal, control = aggregation_case
    book = await executor.fees.get(context.run_id)
    expired = (
        (datetime.fromisoformat(book.runStartedAt) + timedelta(seconds=10800))
        .isoformat()
        .replace("+00:00", "Z")
    )
    executor.fees.clock = lambda: expired
    _, findings, limits = await executor._deduplicate(context, state, outcomes, signal)
    assert len(findings) == 5 and control["sent"] == []
    assert any("RUN_TIME_BUDGET_EXHAUSTED" in item for item in limits)


def test_blank_rationale_is_not_a_confirmed_semantic_decision():
    from review_harness.runtime.dedup import DedupDecision

    with pytest.raises(ValueError):
        DedupDecision(
            candidateHandles=["c1", "c2"],
            sameRootCause=True,
            sameTrigger=True,
            sameFix=True,
            rationale=" \t\n",
        )


@pytest.mark.parametrize(
    "mutation", ["validation_call", "source", "duplicate_identity"]
)
async def test_original_identity_or_source_corruption_is_fatal_before_comparison(
    aggregation_case, mutation
):
    from review_harness.domain.model import HarnessError

    executor, context, state, outcomes, signal, control = aggregation_case
    outcome = outcomes[0]
    finding = outcome.findings[0]
    if mutation == "validation_call":
        finding = finding.model_copy(update={"validationModelCallId": str(uuid4())})
    elif mutation == "source":
        finding = finding.model_copy(
            update={
                "codeReferences": [
                    finding.codeReferences[0].model_copy(
                        update={"sha256": "sha256:" + "f" * 64}
                    )
                ]
            }
        )
    else:
        finding = finding.model_copy(
            update={"findingId": outcomes[1].findings[0].findingId}
        )
    outcomes = [outcome.model_copy(update={"findings": [finding]}), *outcomes[1:]]
    state = state.updated(passLedger=outcomes)
    with pytest.raises(HarnessError):
        await executor._deduplicate(context, state, outcomes, signal)
    assert control["sent"] == []
