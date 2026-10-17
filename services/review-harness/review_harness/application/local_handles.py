"""Project local names and resolve them back to existing trusted contracts."""

from uuid import uuid4

from pydantic import ValidationError

from review_harness.domain.graph import GraphMessage, graph_error
from review_harness.domain.handles import (
    ConfirmedCandidate,
    EvidenceReceipt,
    SubmissionReceipt,
)
from review_harness.domain.model import canonical, sha256
from review_harness.domain.multipass_state import (
    MultipassRegistry,
    MultipassState,
    PassCandidate,
    PassConfirmedCandidate,
    PassValidationBatch,
    current_candidates,
)
from review_harness.domain.review import EvidenceCandidate, ReviewCandidate
from review_harness.domain.submission import SubmissionIssue


def candidate_view(registry, candidate):
    handle = next(
        c.candidateHandle
        for c in registry.candidates
        if c.candidateId == candidate.candidateId
    )
    return (
        PassConfirmedCandidate
        if isinstance(registry, MultipassRegistry)
        else ConfirmedCandidate
    ).model_validate(
        {
            **candidate.model_dump(
                exclude={"candidateId", "evidenceIds", "codeReferences"}
            ),
            "candidateHandle": handle,
            "evidenceLinks": [
                {"kind": "SAVED", "handle": registry.evidence_handle(e)}
                for e in candidate.evidenceIds
            ],
            "codeSelections": [
                next(
                    s
                    for e in registry.evidence
                    if e.evidenceId in candidate.evidenceIds
                    for s in e.selections
                    if registry.resolve(s) == r
                ).model_dump(exclude_none=True)
                for r in candidate.codeReferences
            ],
        }
    )


async def model_handle_view(state, evidence, run):
    """Current candidates and recent receipts; persistent registry is never pruned."""
    registry = state.handleRegistry
    wanted = {e.evidenceId for e in registry.evidence[-16:]} | {
        e for c in current_candidates(state) for e in c.evidenceIds
    }
    sources = {
        s.sourceHandle
        for s in registry.sources[-16:]
        if not isinstance(registry, MultipassRegistry) or s.passId == state.activePass
    }
    sources.update(
        s.sourceHandle
        for s in registry.sources
        if s.source.sourceType == "USER_INPUT"
        and (
            not isinstance(registry, MultipassRegistry) or s.passId == state.activePass
        )
    )
    records = []
    for binding in registry.evidence:
        if binding.evidenceId not in wanted:
            continue
        record = await evidence.get(run, binding.evidenceId)
        selections = binding.selections
        if [registry.resolve(s) for s in selections] != record.sourceRefs:
            raise graph_error("HANDLE_SOURCE_INTEGRITY_FAILED")
        sources.update(s.sourceHandle for s in selections)
        records.append(
            {
                "evidenceHandle": binding.evidenceHandle,
                "evidenceType": record.evidenceType,
                "coverage": record.coverage,
                "summary": record.summary,
                "selections": [s.model_dump(exclude_none=True) for s in selections],
            }
        )
    candidates = [
        candidate_view(registry, c).model_dump(exclude_none=True)
        for c in current_candidates(state)
    ]
    return {
        "sourceCatalog": [
            s.view() for s in registry.sources if s.sourceHandle in sources
        ],
        "evidenceRecords": records,
        "candidates": candidates,
    }


