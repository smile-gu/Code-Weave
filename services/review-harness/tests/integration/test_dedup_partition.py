"""Compatible partitions recall sources; only full semantic decisions merge them."""

import json
import random
from pathlib import Path
from uuid import uuid4

import pytest

from review_harness.domain.multipass_state import PassFinding
from review_harness.runtime.dedup import comparison_groups
from tests.integration.test_dedup_fix import aggregation_case as _aggregation_case
from tests.integration.test_dedup_fix import read_record

aggregation_case = _aggregation_case


def nine_findings():
    data = json.loads(
        (
            Path(__file__).parents[1] / "fixtures/dedup-real-nine-findings.json"
        ).read_bytes()
    )
    return [PassFinding.model_validate(f) for f in data["findings"]]


def test_original_nine_findings_recall_compatible_five_and_four_groups():
    findings = nine_findings()
    groups, limits = comparison_groups(findings)
    assert [len(group) for group, _ in groups] == [5, 4]
    assert [group[0].disposition for group, _ in groups] == ["VERIFIED", "RISK"]
    assert not limits


@pytest.mark.parametrize(
    "aggregation_case", ["dedup-real-nine-findings.json"], indirect=True
)
async def test_production_requests_confirm_two_complete_partition_groups(
    aggregation_case,
):
    from review_harness.domain.model import canonical, sha256

    executor, context, state, outcomes, signal, control = aggregation_case
    saved, findings, limits = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert not limits
    assert len(control["sent"]) == 2
    assert (await executor.fees.get(context.run_id)).dedupCalls == 2
    from review_harness.domain.multipass import PASS_ORDER

    sources = [f for outcome in outcomes for f in outcome.findings]
    handles = {
        c.candidateId: c.candidateHandle for c in state.handleRegistry.candidates
    }
    for body, disposition in zip(control["sent"], ["VERIFIED", "RISK"], strict=True):
        expected = sorted(
            (f for f in sources if f.disposition == disposition),
            key=lambda f: (PASS_ORDER.index(f.passId), f.candidateId),
        )
        assert submitted_group(body) == [
            {
                "candidateHandle": handles[f.candidateId],
                "validatedFinding": f.model_dump(exclude={"candidateId", "findingId"}),
            }
            for f in expected
        ]
    assert [(f.disposition, len(f.contributions)) for f in findings] == [
        ("VERIFIED", 5),
        ("RISK", 4),
    ]
    assert {e for f in findings for e in f.evidenceIds} == {
        e for o in outcomes for f in o.findings for e in f.evidenceIds
    }
    records = [
        await read_record(executor, context, ref.artifactId)
        for ref in saved.artifactRefs
    ]
    assert [r["grouping"]["partitionKey"] for r in records] == [
        {"claimKind": "STATIC_CORRECTNESS", "disposition": "VERIFIED"},
        {"claimKind": "ENVIRONMENT_DEPENDENT", "disposition": "RISK"},
    ]
    assert [r["grouping"]["groupIndex"] for r in records] == [0, 1]
    for record, finding in zip(records, findings, strict=True):
        assert record["grouping"]["strategy"] == "compatible-partition-v1"
        assert record["action"] == "MERGED"
        assert record["mergedFindingSha256"] == sha256(canonical(finding.model_dump()))
    checkpoints = executor.engine.checkpoints
    for record, ref in zip(records, saved.artifactRefs, strict=True):
        async with checkpoints.connection.execute(
            "SELECT checkpoint_id FROM checkpoints WHERE json_extract(metadata, '$.revision')=?",
            (record["stateRevision"] + 1,),
        ) as cursor:
            identity = (await cursor.fetchone())[0]
        confirmed = await checkpoints.read(saved, identity)
        assert ref in confirmed.artifactRefs


