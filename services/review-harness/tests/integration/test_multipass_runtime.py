from uuid import uuid4

import pytest


@pytest.mark.parametrize(
    "name",
    [
        "review-graph-state-v4",
        "review-context-manifest-v1.3",
        "review-pass-fact-v1",
        "review-pass-progress-v1",
        "review-harness-status-v1.1",
        "review-harness-event-v1.1",
        "review-local-investigation-v2",
        "review-local-validation-batch-v2",
        "review-dedup-decision-v1",
    ],
)
def test_runtime_schemas_match_strict_production_types(name):
    import json

    from jsonschema import Draft202012Validator

    from tests.export_multipass_runtime_contracts import MODELS, ROOT

    schema = json.loads((ROOT / (name + ".schema.json")).read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    assert {k: v for k, v in schema.items() if k not in {"$id", "$schema"}} == MODELS[
        name
    ].model_json_schema()


@pytest.mark.parametrize("state_version", ["4.0", "6.0"])
async def test_state4_compacts_closed_history_before_message_collection_overflow(
    database, payload, workspaces, tmp_path, state_version
):
    import asyncio

    import httpx
    from pydantic import SecretStr

    from review_harness.domain.graph import GraphMessage
    from review_harness.domain.model import canonical
    from review_harness.domain.multipass_state import MultipassState
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from review_harness.infrastructure.persistence.passes import PassStore
    from review_harness.runtime.compaction import Compactor
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.deepseek import DeepSeekAdapter
    from tests.integration.test_deepseek_adapter import response
    from tests.integration.test_fee_ledger import paid_run

    runs, _ = await paid_run(database, payload)
    run = await runs.get_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    checkpoints = Checkpoints(tmp_path / "collection.sqlite")
    await checkpoints.open()

    def server(request):
        value = response()
        value["choices"][0]["message"]["content"] = (
            "Closed history contains notes only; no finding was verified."
        )
        return httpx.Response(200, stream=httpx.ByteStream(canonical(value)))

    from review_harness.domain.position import PositionMultipassState

    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        ArtifactService(workspaces.settings, database, runs, workspaces),
        checkpoints,
        DeepSeekAdapter(
            SecretStr("offline-only"), transport=httpx.MockTransport(server)
        ),
        model_calls=ModelCallStore(database),
        state_type=PositionMultipassState if state_version == "6.0" else MultipassState,
    )
    passes = PassStore(database, runs)
    engine.pass_store = passes
    try:
        state = await engine.initial(context, signal)
        if state_version == "6.0":
            from review_harness.domain.graph import ArtifactRef

            record = await engine.artifacts.publish(
                context,
                canonical(
                    {
                        "recordVersion": "1.0",
                        "validationProtocol": "POSITION_V1",
                        **state.identity(),
                    }
                ).decode(),
                "TOOL_OUTPUT",
                "position_validation",
                signal,
                producer_type="SYSTEM",
                call_id=str(uuid4()),
                media_type="application/json",
            )
            ref = ArtifactRef.model_validate(
                {k: record.wire()[k] for k in ArtifactRef.model_fields}
            )
            state = state.updated(validationRecordRefs=[ref.model_dump()])
        from review_harness.domain.graph import ArtifactRef

        audit = await engine.artifacts.publish(
            context,
            canonical(
                {
                    "recordType": "dedup_decision",
                    "recordVersion": "1.0",
                    **state.identity(),
                    "action": "RETAINED",
                }
            ).decode(),
            "TOOL_OUTPUT",
            "dedup_decision",
            signal,
            producer_type="SYSTEM",
            media_type="application/json",
        )
        audit_ref = ArtifactRef.model_validate(
            {k: audit.wire()[k] for k in ArtifactRef.model_fields}
        )
        state = state.updated(artifactRefs=[audit_ref])
        state = state.updated(
            messages=[
                GraphMessage(
                    messageId=str(uuid4()),
                    role="user",
                    content="Observed note " + str(i) + ". " * 40,
                ).model_dump()
                for i in range(96)
            ]
        )
        await engine.persist(state, signal)
        await passes.initialize(run.run_id)
        await passes.begin(run.run_id, "correctness", state.revision, state.digest())
        fitted = await Compactor(engine).fit(context, state, signal)
        assert fitted.summaryVersion == 1 and fitted.compactionCount == 1
        assert audit_ref in fitted.artifactRefs
        if state_version == "6.0":
            assert fitted.validationRecordRefs == state.validationRecordRefs
            assert ref in fitted.artifactRefs
        assert (
            len(fitted.messages) < 96
            and fitted.modelCallCount == 1
            and fitted.runModelCalls == 1
        )
        assert fitted.handleRegistry == state.handleRegistry
    finally:
        await engine.cleanup(run.run_id)
        await workspaces.cleanup(run)
        await checkpoints.close()


