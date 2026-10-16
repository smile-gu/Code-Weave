"""Confirm original provenance and audit a decision before adopting its merge."""

from uuid import uuid4

from pydantic import ValidationError

from review_harness.domain.graph import ArtifactRef, graph_error
from review_harness.domain.model import HarnessError, canonical, sha256


async def check_originals(executor, context, state, outcomes, signal):
    engine = executor.engine
    await engine.validate(context, state, signal)
    if canonical([o.model_dump() for o in outcomes]) != canonical(
        [o.model_dump() for o in state.passLedger]
    ):
        raise graph_error("DEDUP_ORIGINAL_IDENTITY_MISMATCH")
    seen_candidates, seen_findings = set(), set()
    for outcome in outcomes:
        for finding in outcome.findings:
            if (
                finding.candidateId in seen_candidates
                or finding.findingId in seen_findings
                or finding.passId != outcome.passResult.passId
                or finding.findingId not in outcome.passResult.findingIds
            ):
                raise graph_error("DEDUP_ORIGINAL_IDENTITY_MISMATCH")
            seen_candidates.add(finding.candidateId)
            seen_findings.add(finding.findingId)
            binding = next(
                (
                    c
                    for c in state.handleRegistry.candidates
                    if c.candidateId == finding.candidateId
                ),
                None,
            )
            candidate = next(
                (c for c in state.candidates if c.candidateId == finding.candidateId),
                None,
            )
            if (
                binding is None
                or candidate is None
                or candidate.passId != finding.passId
                or candidate.evidenceIds != finding.evidenceIds
                or candidate.codeReferences != finding.codeReferences
            ):
                raise graph_error("DEDUP_ORIGINAL_IDENTITY_MISMATCH")
            call = await engine.model_calls.get(
                context.run_id, finding.validationModelCallId
            )
            if (
                call.snapshotId != state.snapshotId
                or call.passId != finding.passId
                or call.purpose != "VALIDATION"
                or call.status != "LOCAL_CLOSED"
                or call.usage is None
                or call.remoteOutcomeUnknown
            ):
                raise graph_error("DEDUP_ORIGINAL_IDENTITY_MISMATCH")
            refs = []
            for eid in finding.evidenceIds:
                record = await executor.evidence.get(context.run, eid)
                refs.extend(record.sourceRefs)
                await executor.evidence.materials(
                    context.run, record.sourceRefs, signal
                )
            if any(ref not in refs for ref in finding.codeReferences):
                raise graph_error("DEDUP_ORIGINAL_IDENTITY_MISMATCH")


async def save_decision(
    engine,
    context,
    state,
    group,
    handles,
    grouping,
    decision,
    response,
    merged,
    reason,
    signal,
):
    record = {
        "recordType": "dedup_decision",
        "recordVersion": "1.0",
        **state.identity(),
        "stateRevision": state.revision,
        "purpose": "DEDUP",
        "modelCallId": response.modelCallId,
        "grouping": grouping,
        "originals": [
            {
                "candidateHandle": handle,
                "candidateId": f.candidateId,
                "finding": f.model_dump(),
                "findingSha256": sha256(canonical(f.model_dump())),
            }
            for handle, f in zip(handles, group, strict=True)
        ],
        "decision": decision.model_dump(),
        "action": "MERGED" if merged is not None else "RETAINED",
        "reason": reason,
        "primaryCandidateId": merged.candidateId if merged is not None else None,
        "mergedFinding": merged.model_dump() if merged is not None else None,
        "mergedFindingSha256": sha256(canonical(merged.model_dump()))
        if merged is not None
        else None,
    }
    raw = canonical(record)
    if len(raw) > engine.settings.artifact_max_bytes:
        return state, False
    # Prove the reference fits before publication; capacity is not a storage failure.
    placeholder = ArtifactRef(
        artifactId=str(uuid4()),
        sha256=sha256(raw),
        byteSize=len(raw),
        coverage="FULL",
        redactionState="NONE",
    )
    try:
        state.updated(
            revision=state.revision + 1, artifactRefs=[*state.artifactRefs, placeholder]
        )
    except ValidationError:
        return state, False
    try:
        artifact = await engine.artifacts.publish(
            context,
            raw.decode(),
            "TOOL_OUTPUT",
            "dedup_decision",
            signal,
            producer_type="SYSTEM",
            call_id=response.modelCallId,
            media_type="application/json",
        )
    except HarnessError as error:
        if error.code == "ARTIFACT_LIMIT_EXCEEDED":
            return state, False
        raise
    if (
        artifact.coverage != "FULL"
        or artifact.sha256 != sha256(raw)
        or artifact.byte_size != len(raw)
    ):
        raise graph_error("DEDUP_AUDIT_INTEGRITY_FAILED")
    ref = ArtifactRef.model_validate(
        {k: artifact.wire()[k] for k in ArtifactRef.model_fields}
    )
    confirmed = state.updated(
        revision=state.revision + 1, artifactRefs=[*state.artifactRefs, ref]
    )
    await engine.persist(confirmed, signal)
    # persist uses exact Checkpoint readback; no adoption on a lost confirmation.
    return confirmed, True
