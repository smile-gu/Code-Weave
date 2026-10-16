"""Resolve positions against immutable, already-authorized safe Evidence material."""

import io
from dataclasses import dataclass

from review_harness.application.local_handles import (
    resolve_validation,
    validation_handle_view,
)
from review_harness.domain.handles import LocalValidationBatch
from review_harness.domain.model import canonical, sha256
from review_harness.domain.multipass_state import (
    MultipassState,
    PassLocalValidationBatch,
    current_candidates,
)
from review_harness.domain.position import (
    PassPositionValidationBatch,
    PositionMultipassState,
    PositionReviewState,
    PositionValidationBatch,
)
from review_harness.domain.review import ValidationBatch
from review_harness.domain.submission import SubmissionIssue

FIELDS = ("title", "claim", "category", "suggestion", "reasoningSummary")


def uses_positions(state):
    return isinstance(state, (PositionReviewState, PositionMultipassState))


def candidate_fields(candidate):
    """Enumerate the complete allowed JSON Pointers, never interpret JSONPath."""
    result = {}

    def visit(value, pointer):
        if isinstance(value, str) and value.strip():
            result[pointer] = value
        elif isinstance(value, dict):
            for name, item in value.items():
                visit(item, pointer + "/" + name.replace("~", "~0").replace("/", "~1"))
        elif isinstance(value, list):
            for index, item in enumerate(value):
                visit(item, pointer + "/" + str(index))

    value = candidate.model_dump()
    for field in FIELDS:
        visit(value[field], "/" + field)
    return result


def coordinate_kind(source):
    if source.sourceType == "ARTIFACT":
        return "ARTIFACT_PHYSICAL_LINE"
    if source.sourceType == "CODE":
        return "DIFF_PHYSICAL_LINE" if source.side == "diff" else "SOURCE_FILE_LINE"
    return "NO_LINE_COORDINATES"


def position_validation_view(state, materials):
    view = validation_handle_view(state, materials)
    originals = {c.candidateId: c for c in current_candidates(state)}
    for item in view["candidateMaterials"]:
        candidate = originals[
            state.handleRegistry.candidate_id(item["candidate"]["candidateHandle"])
        ]
        item["candidateVersion"] = sha256(canonical(candidate.model_dump()))
        item["candidateFields"] = candidate_fields(candidate)
        for record in item["evidence"]:
            for material in record["materials"]:
                binding = state.handleRegistry.source_binding(material["sourceHandle"])
                material["coordinateKind"] = coordinate_kind(binding.source)
                start = material["selection"].get("startLine")
                if start is not None:
                    material["numberedText"] = "".join(
                        f"{number}: {line}"
                        for number, line in enumerate(
                            io.StringIO(material.pop("text")), start
                        )
                    )
                material["sourceVersion"] = sha256(
                    canonical(
                        binding.model_dump(
                            exclude={"observedRanges", "expectedCursor", "pendingLine"}
                        )
                    )
                )
    return {"validationVersion": "POSITION_V1", **view}


@dataclass(frozen=True)
class ResolvedPositionValidation:
    validation: ValidationBatch
    bindings: list[dict]


def _selected_material(state, candidate, selection, materials):
    registry = state.handleRegistry
    binding = registry.source_binding(selection.sourceHandle)
    if binding.source.sourceType == "USER_INPUT":
        raise SubmissionIssue("FACT_IMPLEMENTATION_SOURCE_REQUIRED")
    # resolve() grants only observed complete lines, never a hidden full-file view.
    registry.resolve(selection)
    eligible, selected = False, []
    for evidence in registry.evidence:
        if evidence.evidenceId not in candidate.evidenceIds:
            continue
        item = next(
            (
                m
                for m in materials
                if m["evidence"]["evidenceId"] == evidence.evidenceId
            ),
            None,
        )
        if item is None:
            raise SubmissionIssue("FACT_SOURCE_INVALID")
        if (item["evidence"]["runId"], item["evidence"]["snapshotId"]) != (
            state.runId,
            state.snapshotId,
        ):
            raise SubmissionIssue("FACT_SOURCE_IDENTITY_MISMATCH")
        for index, (authorized, material) in enumerate(
            zip(evidence.selections, item["materials"], strict=True)
        ):
            if authorized.sourceHandle != selection.sourceHandle:
                continue
            eligible = True
            reference = registry.resolve(authorized).model_dump()
            if (
                material["sourceRef"] != reference
                or item["evidence"]["sourceRefs"][index] != reference
            ):
                raise SubmissionIssue("FACT_SOURCE_VERSION_MISMATCH")
            if not (
                authorized.startLine
                <= selection.startLine
                <= selection.endLine
                <= authorized.endLine
            ):
                continue
            lines = list(io.StringIO(material["text"]))
            if len(lines) != authorized.endLine - authorized.startLine + 1:
                raise SubmissionIssue("FACT_SOURCE_LINES_INCOMPLETE")
            text = "".join(
                lines[
                    selection.startLine - authorized.startLine : selection.endLine
                    - authorized.startLine
                    + 1
                ]
            )
            if len(text) > 4096:
                raise SubmissionIssue(
                    "FACT_SOURCE_SELECTION_TOO_LARGE", path=("sourceSelection",)
                )
            if not text.strip():
                raise SubmissionIssue("FACT_SOURCE_SELECTION_EMPTY")
            selected.append(
                (
                    evidence.evidenceId,
                    index,
                    text,
                    reference,
                    material["coverage"],
                    material["redactionState"],
                )
            )
    if not selected:
        raise SubmissionIssue(
            "FACT_SOURCE_RANGE_UNAUTHORIZED" if eligible else "FACT_SOURCE_INVALID"
        )
    if len({slot[2] for slot in selected}) != 1:
        raise SubmissionIssue("FACT_SOURCE_AMBIGUOUS")
    return binding, selected[0]