def test_sources_are_observed_independently_in_each_pass_without_reusing_handles():
    from review_harness.domain.multipass_state import MultipassRegistry

    registry = MultipassRegistry(
        runId=str(uuid4()), snapshotId=str(uuid4()), activePass="correctness"
    )
    source = {
        "sourceType": "CODE",
        "path": "src/Demo.java",
        "side": "final",
        "sha256": "sha256:" + "a" * 64,
        "startLine": 1,
        "endLine": 2,
    }
    registry, first = registry.register_source(source, "FULL", "NONE", [[1, 2]])
    registry = registry.for_pass("security")
    registry, second = registry.register_source(source, "FULL", "NONE", [[1, 1]])
    assert (first, second) == ("s1", "s2")
    assert registry.sources[0].observedRanges == [[1, 2]]
    assert registry.sources[1].observedRanges == [[1, 1]]
    with pytest.raises(ValueError):
        registry.for_pass("correctness")


def test_state4_replaces_pass_view_but_keeps_trusted_run_identity_and_counters():
    from review_harness.domain.graph import GraphMessage
    from review_harness.domain.model import utc_now
    from review_harness.domain.multipass_state import MultipassRegistry, MultipassState
    from review_harness.domain.real_graph import parse_state
    from tests.integration.test_multipass_contracts import plan_data

    run_id, snapshot_id = str(uuid4()), str(uuid4())
    registry = MultipassRegistry(
        runId=run_id, snapshotId=snapshot_id, activePass="correctness"
    )
    state = MultipassState(
        runId=run_id,
        snapshotId=snapshot_id,
        reviewThreadId=str(uuid4()),
        manifestSha256="sha256:" + "a" * 64,
        investigationId=str(uuid4()),
        userGoalDigest="sha256:" + "b" * 64,
        scopeDigest="sha256:" + "c" * 64,
        toolSetDigest="sha256:" + "d" * 64,
        handleRegistry=registry,
        reviewPlan=plan_data(),
        activePass="correctness",
        passId="correctness",
        runStartedAt=utc_now(),
        messages=[
            GraphMessage(
                messageId=str(uuid4()), role="user", content="previous hypothesis"
            )
        ],
        modelCallCount=17,
        runModelCalls=17,
    )
    restored = parse_state(state.model_dump())
    fresh = restored.for_pass("consistency", utc_now())
    assert fresh.stateVersion == "4.0" and fresh.passId == "consistency"
    assert (
        fresh.messages == [] and fresh.summaryVersion == 0 and fresh.inspections == []
    )
    assert fresh.modelCallCount == 0 and fresh.runModelCalls == 17
    assert fresh.revision == state.revision + 1
    assert fresh.investigationId != state.investigationId and fresh.runId == run_id
    with pytest.raises(ValueError):
        parse_state({**state.model_dump(), "stateVersion": "3.0"})


async def test_pass_facts_are_serial_and_events_commit_with_their_sequence(
    database, payload
):
    from review_harness.domain.model import HarnessError
    from review_harness.infrastructure.persistence.passes import PassStore
    from tests.integration.test_fee_ledger import paid_run

    runs, _ = await paid_run(database, payload)
    passes = PassStore(database, runs)
    facts = await passes.initialize(payload["runId"])
    assert [f.ordinal for f in facts] == list(range(6))
    assert all(f.status == "READY" for f in facts)
    await passes.begin(payload["runId"], "correctness", 1, "sha256:" + "a" * 64)
    with pytest.raises(HarnessError):
        await passes.begin(payload["runId"], "consistency", 2, "sha256:" + "b" * 64)
    events = await runs.read_events(payload["runId"], 0, 32)
    assert [e["sequence"] for e in events] == list(range(1, 5))
    assert events[-1]["eventType"] == "REVIEW_PASS_UPDATED"
    assert events[-1]["protocolVersion"] == "1.1"
    assert (await runs.get_run(payload["runId"])).last_event_sequence == 4