def submitted_group(body):
    for message in body["messages"]:
        text = message.get("content") or ""
        if "untrustedDedupGroup" not in text:
            continue
        value = json.loads(text.split("Untrusted fixed input data: ", 1)[-1])
        if isinstance(value, dict) and "untrustedDedupGroup" in value:
            return value["untrustedDedupGroup"]
    raise AssertionError("No full comparison group in actual provider request")


def test_partition_and_group_metadata_are_stable_under_input_permutations():
    findings = nine_findings()
    expected, expected_limits = comparison_groups(findings)
    rng = random.Random(17)
    for _ in range(12):
        rng.shuffle(findings)
        groups, limits = comparison_groups(findings)
        assert groups == expected and limits == expected_limits
    assert {f.candidateId for group, _ in expected for f in group} == {
        f.candidateId for f in findings
    }
    assert sum(len(group) for group, _ in expected) == 9
    for group, info in expected:
        assert {(f.claimKind, f.disposition) for f in group} == {
            (info["partitionKey"]["claimKind"], info["partitionKey"]["disposition"])
        }


@pytest.mark.parametrize(
    "change", [{"disposition": "RISK"}, {"claimKind": "QUANTITATIVE"}]
)
def test_either_compatibility_field_separates_otherwise_overlapping_singletons(change):
    first = next(
        f
        for f in nine_findings()
        if f.disposition == ("VERIFIED" if "disposition" in change else "RISK")
    )
    second = PassFinding.model_validate(
        {
            **first.model_dump(),
            **change,
            "candidateId": str(uuid4()),
            "findingId": str(uuid4()),
        }
    )
    assert comparison_groups([first, second]) == ([], [])


@pytest.mark.parametrize("same_key_bridge", [False, True])
def test_foreign_partition_cannot_bridge_nonoverlapping_same_key_nodes(same_key_bridge):
    findings = nine_findings()
    template = next(f for f in findings if f.disposition == "VERIFIED")
    risk = next(f for f in findings if f.disposition == "RISK")

    def item(original, start, end):
        return PassFinding.model_validate(
            {
                **original.model_dump(),
                "candidateId": str(uuid4()),
                "findingId": str(uuid4()),
                "codeReferences": [
                    {
                        **template.codeReferences[0].model_dump(),
                        "startLine": start,
                        "endLine": end,
                    }
                ],
            }
        )

    a, b, c = item(template, 1, 2), item(risk, 2, 4), item(template, 4, 5)
    source = [a, b, c]
    if same_key_bridge:
        source.append(item(template, 2, 4))
    groups, _ = comparison_groups(source)
    assert [len(g) for g, _ in groups] == ([3] if same_key_bridge else [])
    assert all(f.disposition == "VERIFIED" for group, _ in groups for f in group)


def test_partition_precedes_thirty_two_member_blocks_and_priority_is_not_enum_order():
    findings = nine_findings()
    templates = [
        next(f for f in findings if f.disposition == d) for d in ["VERIFIED", "RISK"]
    ]
    source = [
        PassFinding.model_validate(
            {
                **templates[i % 2].model_dump(),
                "passId": "correctness",
                "candidateId": f"00000000-0000-0000-0000-{i:012d}",
                "findingId": str(uuid4()),
            }
        )
        for i in range(66)
    ]
    groups, limits = comparison_groups(list(reversed(source)))
    assert [(len(g), g[0].disposition) for g, _ in groups] == [
        (32, "VERIFIED"),
        (32, "RISK"),
    ]
    assert len(limits) == 2
    for index, (group, info) in enumerate(groups):
        assert info["componentSize"] == 33 and len(info["blockCandidateIds"]) == 32
        assert info["groupIndex"] == index
        assert {f.disposition for f in group} == {info["partitionKey"]["disposition"]}