async def prepare_local_submission(
    executor, context, state, submission, response, signal, scope
):
    """No writes until all references, original materials and candidates pass."""
    registry = state.handleRegistry
    if (registry.runId, registry.snapshotId) != (
        context.run_id,
        context.run.snapshot_id,
    ):
        raise graph_error("HANDLE_IDENTITY_MISMATCH")
    keys = [e.localKey for e in submission.evidenceCandidates]
    if len(keys) != len(set(keys)):
        raise SubmissionIssue("LOCAL_KEY_DUPLICATE", path=("evidenceCandidates",))
    if len(state.evidenceRefs) + len(keys) > 128:
        raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
    if len(state.candidates) + len(submission.candidates) > 32:
        raise graph_error("FINDING_LIMIT_EXCEEDED")
    inputs = []
    for index, local in enumerate(submission.evidenceCandidates):
        try:
            if isinstance(state, MultipassState) and any(
                registry.source_binding(s.sourceHandle).passId != state.activePass
                for s in local.selections
            ):
                raise SubmissionIssue("SOURCE_NOT_OBSERVED_IN_PASS")
            refs = [registry.resolve(s) for s in local.selections]
            if len({canonical(r.model_dump()) for r in refs}) != len(refs):
                raise SubmissionIssue("SOURCE_SELECTION_DUPLICATE")
            inputs.append(
                EvidenceCandidate.model_validate(
                    {
                        **local.model_dump(exclude={"localKey", "selections"}),
                        "sourceRefs": [r.model_dump() for r in refs],
                    }
                )
            )
        except SubmissionIssue as error:
            raise error.at("evidenceCandidates", index) from None
        except ValidationError:
            raise SubmissionIssue(
                "SOURCE_KIND_UNSUPPORTED", path=("evidenceCandidates", index)
            ) from None
    prepared = await executor.evidence.prepare_batch(
        context, inputs, response.modelCallId, signal
    )
    new = dict(zip(keys, prepared, strict=True))
    saved = {}
    candidates = []
    for index, local in enumerate(submission.candidates):
        try:
            links = [canonical(link.model_dump()) for link in local.evidenceLinks]
            if len(set(links)) != len(links):
                raise SubmissionIssue("EVIDENCE_LINK_DUPLICATE")
            records = []
            for link in local.evidenceLinks:
                if link.kind == "NEW":
                    if link.key not in new:
                        raise SubmissionIssue("LOCAL_KEY_UNKNOWN")
                    records.append(new[link.key])
                else:
                    identity = registry.evidence_id(link.handle)
                    if identity not in saved:
                        saved[identity] = await executor.evidence.get(
                            context.run, identity
                        )
                    records.append(saved[identity])
            if len({r.evidenceId for r in records}) != len(records):
                raise SubmissionIssue("EVIDENCE_LINK_DUPLICATE")
            sources = []
            for record in records:
                if record.evidenceId in saved:
                    selections = next(
                        e.selections
                        for e in registry.evidence
                        if e.evidenceId == record.evidenceId
                    )
                else:
                    key = next(
                        k for k, r in new.items() if r.evidenceId == record.evidenceId
                    )
                    selections = submission.evidenceCandidates[
                        keys.index(key)
                    ].selections
                sources.extend(zip(record.sourceRefs, selections, strict=True))
            code = []
            for selection in local.codeSelections:
                selected = registry.resolve(selection)
                if selected.sourceType != "CODE":
                    raise SubmissionIssue("CANDIDATE_CODE_REQUIRED")
                eligible = [
                    r
                    for r, s in sources
                    if s.sourceHandle == selection.sourceHandle
                    and r.sourceType == "CODE"
                    and (r.path, r.side, r.sha256)
                    == (selected.path, selected.side, selected.sha256)
                    and r.startLine
                    <= selected.startLine
                    <= selected.endLine
                    <= r.endLine
                ]
                if not eligible:
                    raise SubmissionIssue("REFERENCE_NOT_IN_EVIDENCE")
                # Result requires exact Evidence membership: choose the smallest
                # acknowledged containing interval, never an unseen larger range.
                code.append(
                    min(eligible, key=lambda r: (r.endLine - r.startLine, r.startLine))
                )
            if len({canonical(r.model_dump()) for r in code}) != len(code):
                raise SubmissionIssue("SOURCE_SELECTION_DUPLICATE")
            if code and not any(r.path in scope["targetPaths"] for r in code):
                raise SubmissionIssue("SCOPE_INVALID", code="FINDING_SCOPE_VIOLATION")
            if isinstance(state, MultipassState) and local.passId != state.activePass:
                raise SubmissionIssue(
                    "PASS_ID_MISMATCH", code="FINDING_SCOPE_VIOLATION"
                )
            candidate = (
                PassCandidate if isinstance(state, MultipassState) else ReviewCandidate
            ).model_validate(
                {
                    **local.model_dump(exclude={"evidenceLinks", "codeSelections"}),
                    "candidateId": str(uuid4()),
                    "evidenceIds": [r.evidenceId for r in records],
                    "codeReferences": [r.model_dump() for r in code],
                }
            )
            candidates.append(candidate)
        except SubmissionIssue as error:
            raise error.at("candidates", index) from None
    evidence_bindings = [e.model_dump() for e in registry.evidence]
    receipts = []
    for key, record in new.items():
        handle = f"e{len(evidence_bindings) + 1}"
        evidence_bindings.append(
            {
                "evidenceHandle": handle,
                "evidenceId": record.evidenceId,
                "selections": [
                    s.model_dump(exclude_none=True)
                    for s in submission.evidenceCandidates[keys.index(key)].selections
                ],
            }
        )
        receipts.append(EvidenceReceipt(localKey=key, evidenceHandle=handle))
    candidate_bindings = [c.model_dump() for c in registry.candidates]
    handles = []
    for candidate in candidates:
        handle = f"c{len(candidate_bindings) + 1}"
        candidate_bindings.append(
            {"candidateHandle": handle, "candidateId": candidate.candidateId}
        )
        handles.append(handle)
    registry = type(registry).model_validate(
        {
            **registry.model_dump(),
            "evidence": evidence_bindings,
            "candidates": candidate_bindings,
        }
    )
    receipt = SubmissionReceipt(
        modelCallId=response.modelCallId,
        submissionDigest=sha256(canonical(submission.model_dump())),
        checkpointRevision=state.revision + 1,
        newEvidence=receipts,
        candidateHandles=handles,
    )
    # Construct/validate the complete candidate State before publishing facts.
    candidate_state = state.updated(
        revision=state.revision + 1,
        handleRegistry=registry.model_dump(),
        lastSubmissionReceipt=receipt.model_dump(),
        evidenceRefs=[*state.evidenceRefs, *[r.evidenceId for r in prepared]],
        candidateRefs=[*state.candidateRefs, *[c.candidateId for c in candidates]],
        candidates=[c.model_dump() for c in [*state.candidates, *candidates]],
        messages=[
            *[m.model_dump() for m in state.messages],
            GraphMessage(
                messageId=str(uuid4()),
                role="user",
                content=canonical(
                    {
                        "untrustedPlatformReceipt": True,
                        "newEvidence": [r.model_dump() for r in receipts],
                        "candidateHandles": handles,
                    }
                ).decode(),
            ).model_dump(),
        ],
    )
    return prepared, candidate_state, receipt