@pytest.mark.parametrize(
    "find_candidates",
    [
        False,
        True,
        "old-source-correction",
        "submission-rejected",
        "candidate-rejected",
        "fatal-storage",
    ],
)
async def test_six_passes_use_fresh_views_and_real_fee_mock_transport(
    database, payload, workspaces, tmp_path, find_candidates
):
    import asyncio
    import json

    import httpx
    from pydantic import SecretStr

    from review_harness.application.evidence import EvidenceService
    from review_harness.domain.model import canonical
    from review_harness.domain.multipass_state import MultipassState
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from review_harness.infrastructure.persistence.passes import PassStore
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.deepseek import DeepSeekAdapter
    from review_harness.runtime.multipass import MultipassExecutor
    from tests.integration.test_deepseek_adapter import response
    from tests.integration.test_fee_ledger import paid_run
    from tests.snapshot_fixture import publish_snapshot

    source = "class Demo { int value(Integer x) { return x.intValue(); } }\n"
    rejected_pass = find_candidates in {"submission-rejected", "candidate-rejected"}
    generate_candidates = bool(find_candidates) and find_candidates not in {
        "submission-rejected",
        "fatal-storage",
    }
    payload["snapshot"]["snapshotId"] = str(uuid4())
    publish_snapshot(
        workspaces.settings.snapshot_root, payload, {"src/Demo.java": source.encode()}
    )
    runs, ledger = await paid_run(database, payload)
    run = await runs.get_run(payload["runId"])
    # Existing helper selects six; this test proves all six serial views as well.
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    evidence = EvidenceService(
        workspaces.settings, database, runs, workspaces, artifacts
    )
    checkpoints = Checkpoints(tmp_path / "multipass.sqlite")
    await checkpoints.open()
    sent, counts, first_handles = [], {}, []

    def server(request):
        data = json.loads(request.content)
        anchors = next(
            json.loads(m["content"].split("Untrusted fixed input data: ", 1)[1])
            for m in data["messages"]
            if "Untrusted fixed input data: " in (m.get("content") or "")
        )
        phase = anchors["activePass"]
        count = counts[phase] = counts.get(phase, 0) + 1
        sent.append((phase, data))
        result = response()
        choice = result["choices"][0]
        if "DedupDecision" in data["messages"][0]["content"]:
            group_block = next(
                m["content"]
                for m in data["messages"]
                if "untrustedDedupGroup" in (m.get("content") or "")
            )
            group = json.loads(
                group_block.split("Untrusted fixed input data: ", 1)[-1]
            )["untrustedDedupGroup"]
            choice["message"]["content"] = canonical(
                {
                    "candidateHandles": [g["candidateHandle"] for g in group],
                    "sameRootCause": True,
                    "sameTrigger": True,
                    "sameFix": True,
                    "rationale": "Same source, trigger and guard fix",
                }
            ).decode()
        elif rejected_pass and phase == "security" and "response_format" in data:
            choice["message"]["content"] = '{"invalid":'
        elif count == 1:
            assert not any(
                "prior-pass-private-thought" in (m.get("content") or "")
                for m in data["messages"]
            )
            first_handles.extend(
                s["sourceHandle"]
                for s in anchors["sourceCatalog"]
                if s["sourceKind"] == "USER_INPUT"
            )
            choice.update(
                finish_reason="tool_calls",
                message={
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "read-" + phase,
                            "type": "function",
                            "function": {
                                "name": "read_file",
                                "arguments": '{"path":"src/Demo.java"}',
                            },
                        }
                    ],
                },
            )
        elif count == 2:
            choice["message"]["content"] = (
                "prior-pass-private-thought: inspected finite source"
            )
        elif (
            generate_candidates
            and "PassLocalValidationBatch" in data["messages"][0]["content"]
        ):
            c = anchors["candidates"][0]
            choice["message"]["content"] = canonical(
                {
                    "validations": [
                        {
                            "candidateHandle": c["candidateHandle"],
                            "disposition": "VERIFIED",
                            "validationSummary": {
                                "basis": "Original null dereference",
                                "counterexampleChecked": "No null guard",
                                "counterexampleHolds": False,
                                "assumptionsResolved": True,
                            },
                            "factChecks": [
                                {
                                    "candidateHandle": c["candidateHandle"],
                                    "sourceHandle": c["codeSelections"][0][
                                        "sourceHandle"
                                    ],
                                    "candidateText": "Null input throws",
                                    "sourceQuote": source.strip(),
                                    "outcome": "SUPPORTED",
                                    "explanation": "Original source dereferences x without a guard",
                                }
                            ],
                        }
                    ]
                }
            ).decode()
        else:
            submission = {
                "decision": "COMPLETE",
                "evidenceCandidates": [],
                "candidates": [],
                "checkedScope": ["src/Demo.java"],
                "scopeKind": "FINAL_FILES",
                "coverage": "FULL",
                "limitations": ["Static mock only"],
                "incompleteReasons": [],
            }
            if generate_candidates:
                code = next(
                    s
                    for s in anchors["sourceCatalog"]
                    if s["sourceKind"] == "CODE"
                    and s["sourceHandle"]
                    == max(
                        (
                            s["sourceHandle"]
                            for s in anchors["sourceCatalog"]
                            if s["sourceKind"] == "CODE"
                        ),
                        key=lambda h: int(h[1:]),
                    )
                )
                selection = {
                    "sourceHandle": code["sourceHandle"],
                    "startLine": 1,
                    "endLine": 1,
                }
                if (
                    find_candidates == "old-source-correction"
                    and phase == "consistency"
                    and count == 3
                ):
                    selection["sourceHandle"] = "s2"
                if (
                    find_candidates == "old-source-correction"
                    and phase == "consistency"
                    and count == 4
                ):
                    assert "SOURCE_NOT_OBSERVED_IN_PASS" in json.dumps(data)

                submission["evidenceCandidates"] = [
                    {
                        "localKey": "n1",
                        "evidenceType": "CODE",
                        "selections": [selection],
                        "coverage": "FULL",
                        "summary": "Original null dereference",
                    }
                ]
                submission["candidates"] = [
                    {
                        "passId": phase,
                        "category": "Null handling",
                        "title": "Null input throws",
                        "claim": "Null input throws",
                        "severity": "MEDIUM",
                        "confidence": 0.9,
                        "evidenceLinks": [{"kind": "NEW", "key": "n1"}],
                        "codeSelections": [selection],
                        "reasoningSummary": {
                            "rule": "Handle null",
                            "trigger": "x is null",
                            "executionPath": "x.intValue()",
                            "assumptions": [],
                            "counterexamples": ["A caller may reject null"],
                            "conclusion": "Null behavior regresses",
                        },
                        "suggestion": "Restore the guard",
                        "claimKind": "STATIC_CORRECTNESS",
                    }
                ]
            choice["message"]["content"] = canonical(submission).decode()
        return httpx.Response(200, stream=httpx.ByteStream(canonical(result)))

    adapter = DeepSeekAdapter(
        SecretStr("offline-only"), transport=httpx.MockTransport(server)
    )
    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        artifacts,
        checkpoints,
        adapter,
        evidence=evidence,
        model_calls=ModelCallStore(database),
        state_type=MultipassState,
    )
    executor = MultipassExecutor(engine, evidence, PassStore(database, runs), ledger)
    if find_candidates == "fatal-storage":
        execute_pass = executor.investigator.execute

        async def uncertain_storage(context, signal, *, state):
            from review_harness.domain.graph import graph_error

            if state.activePass == "security":
                raise graph_error("EVIDENCE_STORE_WRITE_UNCERTAIN")
            return await execute_pass(context, signal, state=state)

        executor.investigator.execute = uncertain_storage
    try:
        if find_candidates == "fatal-storage":
            from review_harness.domain.model import HarnessError

            with pytest.raises(HarnessError, match="EVIDENCE_STORE_WRITE_UNCERTAIN"):
                await executor.execute(context, signal)
            assert {phase for phase, _ in sent} == {"correctness", "consistency"}
            return
        package = await executor.execute(context, signal)
        assert [p.status for p in package.passResults] == (
            [
                "COMPLETED",
                "COMPLETED",
                "INCOMPLETE",
                "COMPLETED",
                "COMPLETED",
                "COMPLETED",
            ]
            if rejected_pass
            else ["COMPLETED"] * 6
        )
        if rejected_pass:
            assert (
                "MODEL_OUTPUT_REJECTED_PROVIDER_STRUCTURED_OUTPUT_INVALID"
                in package.passResults[2].incompleteReasons
            )
            assert package.coverageStatus == "SELECTED_INCOMPLETE"
        corrected = find_candidates == "old-source-correction"
        expected_order = []
        for phase in package.reviewPlan.selectedPasses:
            count = 4 if generate_candidates else 3
            if rejected_pass and phase == "security":
                count = (
                    5  # read, ready, initial rejection + two corrections; no validation
                )
            if corrected and phase == "consistency":
                count += 1
            expected_order.extend([phase] * count)
        if generate_candidates:
            expected_order.append("testing")  # Dedup has its own paid-call phase.
        assert [phase for phase, _ in sent] == expected_order
        assert len(set(first_handles)) == 6
        assert package.diagnostics.feeSummary.spentMicros > 0
        assert len(package.findings) == (1 if generate_candidates else 0)
        if generate_candidates:
            assert len(package.findings[0].contributions) == (5 if rejected_pass else 6)
            assert package.diagnostics.dedupUsage.modelCalls == 1
        await executor.cleanup(context)
        await workspaces.cleanup(run)
        completed, won = await runs.complete_success(run.run_id, package)
        assert won and completed.result_version == "3.0"
        raw, digest = await runs.get_result(run.run_id)
        from review_harness.domain.model import sha256
        from review_harness.domain.multipass import ReviewResultV3

        delivered = ReviewResultV3.model_validate(json.loads(raw))
        assert delivered.reviewPlan == package.reviewPlan and digest == sha256(raw)
        assert (await runs.get_run(run.run_id)).status == "SUCCEEDED"
        events = await runs.read_events(run.run_id, 0, 128)
        assert any(
            event["eventType"] == "REVIEW_PASS_UPDATED"
            and any(
                p["status"] == "RUNNING" and p["usage"]["modelCalls"] == 1
                for p in event["payload"]["passes"]
            )
            for event in events
        )
    finally:
        await executor.cleanup(context)
        await workspaces.cleanup(run)
        await checkpoints.close()


