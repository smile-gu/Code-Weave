"""Compile explicit, authorized source selections into the existing strict gate."""

from review_harness.domain.handles import LocalInvestigation
from review_harness.domain.model import canonical
from review_harness.domain.multipass_state import MultipassState, PassInvestigation
from review_harness.domain.submission import SubmissionIssue


def compile_sources(state, submission):
    registry = state.handleRegistry
    evidence, candidates = [], []
    for index, candidate in enumerate(submission.candidates):
        try:
            if candidate.passId != getattr(state, "activePass", "correctness"):
                raise SubmissionIssue(
                    "PASS_ID_MISMATCH", code="FINDING_SCOPE_VIOLATION"
                )
            selections = [candidate.location, *candidate.supportingSources]
            unique, seen = [], set()
            for selection in selections:
                identity = canonical(selection.model_dump(exclude_none=True))
                if identity in seen:
                    continue
                seen.add(identity)
                binding = registry.source_binding(selection.sourceHandle)
                if (
                    isinstance(state, MultipassState)
                    and binding.passId != state.activePass
                ):
                    raise SubmissionIssue("SOURCE_NOT_OBSERVED_IN_PASS")
                resolved = registry.resolve(selection)
                if selection is candidate.location and resolved.sourceType != "CODE":
                    raise SubmissionIssue("CANDIDATE_CODE_REQUIRED")
                unique.append((selection, resolved, binding))
            links = []
            for kind in ("CODE", "DOMAIN_CONTEXT"):
                group = [
                    (s, b)
                    for s, r, b in unique
                    if ("DOMAIN_CONTEXT" if r.sourceType == "USER_INPUT" else "CODE")
                    == kind
                ]
                if not group:
                    continue
                key = f"n{len(evidence) + 1}"
                evidence.append(
                    {
                        "localKey": key,
                        "evidenceType": kind,
                        "selections": [
                            s.model_dump(exclude_none=True) for s, _ in group
                        ],
                        "coverage": "FULL"
                        if all(
                            b.coverage == "FULL" and b.redactionState == "NONE"
                            for _, b in group
                        )
                        else "PARTIAL",
                        "summary": "候选问题明确选择的原始材料：" + candidate.title,
                    }
                )
                links.append({"kind": "NEW", "key": key})
            candidates.append(
                {
                    **candidate.model_dump(exclude={"location", "supportingSources"}),
                    "evidenceLinks": links,
                    "codeSelections": [
                        candidate.location.model_dump(exclude_none=True)
                    ],
                }
            )
        except SubmissionIssue as error:
            raise error.at("candidates", index) from None
    model = (
        PassInvestigation if isinstance(state, MultipassState) else LocalInvestigation
    )
    return model.model_validate(
        {
            **submission.model_dump(exclude={"submissionVersion", "candidates"}),
            "evidenceCandidates": evidence,
            "candidates": candidates,
        }
    )