def validation_handle_view(state, materials):
    registry = state.handleRegistry
    view, allowed = [], {}
    for candidate in current_candidates(state):
        projected = candidate_view(registry, candidate)
        records = []
        handles = []
        for binding in registry.evidence:
            if binding.evidenceId not in candidate.evidenceIds:
                continue
            item = next(
                m
                for m in materials
                if m["evidence"]["evidenceId"] == binding.evidenceId
            )
            sources = []
            for selection, material in zip(
                binding.selections, item["materials"], strict=True
            ):
                sources.append(
                    {
                        **registry.source_binding(selection.sourceHandle).view(),
                        "selection": selection.model_dump(),
                        "observedRanges": [[selection.startLine, selection.endLine]]
                        if selection.startLine is not None
                        else [],
                        "text": material["text"],
                        "coverage": material["coverage"],
                        "redactionState": material["redactionState"],
                    }
                )
                if material["sourceRef"]["sourceType"] in {"CODE", "ARTIFACT"}:
                    handles.append(selection.sourceHandle)
            records.append(
                {"evidenceHandle": binding.evidenceHandle, "materials": sources}
            )
        allowed[projected.candidateHandle] = list(dict.fromkeys(handles))
        view.append({"candidate": projected.model_dump(), "evidence": records})
    return {"candidateMaterials": view, "candidateFactSources": allowed}