def test_dedup_preserves_different_triggers_and_deterministic_primary_lineage():
    from review_harness.domain.multipass_state import PassFinding
    from review_harness.runtime.dedup import DedupDecision, merge_group, recall_groups
    from tests.integration.test_multipass_contracts import result_with_finding

    original = result_with_finding()["findings"][0]
    first = PassFinding.model_validate(
        {k: v for k, v in original.items() if k != "contributions"}
    )
    second = first.model_copy(
        update={
            "candidateId": str(uuid4()),
            "findingId": str(uuid4()),
            "passId": "security",
        }
    )
    assert recall_groups([first, second]) == [[first, second]]
    handles = {first.candidateId: "c1", second.candidateId: "c2"}
    decision = DedupDecision(
        candidateHandles=["c1", "c2"],
        sameRootCause=True,
        sameTrigger=True,
        sameFix=True,
        rationale="Same null path and guard fix",
    )
    merged = merge_group([second, first], decision=decision, handles=handles)
    assert merged.candidateId == first.candidateId and merged.passId == "correctness"
    assert [c.passId for c in merged.contributions] == ["correctness", "security"]
    different = second.model_copy(
        update={
            "reasoningSummary": second.reasoningSummary.model_copy(
                update={"trigger": "Different precondition"}
            )
        }
    )
    with pytest.raises(ValueError):
        merge_group(
            [first, different],
            decision=decision.model_copy(update={"sameTrigger": False}),
            handles=handles,
        )


