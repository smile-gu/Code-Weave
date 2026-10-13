"""Conservative source/root recall; merge never creates a new judgment or source."""

from typing import Annotated

from pydantic import Field

from review_harness.domain.graph import StrictModel
from review_harness.domain.handles import CandidateHandle
from review_harness.domain.model import canonical
from review_harness.domain.multipass import PASS_ORDER, MultipassFinding


class DedupDecision(StrictModel):
    candidateHandles: list[CandidateHandle] = Field(min_length=2, max_length=32)
    sameRootCause: bool
    sameTrigger: bool
    sameFix: bool
    rationale: Annotated[str, Field(min_length=1, max_length=2048)]


def normalized(text):
    return " ".join(text.casefold().split())


def overlaps(left, right):
    return any(
        (a.path, a.side, a.sha256) == (b.path, b.side, b.sha256)
        and max(a.startLine, b.startLine) <= min(a.endLine, b.endLine)
        for a in left.codeReferences
        for b in right.codeReferences
    )


def recall_groups(findings):
    groups = []
    for finding in sorted(
        findings, key=lambda f: (PASS_ORDER.index(f.passId), f.candidateId)
    ):
        matching = [
            group
            for group in groups
            if any(
                overlaps(finding, existing)
                and normalized(finding.reasoningSummary.rule)
                == normalized(existing.reasoningSummary.rule)
                for existing in group
            )
        ]
        if not matching:
            groups.append([finding])
        else:
            destination = matching[0]
            destination.append(finding)
            for group in matching[1:]:
                destination.extend(group)
                groups.remove(group)
    return [g for g in groups if len(g) > 1]


def merge_group(group):
    ordered = sorted(group, key=lambda f: (PASS_ORDER.index(f.passId), f.candidateId))
    primary = ordered[0]
    # Different trigger/fix statements remain distinct even after model agreement.
    if any(
        (normalized(f.reasoningSummary.trigger), normalized(f.suggestion))
        != (
            normalized(primary.reasoningSummary.trigger),
            normalized(primary.suggestion),
        )
        for f in ordered
    ):
        raise ValueError("Different triggers or fixes cannot merge")
    evidence = list(dict.fromkeys(e for f in ordered for e in f.evidenceIds))
    refs = {canonical(r.model_dump()): r for f in ordered for r in f.codeReferences}
    return MultipassFinding.model_validate(
        {
            **primary.model_dump(),
            "evidenceIds": evidence,
            "codeReferences": [r.model_dump() for r in refs.values()],
            "contributions": [
                {
                    key: f.model_dump()[key]
                    for key in (
                        "passId",
                        "candidateId",
                        "evidenceIds",
                        "codeReferences",
                        "disposition",
                        "validationModelCallId",
                    )
                }
                for f in ordered
            ],
        }
    )


def unmerged(finding):
    return merge_group([finding])