def resolve_position_validation(state, submitted, materials):
    """Extract text, then reuse legacy identity/correction conversion without guessing versions."""
    if not uses_positions(state):
        raise SubmissionIssue(
            "VALIDATION_PROTOCOL_MISMATCH", code="PROVIDER_VALIDATION_INVALID"
        )
    expected = (
        PassPositionValidationBatch
        if isinstance(state, MultipassState)
        else PositionValidationBatch
    )
    if type(submitted) is not expected:
        raise SubmissionIssue(
            "VALIDATION_PROTOCOL_MISMATCH", code="PROVIDER_VALIDATION_INVALID"
        )
    registry = state.handleRegistry
    candidates = {c.candidateId: c for c in current_candidates(state)}
    transformed = submitted.model_dump(exclude={"validationVersion"})
    bindings = []
    for index, local in enumerate(submitted.validations):
        candidate = candidates.get(registry.candidate_id(local.candidateHandle))
        if candidate is None:
            raise SubmissionIssue(
                "CANDIDATE_ID_MISMATCH", code="PROVIDER_VALIDATION_INVALID"
            )
        checks = []
        for check_index, check in enumerate(local.factChecks):
            path = ("validations", index, "factChecks", check_index)
            try:
                if check.candidateHandle != local.candidateHandle:
                    raise SubmissionIssue("FACT_CANDIDATE_HANDLE_MISMATCH")
                text = candidate_fields(candidate).get(check.candidateField)
                if text is None:
                    raise SubmissionIssue(
                        "FACT_CANDIDATE_FIELD_INVALID", path=("candidateField",)
                    )
                binding, (eid, source_index, quote, reference, coverage, redaction) = (
                    _selected_material(
                        state, candidate, check.sourceSelection, materials
                    )
                )
            except SubmissionIssue as error:
                raise SubmissionIssue(
                    error.reason,
                    path=(*path, *error.path),
                    stage="VALIDATION",
                    code="PROVIDER_VALIDATION_INVALID",
                ) from None
            checks.append(
                {
                    "candidateHandle": check.candidateHandle,
                    "sourceHandle": check.sourceSelection.sourceHandle,
                    "candidateText": text,
                    "sourceQuote": quote,
                    "userQuote": check.userQuote,
                    "outcome": check.outcome,
                    "explanation": check.explanation,
                }
            )
            bindings.append(
                {
                    "validationIndex": index,
                    "checkIndex": check_index,
                    "candidateId": candidate.candidateId,
                    "candidateHandle": check.candidateHandle,
                    "candidateVersion": sha256(canonical(candidate.model_dump())),
                    "candidateField": check.candidateField,
                    "candidateText": text,
                    "sourceSelection": check.sourceSelection.model_dump(),
                    "sourceBinding": binding.model_dump(),
                    "evidenceId": eid,
                    "sourceIndex": source_index,
                    "authorizedSourceRef": reference,
                    "materialCoverage": coverage,
                    "materialRedactionState": redaction,
                    "sourceQuote": quote,
                }
            )
        transformed["validations"][index]["factChecks"] = checks
    model = (
        PassLocalValidationBatch
        if isinstance(state, MultipassState)
        else LocalValidationBatch
    )
    validation = resolve_validation(state, model.model_validate(transformed), materials)
    # The quote converter's first text match cannot choose a smaller Evidence
    # interval that happens to repeat the same line. Keep our exact authorized slot.
    internal = validation.model_dump()
    for binding in bindings:
        internal["validations"][binding["validationIndex"]]["factChecks"][
            binding["checkIndex"]
        ].update(evidenceId=binding["evidenceId"], sourceIndex=binding["sourceIndex"])
    return ResolvedPositionValidation(
        type(validation).model_validate(internal), bindings
    )