@pytest.mark.parametrize(
    "amount",
    [
        "8",
        "0.000001",
        "cancel_after_dispatch",
        "run_deadline_before_dispatch",
        "nonbudget_before_dispatch",
    ],
)
async def test_http11_uses_authorized_production_dispatch_and_honest_empty_scope(
    database, payload, tmp_path, amount, monkeypatch
):
    import json

    import httpx
    from pydantic import SecretStr

    from review_harness.api.app import create_app
    from review_harness.domain.model import canonical, utc_now
    from tests.integration.test_deepseek_adapter import response
    from tests.integration.test_fee_ledger import profile
    from tests.integration.test_lifecycle import client_for, settings_for
    from tests.integration.test_multipass_contracts import plan_data

    price = profile()
    path = tmp_path / "price.json"
    path.write_bytes(canonical(price.model_dump()))
    settings = settings_for(database, tmp_path).model_copy(
        update={
            "execution_mode": "REAL",
            "deepseek_api_key": SecretStr("offline-only"),
            "price_profile_path": path,
            "checkpoint_database_url": f"sqlite+aiosqlite:///{tmp_path / 'cp.sqlite'}",
        }
    )
    plan = plan_data()
    plan["selectedPasses"] = ["correctness", "testing"]
    plan["feeAuthorization"].update(
        authorizationId=str(uuid4()),
        maxAmount="8"
        if amount
        in {
            "cancel_after_dispatch",
            "run_deadline_before_dispatch",
            "nonbudget_before_dispatch",
        }
        else amount,
        confirmedAt=utc_now(),
        priceProfileId=price.priceProfileId,
        priceProfileDigest=price.digest,
    )
    payload.update(
        protocolVersion="1.1",
        executionContract={"executionMode": "REAL", "resultVersion": "3.0"},
        reviewPlan=plan,
    )
    sends = []

    def server(request):
        data = json.loads(request.content)
        sends.append(data)
        result = response()
        result["choices"][0]["message"]["content"] = (
            canonical(
                {
                    "decision": "COMPLETE",
                    "evidenceCandidates": [],
                    "candidates": [],
                    "checkedScope": [],
                    "scopeKind": "FINAL_FILES",
                    "coverage": "PARTIAL",
                    "limitations": ["No material inspected"],
                    "incompleteReasons": ["NO_INSPECTED_MATERIAL"],
                }
            ).decode()
            if "response_format" in data
            else "Ready to submit incomplete scope"
        )
        return httpx.Response(200, stream=httpx.ByteStream(canonical(result)))

    app = create_app(settings, provider_transport=httpx.MockTransport(server))
    if amount == "cancel_after_dispatch":
        from review_harness.infrastructure.persistence.model_calls import ModelCallStore

        original = ModelCallStore.dispatch

        async def dispatch(self, run_id, call_id):
            confirmed = await original(self, run_id, call_id)
            await app.state.service.cancel(run_id)
            return confirmed

        monkeypatch.setattr(ModelCallStore, "dispatch", dispatch)
    if amount == "run_deadline_before_dispatch":
        from datetime import datetime, timedelta

        from review_harness.infrastructure.persistence.fees import FeeLedger

        original_dispatch = FeeLedger.dispatch

        async def expire_after_reservation(self, run_id, call_id):
            book = await self.get(run_id)
            assert book.reservedCalls == 1 and book.reservedMicros > 0
            at = (
                (datetime.fromisoformat(book.runStartedAt) + timedelta(seconds=10800))
                .isoformat(timespec="microseconds")
                .replace("+00:00", "Z")
            )
            self.clock = lambda: at
            return await original_dispatch(self, run_id, call_id)

        monkeypatch.setattr(FeeLedger, "dispatch", expire_after_reservation)
    if amount == "nonbudget_before_dispatch":
        from review_harness.domain.graph import graph_error
        from review_harness.infrastructure.persistence.fees import FeeLedger

        async def reject_before_dispatch(self, run_id, call_id):
            book = await self.get(run_id)
            assert book.reservedCalls == 1
            raise graph_error("PRICE_PROFILE_CHANGED")

        monkeypatch.setattr(FeeLedger, "dispatch", reject_before_dispatch)
    async with app.router.lifespan_context(app), client_for(app) as client:
        capability = (
            await client.get("/internal/v1/review-capabilities?protocolVersion=1.1")
        ).json()
        assert capability["status"] == "READY"
        created = await client.post("/internal/v1/review-runs", json=payload)
        assert created.status_code == 202, created.text
        await app.state.supervisor.join()
        status = (
            await client.get(f"/internal/v1/review-runs/{payload['runId']}")
        ).json()
        from review_harness.domain.runtime_wire import RunEventV11, RunStatusV11

        RunStatusV11.model_validate(status)
        stream = await client.get(f"/internal/v1/review-runs/{payload['runId']}/events")
        for line in stream.text.splitlines():
            if line.startswith("data: "):
                RunEventV11.model_validate(json.loads(line[6:]))
        if amount == "cancel_after_dispatch":
            from review_harness.infrastructure.persistence.fees import FeeLedger
            from review_harness.infrastructure.persistence.passes import PassStore

            assert (
                status["status"] == "CANCELLED"
                and status["resourceState"] == "RELEASED"
            ), status
            assert sends == []
            book = await FeeLedger(app.state.database).get(payload["runId"])
            assert (
                book.reservedMicros > 0
                and book.stopReason == "FEE_REMOTE_OUTCOME_UNKNOWN"
            )
            facts = await PassStore(
                app.state.database, app.state.service.store
            ).list_for_run(payload["runId"])
            assert facts[0].status == "INCOMPLETE" and facts[0].usage.modelCalls == 1
            assert all(f.status != "RUNNING" for f in facts)
            return
        if amount == "nonbudget_before_dispatch":
            from review_harness.infrastructure.persistence.passes import PassStore

            assert (
                status["status"] == "FAILED" and status["resourceState"] == "RELEASED"
            )
            assert status["failure"]["code"] == "PRICE_PROFILE_CHANGED"
            assert status["result"] is None and sends == []
            book = await FeeLedger(app.state.database).get(payload["runId"])
            assert (
                book.reservedMicros == 0
                and book.stopReason == "PROVIDER_CALL_NOT_DISPATCHED"
            )
            facts = await PassStore(
                app.state.database, app.state.service.store
            ).list_for_run(payload["runId"])
            assert facts[-1].reason == "REVIEW_RUN_INTERRUPTED"
            return
        assert status["status"] == "SUCCEEDED", status
        result = (
            await client.get(f"/internal/v1/review-runs/{payload['runId']}/result")
        ).json()
        assert result["coverageStatus"] == "SELECTED_INCOMPLETE"
        if amount == "run_deadline_before_dispatch":
            from review_harness.domain.multipass import ReviewResultV3
            from review_harness.infrastructure.persistence.model_calls import (
                ModelCallStore,
            )
            from review_harness.infrastructure.persistence.passes import PassStore

            assert sends == []
            assert status["resourceState"] == "RELEASED"
            ReviewResultV3.model_validate(result)
            book = await FeeLedger(app.state.database).get(payload["runId"])
            assert book.stopReason == "RUN_TIME_BUDGET_EXHAUSTED"
            assert (
                book.reservedMicros
                == book.spentMicros
                == book.modelCalls
                == book.reservedCalls
                == 0
            )
            calls = await ModelCallStore(app.state.database).list_for_run(
                payload["runId"]
            )
            assert len(calls) == 1 and calls[0].status == "LOCAL_CLOSED"
            assert not calls[0].remoteOutcomeUnknown
            assert (
                await FeeLedger(app.state.database).reservation(
                    payload["runId"], calls[0].modelCallId
                )
            ).state == "REFUNDED"
            assert result["passResults"][0]["status"] == "INCOMPLETE"
            assert result["passResults"][0]["checkedScope"] == []
            assert (
                "RUN_TIME_BUDGET_EXHAUSTED"
                in result["passResults"][0]["incompleteReasons"]
            )
            assert result["passResults"][-1]["skipReason"] == "RUN_BUDGET_EXHAUSTED"
            facts = await PassStore(
                app.state.database, app.state.service.store
            ).list_for_run(payload["runId"])
            assert (
                facts[0].status == "INCOMPLETE"
                and facts[-1].reason == "RUN_BUDGET_EXHAUSTED"
            )
            assert all(f.usage.modelCalls == 0 for f in facts)
            # Preserve fresh production output for cross-language offline verification.
            (tmp_path / "deadline-result.json").write_bytes(canonical(result))
            (tmp_path / "deadline-progress.json").write_bytes(
                canonical([f.progress() for f in facts])
            )
            return
        assert all(p["checkedScope"] == [] for p in result["passResults"])
        assert len(sends) == (4 if amount == "8" else 0)
        assert result["passResults"][1]["skipReason"] == "USER_NOT_SELECTED"
        if amount != "8":
            assert result["passResults"][-1]["skipReason"] == "RUN_BUDGET_EXHAUSTED"


