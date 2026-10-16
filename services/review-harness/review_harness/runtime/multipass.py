"""Serial pass orchestration over the existing autonomous investigation gates."""

import asyncio
from uuid import uuid4

from review_harness.application.report import render_multipass_report
from review_harness.domain.graph import graph_error
from review_harness.domain.model import HarnessError, canonical, sha256, utc_now
from review_harness.domain.multipass import ReviewResultV3
from review_harness.domain.multipass_state import MultipassState
from review_harness.runtime.provider_profile import PROFILE
from review_harness.runtime.review import RealReviewExecutor


class MultipassExecutor:
    expected_contract = {"executionMode": "REAL", "resultVersion": "3.0"}
    wallclock_seconds = 10800

    def __init__(self, engine, evidence, passes, fees):
        if not issubclass(engine.state_type, MultipassState):
            raise ValueError("Multipass execution requires a versioned multipass State")
        self.engine, self.evidence, self.passes, self.fees = (
            engine,
            evidence,
            passes,
            fees,
        )
        self.investigator = RealReviewExecutor(engine, evidence)
        engine.pass_store = passes
        self.last_state = None

    async def execute(self, context, signal):
        if not await self.fees.exists(context.run_id):
            if self.fees.price_catalog is None:
                raise graph_error("PRICE_PROFILE_UNAVAILABLE")
            profile = self.fees.price_catalog.current().priceProfile
            await self.engine.call_fact(
                context, signal, self.fees.initialize(context.run_id, profile)
            )
        book = await self.fees.get(context.run_id)
        state = await self.engine.initial(context, signal)
        await self.engine.checkpoints.assert_fresh(state)
        await self.engine.persist(state, signal)
        await self.engine.call_fact(
            context, signal, self.passes.initialize(context.run_id)
        )
        outcomes = []
        for phase in state.reviewPlan.selectedPasses:
            await self.engine.active(context, signal)
            if outcomes:
                state = state.for_pass(phase, utc_now())
                state = state.updated(passLedger=[o.model_dump() for o in outcomes])
                goal, _ = await self.engine.anchors(context, signal)
                registry, _ = state.handleRegistry.register_source(
                    {"sourceType": "USER_INPUT", "sha256": sha256(goal.encode())},
                    "FULL",
                    "NONE",
                    [],
                )
                state = state.updated(handleRegistry=registry.model_dump())
                self.engine.call_counts.pop(context.run_id, None)
            book = await self.fees.get(context.run_id)
            if book.activePhase != phase:
                try:
                    await self.engine.call_fact(
                        context, signal, self.fees.begin_phase(context.run_id, phase)
                    )
                except HarnessError as error:
                    if error.code not in {
                        "RUN_TIME_BUDGET_EXHAUSTED",
                        "RUN_CALL_BUDGET_EXHAUSTED",
                        "FEE_BUDGET_EXHAUSTED",
                    }:
                        raise
                    await self.engine.call_fact(
                        context, signal, self.passes.skip_remaining(context.run_id)
                    )
                    break
            state = state.updated(
                phaseStartedAt=(await self.fees.get(context.run_id)).phaseStartedAt
            )
            await self.engine.persist(state, signal)
            await self.engine.call_fact(
                context,
                signal,
                self.passes.begin(
                    context.run_id,
                    phase,
                    state.revision,
                    sha256(canonical(state.model_dump())),
                ),
            )
            self.investigator.last_state = state
            try:
                async with asyncio.timeout(
                    await self.fees.phase_timeout(context.run_id)
                ):
                    outcome = await self.investigator.execute(
                        context, signal, state=state
                    )
            except HarnessError as error:
                if error.code not in {
                    "RUN_TIME_BUDGET_EXHAUSTED",
                    "PASS_TIME_BUDGET_EXHAUSTED",
                }:
                    raise
                outcome = await self.investigator._finish(
                    context,
                    self.investigator.last_state or state,
                    signal,
                    budget_error=error.code,
                )
            except TimeoutError:
                await self.engine.cancel_local(context.run_id)
                await self.engine.cleanup(context.run_id)
                calls = await self.engine.model_calls.list_for_run(context.run_id)
                if any(c.remoteOutcomeUnknown for c in calls):
                    raise graph_error("FEE_REMOTE_OUTCOME_UNKNOWN")
                outcome = await self.investigator._finish(
                    context,
                    self.investigator.last_state or state,
                    signal,
                    budget_error="PASS_TIME_BUDGET_EXHAUSTED",
                )
            state = self.investigator.last_state
            await self.engine.cleanup(context.run_id)
            # A released view does not clear fee/resource protection from unknown dispatch.
            book = await self.fees.get(context.run_id)
            if book.stopReason and (
                book.stopReason
                not in {
                    "PASS_TIME_BUDGET_EXHAUSTED",
                    "PASS_CALL_BUDGET_EXHAUSTED",
                    "RUN_TIME_BUDGET_EXHAUSTED",
                    "RUN_CALL_BUDGET_EXHAUSTED",
                    "FEE_BUDGET_EXHAUSTED",
                }
                or book.stopReason not in outcome.passResult.incompleteReasons
            ):
                raise graph_error(book.stopReason)
            outcomes.append(outcome)
            state = state.updated(
                revision=state.revision + 1,
                passLedger=[o.model_dump() for o in outcomes],
            )
            await self.engine.persist(state, signal)
            await self.engine.call_fact(
                context,
                signal,
                self.passes.finish(
                    context.run_id,
                    outcome.passResult,
                    state.revision,
                    sha256(canonical(state.model_dump())),
                ),
            )
            self.last_state = state
            if any(
                reason in outcome.passResult.incompleteReasons
                for reason in {
                    "FEE_BUDGET_EXHAUSTED",
                    "RUN_TIME_BUDGET_EXHAUSTED",
                    "RUN_CALL_BUDGET_EXHAUSTED",
                }
            ):
                await self.engine.call_fact(
                    context, signal, self.passes.skip_remaining(context.run_id)
                )
                break
        state, findings, dedup_limits = await self._deduplicate(
            context, state, outcomes, signal
        )
        self.last_state = state
        return await self._result(context, state, outcomes, findings, dedup_limits)

    async def _deduplicate(self, context, state, outcomes, signal):
        from review_harness.domain.model import HarnessError
        from review_harness.runtime.dedup import (
            DedupDecision,
            merge_group,
            recall_groups,
            unmerged,
        )

        source = [f for o in outcomes for f in o.findings]
        groups = recall_groups(source)
        final = {f.candidateId: unmerged(f) for f in source}
        limitations = []
        if not groups:
            return state, list(final.values()), limitations
        if len(outcomes) != len(state.reviewPlan.selectedPasses):
            return (
                state,
                list(final.values()),
                ["Possible duplicates remain; run budget prevented aggregation."],
            )
        try:
            await self.engine.call_fact(
                context, signal, self.fees.begin_phase(context.run_id, "dedup")
            )
            state = state.updated(
                aggregating=True,
                revision=state.revision + 1,
                modelCallCount=0,
                modelUsages=[],
                correctionCount=0,
                messages=[],
                summaryVersion=0,
                summaryArtifactRef=None,
                sourceDigest=None,
                sourceMessageIds=[],
                pendingToolCalls=[],
                phaseStartedAt=(await self.fees.get(context.run_id)).phaseStartedAt,
            )
            self.engine.call_counts.pop(context.run_id, None)
            await self.engine.persist(state, signal)
            for group in groups:
                handles = [
                    next(
                        c.candidateHandle
                        for c in state.handleRegistry.candidates
                        if c.candidateId == f.candidateId
                    )
                    for f in group
                ]
                original = [
                    {
                        "candidateHandle": handle,
                        "validatedFinding": f.model_dump(
                            exclude={"candidateId", "findingId"}
                        ),
                    }
                    for handle, f in zip(handles, group, strict=True)
                ]
                state, _, decision = await self.investigator._json(
                    context,
                    state,
                    signal,
                    DedupDecision,
                    "Compare only supplied validated judgments. Merge only when root cause, trigger and required fix coincide. Different triggers/fixes remain distinct; uncertainty means false. Do not create sources, change grades or rewrite findings. Return json matching "
                    + canonical(DedupDecision.model_json_schema()).decode(),
                    purpose="DEDUP",
                    messages=[
                        {"role": "user", "content": {"untrustedDedupGroup": original}}
                    ],
                    dedup_handles=handles,
                )
                if decision.sameRootCause and decision.sameTrigger and decision.sameFix:
                    try:
                        merged = merge_group(group)
                    except ValueError:
                        limitations.append(
                            "Possible duplicates retained: trigger, fix or provenance differs."
                        )
                        continue
                    for f in group:
                        final.pop(f.candidateId)
                    final[merged.candidateId] = merged
                else:
                    limitations.append(
                        "Possible duplicates retained after bounded comparison."
                    )
        except HarnessError as error:
            from review_harness.domain.submission import SubmissionIssue

            if isinstance(error, SubmissionIssue):
                state = self.investigator.last_state or state
                limitations.append(
                    "Possible duplicates retained: bounded structured comparison was rejected."
                )
                return state, list(final.values()), limitations
            if error.code not in {
                "DEDUP_CALL_BUDGET_EXHAUSTED",
                "CONTEXT_MODEL_CALL_LIMIT_EXCEEDED",
                "SUMMARY_TIME_BUDGET_EXHAUSTED",
                "RUN_CALL_BUDGET_EXHAUSTED",
                "RUN_TIME_BUDGET_EXHAUSTED",
                "FEE_BUDGET_EXHAUSTED",
                "CONTEXT_REQUEST_EXCEEDS_BUDGET",
            }:
                raise
            state = self.investigator.last_state or state
            limitations.append("Possible duplicates remain: " + error.code)
        return state, list(final.values()), list(dict.fromkeys(limitations))[:8]

    async def _result(self, context, state, outcomes, findings, dedup_limits):

        calls = await self.engine.model_calls.dispatched_for_run(context.run_id)
        if any(
            c.status != "LOCAL_CLOSED" or c.usage is None or c.remoteOutcomeUnknown
            for c in calls
        ):
            raise graph_error("PROVIDER_CALL_INTEGRITY_FAILED")
        book = await self.fees.get(context.run_id)
        facts = await self.passes.list_for_run(context.run_id)
        results = []
        for fact in facts:
            if fact.result:
                results.append(fact.result)
            else:
                results.append(
                    {
                        "passId": fact.passId,
                        "selected": fact.selected,
                        "status": "SKIPPED",
                        "scopeKind": "FINAL_FILES",
                        "checkedScope": [],
                        "coverage": "PARTIAL",
                        "limitations": ["This pass was not executed."],
                        "incompleteReasons": [],
                        "skipReason": fact.reason,
                        "unvalidatedCandidates": 0,
                        "findingIds": [],
                        "usage": {"modelCalls": 0, "inputTokens": 0, "outputTokens": 0},
                    }
                )
        coverage = (
            "SELECTED_COMPLETE"
            if all(o.passResult.status == "COMPLETED" for o in outcomes)
            and len(outcomes) == len(state.reviewPlan.selectedPasses)
            else "SELECTED_INCOMPLETE"
        )
        from review_harness.domain.multipass import MultipassPassResult

        results = [MultipassPassResult.model_validate(r) for r in results]
        results = [
            MultipassPassResult.model_validate(
                {
                    **p.model_dump(),
                    "findingIds": [
                        f.findingId
                        for f in findings
                        if any(c.passId == p.passId for c in f.contributions)
                    ],
                }
            )
            for p in results
        ]
        dedup_calls = [c for c in calls if c.passId == "dedup"]
        report = render_multipass_report(results, findings, coverage)
        return ReviewResultV3.model_validate(
            {
                "resultVersion": "3.0",
                "resultId": str(uuid4()),
                "runId": context.run_id,
                "snapshotId": state.snapshotId,
                "manifestSha256": state.manifestSha256,
                "generatedAt": utc_now(),
                "reviewPlan": state.reviewPlan.model_dump(),
                "coverageStatus": coverage,
                "passResults": [p.model_dump() for p in results],
                "evidence": [
                    e.model_dump()
                    for e in await self.evidence.list_for_run(context.run)
                ],
                "findings": [f.model_dump() for f in findings],
                "reportDraft": report.model_dump(),
                "diagnostics": {
                    "executionMode": "REAL",
                    "contextManifestArtifactIds": [
                        c.manifestRef.artifactId for c in calls
                    ],
                    "modelCallIds": [c.modelCallId for c in calls],
                    "meterVersion": self.engine.adapter.capabilities.meter_version,
                    "limitations": [*PROFILE["limitations"], *dedup_limits],
                    "dedupUsage": {
                        "modelCalls": len(dedup_calls),
                        "inputTokens": sum(c.usage.prompt_tokens for c in dedup_calls),
                        "outputTokens": sum(
                            c.usage.completion_tokens for c in dedup_calls
                        ),
                    },
                    "feeSummary": {
                        "authorizationId": book.reviewPlan.feeAuthorization.authorizationId,
                        "currency": "CNY",
                        "maxAmountMicros": book.reviewPlan.feeAuthorization.amount_micros,
                        "spentMicros": book.spentMicros,
                        "reservedMicros": book.reservedMicros,
                        "overrunMicros": book.overrunMicros,
                        "unknownCalls": 0,
                    },
                },
            }
        )

    async def cleanup(self, context):
        await self.engine.cleanup(context.run_id)
        for call in await self.engine.model_calls.list_for_run(context.run_id):
            if call.status != "LOCAL_CLOSED":
                await self.engine.call_fact(
                    context,
                    asyncio.Event(),
                    self.engine.model_calls.close(
                        context.run_id, call.modelCallId, "PROVIDER_CALL_INTERRUPTED"
                    ),
                )
        facts = await self.passes.list_for_run(context.run_id)
        if any(f.status in {"READY", "RUNNING"} for f in facts):
            current = await self.engine.runs.get_run(context.run_id)
            await self.passes.interrupt(
                context.run_id,
                "REVIEW_RUN_CANCELLED"
                if current.status == "CANCELLING"
                else "REVIEW_RUN_INTERRUPTED",
            )

    async def abort(self, run_id):
        await self.engine.cancel_local(run_id)


class VersionedReviewExecutor:
    """Explicit protocol dispatch, retaining the original single-pass executor."""

    def __init__(self, runs, single, multipass):
        self.runs, self.single, self.multipass = runs, single, multipass

    def select(self, run):
        executor = self.multipass if run.protocol_version == "1.1" else self.single
        if run.execution_contract != executor.expected_contract:
            raise graph_error("EXECUTION_CONTRACT_UNSUPPORTED")
        return executor

    def timeout_for(self, run):
        return self.select(run).wallclock_seconds

    async def execute(self, context, signal):
        return await self.select(context.run).execute(context, signal)

    async def cleanup(self, context):
        await self.select(context.run).cleanup(context)

    async def abort(self, run_id):
        await self.select(await self.runs.get_run(run_id)).abort(run_id)