def resolve_validation(state, submitted, materials):
    from review_harness.domain.review import ValidationBatch

    registry = state.handleRegistry
    values = []
    handles = [v.candidateHandle for v in submitted.validations]
    if len(set(handles)) != len(handles) or set(handles) != {
        c.candidateHandle
        for c in registry.candidates
        if c.candidateId
        in {candidate.candidateId for candidate in current_candidates(state)}
    }:
        raise SubmissionIssue(
            "CANDIDATE_ID_MISMATCH",
            stage="VALIDATION",
            code="PROVIDER_VALIDATION_INVALID",
        )
    candidates = {c.candidateId: c for c in current_candidates(state)}
    for index, local in enumerate(submitted.validations):
        original = candidates[registry.candidate_id(local.candidateHandle)]

        def invalid(reason, *path):
            raise SubmissionIssue(
                reason,
                stage="VALIDATION",
                path=("validations", index, *path),
                code="PROVIDER_VALIDATION_INVALID",
            )

        checks = []
        for check_index, check in enumerate(local.factChecks):
            if check.candidateHandle != local.candidateHandle:
                invalid("FACT_CANDIDATE_HANDLE_MISMATCH", "factChecks", check_index)
            try:
                binding = registry.source_binding(check.sourceHandle)
            except SubmissionIssue:
                invalid("FACT_SOURCE_INVALID", "factChecks", check_index)
            if binding.source.sourceType == "USER_INPUT":
                invalid(
                    "FACT_IMPLEMENTATION_SOURCE_REQUIRED", "factChecks", check_index
                )
            eligible = []
            for evidence_binding in registry.evidence:
                if evidence_binding.evidenceId not in original.evidenceIds:
                    continue
                item = next(
                    m
                    for m in materials
                    if m["evidence"]["evidenceId"] == evidence_binding.evidenceId
                )
                for source_index, (selection, material) in enumerate(
                    zip(evidence_binding.selections, item["materials"], strict=True)
                ):
                    if selection.sourceHandle == check.sourceHandle:
                        eligible.append(
                            (evidence_binding.evidenceId, source_index, material)
                        )
            if not eligible:
                invalid("FACT_SOURCE_INVALID", "factChecks", check_index)
            chosen = next(
                (slot for slot in eligible if check.sourceQuote in slot[2]["text"]),
                None,
            )
            if chosen is None:
                invalid("FACT_QUOTE_INVALID", "factChecks", check_index)
            checks.append(
                {
                    **check.model_dump(exclude={"candidateHandle", "sourceHandle"}),
                    "evidenceId": chosen[0],
                    "sourceIndex": chosen[1],
                }
            )
        corrected = local.correctedCandidate
        internal_corrected = None
        if corrected:
            expected = candidate_view(registry, original)
            for field in (
                "candidateHandle",
                "passId",
                "claimKind",
                "evidenceLinks",
                "codeSelections",
            ):
                if getattr(corrected, field) != getattr(expected, field):
                    invalid("CORRECTION_SOURCE_MISMATCH", "correctedCandidate", field)
            internal_corrected = {
                **corrected.model_dump(
                    exclude={"candidateHandle", "evidenceLinks", "codeSelections"}
                ),
                "evidenceIds": original.evidenceIds,
                "codeReferences": [r.model_dump() for r in original.codeReferences],
            }
        values.append(
            {
                "candidateId": original.candidateId,
                "disposition": local.disposition,
                "validationSummary": local.validationSummary.model_dump(),
                "correctedCandidate": internal_corrected,
                "factChecks": checks,
            }
        )
    return (
        PassValidationBatch if isinstance(state, MultipassState) else ValidationBatch
    ).model_validate({"validations": values})