async def test_recovery_interrupts_pass_facts_without_checkpoint_or_model_replay(
    database, payload, workspaces
):
    from review_harness.application.recovery import recover_runs
    from review_harness.infrastructure.persistence.passes import PassStore
    from tests.integration.test_fee_ledger import paid_run

    runs, _ = await paid_run(database, payload)
    facts = PassStore(database, runs)
    await facts.initialize(payload["runId"])
    await facts.begin(payload["runId"], "correctness", 1, "sha256:" + "a" * 64)

    class NoReplay:
        async def ensure_scheduled(self, run):
            raise AssertionError("Running review must not be replayed")

    await recover_runs(runs, NoReplay(), workspaces)
    assert (await runs.get_run(payload["runId"])).status == "FAILED"
    recovered = await facts.list_for_run(payload["runId"])
    assert recovered[0].status == "INCOMPLETE"
    assert all(
        f.status == "SKIPPED" and f.reason == "REVIEW_RUN_INTERRUPTED"
        for f in recovered[1:]
    )


@pytest.mark.parametrize("after_commit", [False, True])
async def test_pass_commit_loss_never_splits_progress_and_ordered_history(
    database, payload, after_commit
):
    from review_harness.domain.model import HarnessError
    from review_harness.infrastructure.persistence.passes import PassStore
    from review_harness.infrastructure.persistence.store import commit
    from tests.integration.test_fee_ledger import paid_run

    runs, _ = await paid_run(database, payload)

    async def boundary(connection):
        if after_commit:
            await commit(connection)
        raise RuntimeError("Lost write acknowledgment")

    store = PassStore(database, runs, commit_transaction=boundary)
    if after_commit:
        assert len(await store.initialize(payload["runId"])) == 6
        assert (await runs.get_run(payload["runId"])).last_event_sequence == 3
    else:
        with pytest.raises(HarnessError, match="PASS_FACT_WRITE_UNCERTAIN"):
            await store.initialize(payload["runId"])
        assert await store.list_for_run(payload["runId"]) == []
        assert (await runs.get_run(payload["runId"])).last_event_sequence == 2


