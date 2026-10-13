"""Bounded, transactional view replacement; no recursive summarizer or hidden retry."""

import json

from pydantic import ValidationError

from review_harness.application.redaction import redact
from review_harness.domain.graph import ArtifactRef, Summary, graph_error, pending_calls
from review_harness.domain.model import canonical, sha256, utc_now
from review_harness.domain.provider import CompactionText
from review_harness.runtime.model_adapter import TestWindowExceeded


def closed_prefixes(messages):
    pending_calls(messages)  # Reject malformed identities before selecting any range.
    boundaries = []
    for index in range(1, len(messages) + 1):
        if not pending_calls(messages[:index]):
            boundaries.append(index)
    # Retain the most recent closed interaction unit, and any open tail.
    return boundaries[:-1]


class Compactor:
    def __init__(self, engine):
        self.engine = engine

    async def compact(self, context, state, signal):
        engine = self.engine
        if state.compactionCount >= engine.settings.compaction_max_successes:
            raise graph_error("COMPACTION_LIMIT_EXCEEDED")
        if state.pendingToolCalls:
            raise graph_error("COMPACTION_UNCLOSED_TOOLS")
        baseline = await engine.build(context, state, signal)
        baseline_units = engine.adapter.count_request(baseline)
        prefix = None
        for end in reversed(closed_prefixes(state.messages)):
            candidate = state.messages[:end]
            request = await engine.build(
                context, state, signal, purpose="COMPACTION", messages=candidate
            )
            if engine.input_cost(request) <= engine.adapter.capabilities.input_budget:
                prefix = candidate
                break
        if not prefix:
            raise graph_error("COMPACTION_SOURCE_EXCEEDS_BUDGET")
        # One trusted collection feeds both the durable Summary and retained State.
        refs = {r.artifactId: r for r in state.artifactRefs}
        sources = {canonical(r.model_dump()): r for r in state.sourceRefs}
        evidence_ids, candidate_ids = set(state.evidenceRefs), set(state.candidateRefs)
        if state.summaryArtifactRef:
            page = await engine.artifacts.content(
                state.runId, state.summaryArtifactRef.artifactId, signal=signal
            )
            if page.hasMore or page.coverage != "FULL":
                raise graph_error("COMPACTION_SUMMARY_INVALID")
            previous = Summary.model_validate(json.loads(page.text))
            sources.update((canonical(r.model_dump()), r) for r in previous.sourceRefs)
            evidence_ids.update(previous.referencedEvidenceIds)
            candidate_ids.update(previous.referencedCandidateIds)
            # baseline build already confirms previous IDs belong to retained State.
            if not set(previous.referencedArtifactIds) <= refs.keys():
                raise graph_error("COMPACTION_REFERENCE_INVALID")
        for item in prefix:
            refs.update((r.artifactId, r) for r in item.artifactRefs)
            sources.update((canonical(r.model_dump()), r) for r in item.sourceRefs)
        if (
            len(refs) > 64
            or len(sources) > 64
            or len(evidence_ids) > 128
            or len(candidate_ids) > 32
        ):
            raise graph_error("COMPACTION_REFERENCE_LIMIT_EXCEEDED")
        anchored = state.updated(
            artifactRefs=[r.model_dump() for r in refs.values()],
            sourceRefs=[r.model_dump() for r in sources.values()],
            evidenceRefs=sorted(evidence_ids),
            candidateRefs=sorted(candidate_ids),
        )
        await engine.validate(context, anchored, signal)
        # invoke() governs the summarizer just like every investigation call.
        try:
            called, response = await engine.invoke(
                context, state, signal, purpose="COMPACTION", messages=prefix
            )
        except TestWindowExceeded:
            raise graph_error("COMPACTION_MODEL_WINDOW_EXCEEDED") from None
        if response.toolCalls:
            raise graph_error("COMPACTION_SUMMARY_INVALID")
        text, redaction = redact(response.content)
        try:
            CompactionText(summaryText=text)
        except ValidationError:
            raise graph_error("COMPACTION_SUMMARY_INVALID")
        if engine.adapter.capabilities.meter_id != "V41_REFERENCE_LOCAL_TOKENS" and (
            not set(response.referencedArtifactIds) <= refs.keys()
            or any(
                canonical(r.model_dump()) not in sources for r in response.sourceRefs
            )
        ):
            raise graph_error("COMPACTION_REFERENCE_INVALID")
        summary = Summary(
            summaryVersion=state.summaryVersion + 1,
            sourceMessageIds=[m.messageId for m in prefix],
            sourceDigest=sha256(
                canonical(
                    {
                        "previousSummary": state.summaryArtifactRef.model_dump()
                        if state.summaryArtifactRef
                        else None,
                        "messages": [m.model_dump() for m in prefix],
                    }
                )
            ),
            previousSummaryVersion=state.summaryVersion,
            summaryText=text,
            referencedArtifactIds=sorted(refs),
            sourceRefs=list(sources.values()),
            referencedEvidenceIds=sorted(evidence_ids),
            referencedCandidateIds=sorted(candidate_ids),
            modelId=engine.adapter.capabilities.model_id,
            estimatedTokens=(
                len(
                    engine.adapter.meter.tokenizer.encode(
                        text, add_special_tokens=False
                    ).ids
                )
                if engine.adapter.capabilities.meter_id == "V41_REFERENCE_LOCAL_TOKENS"
                else len(text.encode())
            ),
            createdAt=utc_now(),
        )
        artifact = await engine.artifacts.publish(
            context,
            canonical(summary.model_dump()).decode(),
            "COMPACTION_SUMMARY",
            "summary",
            signal,
            producer_type="SYSTEM",
            call_id=response.modelCallId,
            media_type="application/json",
            prior_redaction=redaction,
        )
        if artifact.coverage != "FULL":
            raise graph_error("COMPACTION_SUMMARY_INCOMPLETE")
        ref = ArtifactRef.model_validate(
            {key: artifact.wire()[key] for key in ArtifactRef.model_fields}
        )
        candidate = called.updated(
            revision=called.revision + 1,
            summaryVersion=summary.summaryVersion,
            summaryArtifactRef=ref.model_dump(),
            messages=[m.model_dump() for m in state.messages[len(prefix) :]],
            artifactRefs=[r.model_dump() for r in refs.values()],
            sourceRefs=[r.model_dump() for r in sources.values()],
            evidenceRefs=summary.referencedEvidenceIds,
            candidateRefs=summary.referencedCandidateIds,
            sourceMessageIds=summary.sourceMessageIds,
            sourceDigest=summary.sourceDigest,
            compactionCount=state.compactionCount + 1,
        )
        replaced = await engine.build(context, candidate, signal)
        if engine.adapter.count_request(replaced) >= baseline_units:
            raise graph_error("COMPACTION_NO_PROGRESS")
        if engine.input_cost(replaced) > engine.adapter.capabilities.input_budget:
            raise graph_error("CONTEXT_REQUEST_EXCEEDS_BUDGET")
        await engine.persist(candidate, signal)
        await engine.active(context, signal)
        return candidate

    async def fit(self, context, state, signal, **request_options):
        current = state
        for attempt in range(self.engine.settings.compaction_attempts + 1):
            request = await self.engine.build(
                context, current, signal, **request_options
            )
            can_compact = request_options.get("messages") is None and bool(
                closed_prefixes(current.messages)
            )
            if self.engine.input_cost(
                request
            ) <= self.engine.adapter.capabilities.input_budget * (
                0.8
                if self.engine.adapter.capabilities.meter_id
                == "V41_REFERENCE_LOCAL_TOKENS"
                and can_compact
                else 1
            ):
                return current
            if attempt == self.engine.settings.compaction_attempts:
                break
            if not can_compact:
                break
            current = await self.compact(context, current, signal)
        raise graph_error("CONTEXT_REQUEST_EXCEEDS_BUDGET")