@pytest.mark.parametrize("ordinal", ["036", "037", "038"])
def test_previous_failed_replies_remain_rejected_in_original_nine_member_context(
    ordinal,
):
    from review_harness.domain.model import HarnessError
    from review_harness.runtime.dedup import DedupDecision, merge_group
    from review_harness.runtime.review import parse_submission

    data = json.loads(
        (
            Path(__file__).parents[1] / "fixtures/dedup-real-nine-findings.json"
        ).read_bytes()
    )
    history = data["historicalDedup"]
    reply = next(r["content"] for r in history["replies"] if r["ordinal"] == ordinal)
    with pytest.raises((HarnessError, ValueError)):
        decision = parse_submission(reply, DedupDecision)
        merge_group(
            nine_findings(), decision=decision, handles=history["candidateHandles"]
        )


@pytest.mark.parametrize(
    "aggregation_case", ["dedup-real-nine-findings.json"], indirect=True
)
@pytest.mark.parametrize("negative_field", ["sameRootCause", "sameTrigger", "sameFix"])
async def test_one_partition_confirmed_other_declined_preserves_all_judgments(
    aggregation_case, negative_field
):
    executor, context, state, outcomes, signal, control = aggregation_case

    def reply(group, content):
        if group[0]["validatedFinding"]["disposition"] == "RISK":
            content[negative_field] = False
        return content

    control["reply"] = reply
    saved, findings, limits = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert len(control["sent"]) == 2 and limits
    assert len(findings) == 5
    merged = next(f for f in findings if f.disposition == "VERIFIED")
    assert len(merged.contributions) == 5
    assert all(len(f.contributions) == 1 for f in findings if f.disposition == "RISK")
    records = [
        await read_record(executor, context, r.artifactId) for r in saved.artifactRefs
    ]
    assert [r["action"] for r in records] == ["MERGED", "RETAINED"]


@pytest.mark.parametrize(
    "aggregation_case", ["dedup-real-nine-findings.json"], indirect=True
)
@pytest.mark.parametrize("invalid", ["cross_group", "subset", "extra", "duplicate"])
async def test_second_partition_cannot_claim_other_partition_or_partial_handles(
    aggregation_case, invalid
):
    executor, context, state, outcomes, signal, control = aggregation_case
    first_handles = []

    def reply(group, content):
        if group[0]["validatedFinding"]["disposition"] == "VERIFIED":
            first_handles.extend(content["candidateHandles"])
        else:
            hs = content["candidateHandles"]
            content["candidateHandles"] = {
                "cross_group": first_handles,
                "subset": hs[:-1],
                "extra": [*hs, first_handles[0]],
                "duplicate": [*hs[:-1], hs[0]],
            }[invalid]
        return content

    control["reply"] = reply
    saved, findings, limits = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert len(control["sent"]) == 4 and saved.correctionCount == 2
    assert limits and len(findings) == 5
    assert len(saved.artifactRefs) == 1
    first_group = set(
        submitted_group(control["sent"][0])[i]["candidateHandle"] for i in range(5)
    )
    for body in control["sent"][1:]:
        delivered = submitted_group(body)
        assert len(delivered) == 4
        assert not first_group & {x["candidateHandle"] for x in delivered}


@pytest.mark.parametrize(
    "aggregation_case", ["dedup-real-nine-findings.json"], indirect=True
)
async def test_budget_expiry_after_first_confirmation_keeps_first_merge_and_remaining_originals(
    aggregation_case,
    monkeypatch,
):
    from datetime import datetime, timedelta

    executor, context, state, outcomes, signal, control = aggregation_case
    book = await executor.fees.get(context.run_id)
    expired = (
        (datetime.fromisoformat(book.runStartedAt) + timedelta(seconds=10800))
        .isoformat()
        .replace("+00:00", "Z")
    )
    # Both layers use the same real SQLite ledger with this test's clock.
    monkeypatch.setattr(executor.engine.model_calls, "_fees", lambda: executor.fees)
    control["before_response"] = lambda: setattr(
        executor.fees, "clock", lambda: expired
    )
    saved, findings, limits = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert len(control["sent"]) == 1
    assert len(findings) == 5 and len(saved.artifactRefs) == 1
    assert any("RUN_TIME_BUDGET_EXHAUSTED" in limit for limit in limits)
    assert (
        len(next(f for f in findings if f.disposition == "VERIFIED").contributions) == 5
    )
    assert all(len(f.contributions) == 1 for f in findings if f.disposition == "RISK")