async def test_unsent_pass_deadline_refunds_and_can_only_advance_to_next_pass(
    database, payload
):
    from review_harness.domain.model import HarnessError
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from tests.integration.test_fee_ledger import intent, paid_run

    _, fees = await paid_run(database, payload)
    calls = ModelCallStore(database)
    call = await calls.prepare(intent(payload))
    await calls.close(call.runId, call.modelCallId, "PASS_TIME_BUDGET_EXHAUSTED")
    assert (await fees.get(call.runId)).reservedMicros == 0
    with pytest.raises(HarnessError):
        await calls.prepare(intent(payload))
    await fees.begin_phase(call.runId, "consistency")
    assert (await fees.get(call.runId)).stopReason is None
    assert (await fees.get(call.runId)).modelCalls == 0


async def test_failed_pass_preserves_over_window_observed_usage(database, payload):
    from review_harness.domain.model import HarnessError
    from review_harness.domain.provider import ProviderUsage
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from review_harness.infrastructure.persistence.passes import PassStore
    from tests.integration.test_fee_ledger import intent, paid_run

    runs, _ = await paid_run(database, payload)
    passes = PassStore(database, runs)
    await passes.initialize(payload["runId"])
    await passes.begin(payload["runId"], "correctness", 1, "sha256:" + "a" * 64)
    calls = ModelCallStore(database)
    call = await calls.prepare(intent(payload))
    await calls.dispatch(call.runId, call.modelCallId)
    with pytest.raises(HarnessError, match="FEE_RESERVATION_OVERRUN"):
        await calls.returned(
            call.runId,
            call.modelCallId,
            ProviderUsage(
                prompt_tokens=1000000000, completion_tokens=0, total_tokens=1000000000
            ),
        )
    await calls.close(call.runId, call.modelCallId)
    facts = await passes.interrupt(call.runId)
    assert facts[0].usage.inputTokens == 1000000000
    assert facts[0].usage.modelCalls == 1


