"""Conservative source/root recall; merge never creates a new judgment or source."""

from typing import Annotated

from pydantic import Field, field_validator

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

    @field_validator("rationale")
    @classmethod
    def nonblank_rationale(cls, value):
        if not value.strip():
            raise ValueError("A nonblank group comparison rationale is required")
        return value


def overlaps(left, right):
    return any(
        (a.path, a.side, a.sha256) == (b.path, b.side, b.sha256)
        and max(a.startLine, b.startLine) <= min(a.endLine, b.endLine)
        for a in left.codeReferences
        for b in right.codeReferences
    )


def ordering(finding):
    return PASS_ORDER.index(finding.passId), finding.candidateId


def recall_groups(findings):
    groups = []
    for finding in sorted(
        findings, key=lambda f: (PASS_ORDER.index(f.passId), f.candidateId)
    ):
        matching = [
            group
            for group in groups
            if any(overlaps(finding, existing) for existing in group)
        ]
        if not matching:
            groups.append([finding])
        else:
            destination = matching[0]
            destination.append(finding)
            for group in matching[1:]:
                destination.extend(group)
                groups.remove(group)
    return [sorted(g, key=ordering) for g in groups if len(g) > 1]


def merge_group(group, *, decision=None, handles=None):
    """A merge consumes an affirmative decision for exactly these originals."""
    if len(group) < 2 or decision is None or handles is None:
        raise ValueError("Merge requires an exact confirmed group")
    decision = DedupDecision.model_validate(decision.model_dump())
    identities = [f.candidateId for f in group]
    expected = [handles.get(identity) for identity in identities]
    if (
        len(set(identities)) != len(identities)
        or None in expected
        or len(set(expected)) != len(expected)
        or len(set(decision.candidateHandles)) != len(decision.candidateHandles)
        or set(expected) != set(decision.candidateHandles)
        or not (decision.sameRootCause and decision.sameTrigger and decision.sameFix)
    ):
        raise ValueError("Unconfirmed or mismatched decision")
    recalled = recall_groups(group)
    if len(recalled) != 1 or len(recalled[0]) != len(group):
        raise ValueError("Group must be recalled from connected source overlap")
    from review_harness.domain.multipass_state import PassFinding

    ordered = sorted(
        (PassFinding.model_validate(f.model_dump()) for f in group), key=ordering
    )
    primary = ordered[0]
    if any(
        (f.claimKind, f.disposition) != (primary.claimKind, primary.disposition)
        for f in ordered
    ):
        raise ValueError("Incompatible judgment kinds")
    return _project(ordered)


def _project(ordered):
    primary = ordered[0]
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
    return _project([finding])


def comparison_groups(findings):
    """Partition known-incompatible judgments before source connectivity or blocking."""
    partitions = {}
    for finding in sorted(findings, key=ordering):
        key = (finding.claimKind, finding.disposition)
        partitions.setdefault(key, []).append(finding)
    components = sorted(
        (
            (key, component)
            for key, members in partitions.items()
            for component in recall_groups(members)
        ),
        key=lambda item: ordering(item[1][0]),
    )
    comparisons, limitations = [], []
    for component_index, (key, component) in enumerate(components):
        if len(component) > 32:
            limitations.append(
                "Possible duplicates remain: source component exceeds 32; cross-block comparisons omitted."
            )
        for block_index, offset in enumerate(range(0, len(component), 32)):
            block = component[offset : offset + 32]
            for group in recall_groups(block):
                comparisons.append(
                    (
                        group,
                        {
                            "strategy": "compatible-partition-v1",
                            "partitionKey": {
                                "claimKind": key[0],
                                "disposition": key[1],
                            },
                            "componentIndex": component_index,
                            "componentSize": len(component),
                            "blockIndex": block_index,
                            "blockCandidateIds": [f.candidateId for f in block],
                            "overlapEdges": [
                                [a.candidateId, b.candidateId]
                                for i, a in enumerate(group)
                                for b in group[i + 1 :]
                                if overlaps(a, b)
                            ],
                        },
                    )
                )
    comparisons.sort(key=lambda item: ordering(item[0][0]))
    for index, (_, grouping) in enumerate(comparisons):
        grouping["groupIndex"] = index
    return comparisons, limitations