@pytest.mark.parametrize(
    "aggregation_case", ["dedup-real-nine-findings.json"], indirect=True
)
async def test_compatible_partition_singletons_do_not_call_provider(aggregation_case):
    executor, context, state, outcomes, signal, control = aggregation_case
    filtered = []
    selected = {
        outcomes[0].findings[0].candidateId,
        outcomes[0].findings[1].candidateId,
    }
    for outcome in outcomes:
        keep = [f for f in outcome.findings if f.candidateId in selected]
        filtered.append(
            outcome.model_copy(
                update={
                    "findings": keep,
                    "passResult": outcome.passResult.model_copy(
                        update={"findingIds": [f.findingId for f in keep]}
                    ),
                }
            )
        )
    state = state.updated(passLedger=filtered)
    _, findings, limits = await executor._deduplicate(context, state, filtered, signal)
    assert len(findings) == 2 and not limits and control["sent"] == []
    assert all(len(f.contributions) == 1 for f in findings)


@pytest.mark.parametrize(
    "aggregation_case", ["dedup-real-nine-findings.json"], indirect=True
)
async def test_second_group_unknown_send_keeps_first_audit_and_fatal_fee_protection(
    aggregation_case,
):
    import httpx

    from review_harness.domain.model import HarnessError

    executor, context, state, outcomes, signal, control = aggregation_case

    def reply(group, content):
        if group[0]["validatedFinding"]["disposition"] == "RISK":
            raise httpx.ReadTimeout("offline unknown result")
        return content

    control["reply"] = reply
    with pytest.raises(HarnessError):
        await executor._deduplicate(context, state, outcomes, signal)
    assert len(control["sent"]) == 2
    saved = executor.investigator.last_state
    assert len(saved.artifactRefs) == 1
    record = await read_record(executor, context, saved.artifactRefs[0].artifactId)
    assert record["action"] == "MERGED" and record["grouping"]["groupIndex"] == 0
    calls = await executor.engine.model_calls.list_for_run(context.run_id)
    assert any(c.remoteOutcomeUnknown for c in calls)
    assert (await executor.fees.get(context.run_id)).reservedMicros > 0


def test_severity_and_category_never_create_extra_partitions():
    original = nine_findings()
    altered = [
        PassFinding.model_validate(
            {
                **f.model_dump(),
                "severity": "LOW" if i % 2 else "CRITICAL",
                "category": "Category " + str(i),
            }
        )
        for i, f in enumerate(original)
    ]
    assert [[f.candidateId for f in g] for g, _ in comparison_groups(altered)[0]] == [
        [f.candidateId for f in g] for g, _ in comparison_groups(original)[0]
    ]


@pytest.mark.parametrize(
    "aggregation_case", ["dedup-real-nine-findings.json"], indirect=True
)
async def test_two_partitions_share_two_corrections_instead_of_resetting_allowance(
    aggregation_case,
):
    executor, context, state, outcomes, signal, control = aggregation_case
    foreign = []

    def reply(group, content):
        if group[0]["validatedFinding"]["disposition"] == "VERIFIED":
            foreign[:] = content["candidateHandles"]
            if len(control["sent"]) == 1:
                content["unexpectedNote"] = "Force one bounded schema correction"
        else:
            content["candidateHandles"] = foreign
        return content

    control["reply"] = reply
    saved, findings, limits = await executor._deduplicate(
        context, state, outcomes, signal
    )
    assert len(control["sent"]) == 4 and saved.correctionCount == 2
    assert len(findings) == 5 and limits and len(saved.artifactRefs) == 1
    assert (await executor.fees.get(context.run_id)).dedupCalls == 4