async def test_expired_run_before_first_send_delivers_incomplete_without_fake_scope(
    database, payload, workspaces, tmp_path
):
    import asyncio
    from datetime import datetime, timedelta

    import httpx
    from pydantic import SecretStr

    from review_harness.application.evidence import EvidenceService
    from review_harness.domain.multipass_state import MultipassState
    from review_harness.infrastructure.artifacts import ArtifactService
    from review_harness.infrastructure.checkpoints import Checkpoints
    from review_harness.infrastructure.persistence.model_calls import ModelCallStore
    from review_harness.infrastructure.persistence.passes import PassStore
    from review_harness.runtime.context import ContextEngine
    from review_harness.runtime.deepseek import DeepSeekAdapter
    from review_harness.runtime.multipass import MultipassExecutor
    from tests.integration.test_fee_ledger import paid_run

    runs, fees = await paid_run(database, payload)
    book = await fees.get(payload["runId"])
    expired = (
        (datetime.fromisoformat(book.runStartedAt) + timedelta(seconds=10800))
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )
    fees.clock = lambda: expired
    run = await runs.get_run(payload["runId"])
    signal = asyncio.Event()
    context = await workspaces.prepare(run, signal)
    checkpoints = Checkpoints(tmp_path / "deadline.sqlite")
    await checkpoints.open()
    artifacts = ArtifactService(workspaces.settings, database, runs, workspaces)
    evidence = EvidenceService(
        workspaces.settings, database, runs, workspaces, artifacts
    )

    def server(request):
        raise AssertionError("expired run cannot send")

    engine = ContextEngine(
        workspaces.settings,
        runs,
        workspaces,
        artifacts,
        checkpoints,
        DeepSeekAdapter(
            SecretStr("offline-only"), transport=httpx.MockTransport(server)
        ),
        model_calls=ModelCallStore(database),
        state_type=MultipassState,
    )
    executor = MultipassExecutor(engine, evidence, PassStore(database, runs), fees)
    try:
        result = await executor.execute(context, signal)
        assert result.coverageStatus == "SELECTED_INCOMPLETE"
        assert result.passResults[0].status == "INCOMPLETE"
        assert result.passResults[0].checkedScope == []
        assert all(
            p.status == "SKIPPED" and p.skipReason == "RUN_BUDGET_EXHAUSTED"
            for p in result.passResults[1:]
        )
        assert result.diagnostics.modelCallIds == []
    finally:
        await executor.cleanup(context)
        await workspaces.cleanup(run)
        await checkpoints.close()
