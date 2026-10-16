"""Position selections grant no authority beyond a candidate's original evidence."""

from uuid import uuid4

import pytest

from review_harness.application.position_validation import (
    candidate_fields,
    resolve_position_validation,
)
from review_harness.domain.position import PositionValidationBatch
from review_harness.domain.submission import SubmissionIssue


@pytest.fixture
def position_case():
    from review_harness.domain.model import sha256
    from review_harness.domain.position import PositionReviewState

    run, snapshot, candidate_id, evidence_id = [str(uuid4()) for _ in range(4)]
    raw = "first\nreturn text.length();\nlast\n"
    ref = {
        "sourceType": "CODE",
        "path": "Demo.java",
        "side": "final",
        "sha256": sha256(raw.encode()),
        "startLine": 1,
        "endLine": 3,
    }
    candidate = {
        "candidateId": candidate_id,
        "passId": "correctness",
        "category": "Null regression",
        "title": "Null guard removed",
        "claim": "Null input now throws. This complete field need not be copied... ever.",
        "severity": "HIGH",
        "confidence": 0.9,
        "evidenceIds": [evidence_id],
        "codeReferences": [ref],
        "suggestion": "Restore the guard",
        "claimKind": "STATIC_CORRECTNESS",
        "reasoningSummary": {
            "rule": "Null returns zero",
            "trigger": "Null input",
            "executionPath": "Dereference",
            "assumptions": ["Documented contract applies"],
            "counterexamples": ["A guard would refute"],
            "conclusion": "Regression",
        },
    }
    registry = {
        "runId": run,
        "snapshotId": snapshot,
        "sources": [
            {
                "sourceHandle": "s1",
                "source": ref,
                "observedRanges": [[1, 3]],
                "coverage": "FULL",
                "redactionState": "NONE",
            }
        ],
        "evidence": [
            {
                "evidenceHandle": "e1",
                "evidenceId": evidence_id,
                "selections": [{"sourceHandle": "s1", "startLine": 1, "endLine": 3}],
            }
        ],
        "candidates": [{"candidateHandle": "c1", "candidateId": candidate_id}],
    }
    state = PositionReviewState(
        runId=run,
        snapshotId=snapshot,
        reviewThreadId=str(uuid4()),
        investigationId=str(uuid4()),
        manifestSha256="sha256:" + "a" * 64,
        userGoalDigest="sha256:" + "b" * 64,
        scopeDigest="sha256:" + "c" * 64,
        toolSetDigest="sha256:" + "d" * 64,
        handleRegistry=registry,
        candidates=[candidate],
        candidateRefs=[candidate_id],
        evidenceRefs=[evidence_id],
    )
    evidence = {
        "evidenceId": evidence_id,
        "runId": run,
        "snapshotId": snapshot,
        "sourceRefs": [ref],
    }
    materials = [
        {
            "evidence": evidence,
            "materials": [
                {
                    "sourceRef": ref,
                    "text": raw,
                    "coverage": "FULL",
                    "redactionState": "NONE",
                }
            ],
        }
    ]
    reply = {
        "validationVersion": "POSITION_V1",
        "validations": [
            {
                "candidateHandle": "c1",
                "disposition": "VERIFIED",
                "validationSummary": {
                    "basis": "Original source",
                    "counterexampleChecked": "Guard absent",
                    "counterexampleHolds": False,
                    "assumptionsResolved": True,
                },
                "factChecks": [
                    {
                        "candidateHandle": "c1",
                        "candidateField": "/claim",
                        "sourceSelection": {
                            "sourceHandle": "s1",
                            "startLine": 2,
                            "endLine": 2,
                        },
                        "outcome": "SUPPORTED",
                        "explanation": "Null dereference violates contract",
                    }
                ],
            }
        ],
    }
    return state, reply, materials


def test_complete_candidate_field_and_source_line_are_extracted_by_platform(
    position_case,
):
    state, reply, materials = position_case
    result = resolve_position_validation(
        state, PositionValidationBatch.model_validate(reply), materials
    )
    check = result.validation.validations[0].factChecks[0]
    assert check.candidateText == state.candidates[0].claim
    assert check.sourceQuote == "return text.length();\n"
    assert result.bindings[0]["candidateField"] == "/claim"
    assert check.evidenceId == materials[0]["evidence"]["evidenceId"]
    assert (
        candidate_fields(state.candidates[0])["/reasoningSummary/assumptions/0"]
        == "Documented contract applies"
    )


@pytest.mark.parametrize(
    "pointer",
    [
        "/candidateId",
        "/evidenceIds/0",
        "/claim/missing",
        "/reasoningSummary/assumptions/1",
        "/reasoningSummary/assumptions/00",
        "$..claim",
        "/reasoningSummary",
        "/claim~1missing",
    ],
)
def test_only_enumerated_complete_string_leaf_pointers_are_allowed(
    position_case, pointer
):
    from pydantic import ValidationError

    state, reply, materials = position_case
    reply["validations"][0]["factChecks"][0]["candidateField"] = pointer
    with pytest.raises((SubmissionIssue, ValidationError)):
        resolve_position_validation(
            state, PositionValidationBatch.model_validate(reply), materials
        )


@pytest.mark.parametrize(
    "mutation",
    [
        "hole",
        "half-line",
        "evidence-subrange",
        "hash",
        "run",
        "snapshot",
        "other-candidate",
        "incomplete-material",
        "oversized",
    ],
)
def test_position_selection_rejects_unobserved_or_unauthorized_material(
    position_case, mutation
):
    state, reply, materials = position_case
    data = state.model_dump()
    if mutation in {"hole", "half-line"}:
        data["handleRegistry"]["sources"][0]["observedRanges"] = [[1, 1], [3, 3]]
        if mutation == "half-line":
            data["handleRegistry"]["sources"][0]["pendingLine"] = 2
        from pydantic import ValidationError

        with pytest.raises(ValidationError):
            type(state).model_validate(data)
        return
    if mutation == "evidence-subrange":
        data["handleRegistry"]["evidence"][0]["selections"][0].update(
            startLine=1, endLine=1
        )
        materials[0]["materials"][0]["sourceRef"] = {
            **materials[0]["materials"][0]["sourceRef"],
            "endLine": 1,
        }
        materials[0]["materials"][0]["text"] = "first\n"
        state = type(state).model_validate(data)
    elif mutation == "hash":
        materials[0]["materials"][0]["sourceRef"] = {
            **materials[0]["materials"][0]["sourceRef"],
            "sha256": "sha256:" + "e" * 64,
        }
    elif mutation in {"run", "snapshot"}:
        materials[0]["evidence"][mutation + "Id"] = str(uuid4())
    elif mutation == "other-candidate":
        reply["validations"][0]["factChecks"][0]["candidateHandle"] = "c2"
    elif mutation == "incomplete-material":
        materials[0]["materials"][0]["text"] = "first\n"
    elif mutation == "oversized":
        materials[0]["materials"][0]["text"] = "first\n" + "x" * 4097 + "\nlast\n"
    with pytest.raises(SubmissionIssue):
        resolve_position_validation(
            state, PositionValidationBatch.model_validate(reply), materials
        )


def test_position_schema_requires_explicit_version_and_rejects_copy_fields():
    from pydantic import ValidationError

    reply = {
        "validationVersion": "POSITION_V1",
        "validations": [
            {
                "candidateHandle": "c1",
                "disposition": "REJECTED",
                "validationSummary": {
                    "basis": "Benign",
                    "counterexampleChecked": "Checked",
                    "counterexampleHolds": False,
                    "assumptionsResolved": True,
                },
                "factChecks": [
                    {
                        "candidateHandle": "c1",
                        "candidateField": "/claim",
                        "sourceSelection": {
                            "sourceHandle": "s1",
                            "startLine": 2,
                            "endLine": 2,
                        },
                        "outcome": "SUPPORTED",
                        "explanation": "Source checked",
                    }
                ],
            }
        ],
    }
    PositionValidationBatch.model_validate(reply)
    for mutation in [
        {**reply, "validationVersion": "UNKNOWN"},
        {k: v for k, v in reply.items() if k != "validationVersion"},
    ]:
        with pytest.raises(ValidationError):
            PositionValidationBatch.model_validate(mutation)
    reply["validations"][0]["factChecks"][0]["sourceQuote"] = (
        "Do not accept a fallback quote"
    )
    with pytest.raises(ValidationError):
        PositionValidationBatch.model_validate(reply)


@pytest.mark.parametrize("source_type", ["final", "diff", "artifact"])
def test_source_coordinates_stay_in_the_source_and_redacted_material_is_not_refilled(
    position_case, source_type
):
    from review_harness.application.position_validation import position_validation_view

    state, reply, materials = position_case
    data = state.model_dump()
    ref = materials[0]["materials"][0]["sourceRef"].copy()
    if source_type == "artifact":
        ref = {
            "sourceType": "ARTIFACT",
            "artifactId": str(uuid4()),
            "sha256": ref["sha256"],
            "startLine": 1,
            "endLine": 3,
            "coverage": "FULL",
        }
        data["candidates"][0]["codeReferences"] = []
    elif source_type == "diff":
        ref["side"] = "diff"
        data["candidates"][0]["codeReferences"] = [ref]
    data["handleRegistry"]["sources"][0].update(source=ref, redactionState="APPLIED")
    materials[0]["evidence"]["sourceRefs"] = [ref]
    materials[0]["materials"][0].update(
        sourceRef=ref,
        text="first\npassword = [REDACTED]\nlast\n",
        redactionState="APPLIED",
    )
    state = type(state).model_validate(data)
    resolved = resolve_position_validation(
        state, PositionValidationBatch.model_validate(reply), materials
    )
    assert (
        resolved.validation.validations[0].factChecks[0].sourceQuote
        == "password = [REDACTED]\n"
    )
    projected = position_validation_view(state, materials)["candidateMaterials"][0][
        "evidence"
    ][0]["materials"][0]
    assert (
        projected["coordinateKind"]
        == {
            "final": "SOURCE_FILE_LINE",
            "diff": "DIFF_PHYSICAL_LINE",
            "artifact": "ARTIFACT_PHYSICAL_LINE",
        }[source_type]
    )


def test_multiple_evidence_slots_select_the_interval_not_the_first_repeated_text(
    position_case,
):
    import copy

    state, reply, materials = position_case
    data = state.model_dump()
    second = str(uuid4())
    data["evidenceRefs"].append(second)
    data["candidates"][0]["evidenceIds"].append(second)
    data["handleRegistry"]["evidence"].append(
        {
            "evidenceHandle": "e2",
            "evidenceId": second,
            "selections": [{"sourceHandle": "s1", "startLine": 1, "endLine": 3}],
        }
    )
    data["handleRegistry"]["evidence"][0]["selections"][0].update(endLine=1)
    other = copy.deepcopy(materials[0])
    other["evidence"]["evidenceId"] = second
    other["materials"][0]["text"] = (
        "return text.length();\nreturn text.length();\nlast\n"
    )
    materials[0]["materials"][0].update(
        sourceRef={**materials[0]["materials"][0]["sourceRef"], "endLine": 1},
        text="return text.length();\n",
    )
    materials[0]["evidence"]["sourceRefs"] = [materials[0]["materials"][0]["sourceRef"]]
    materials.append(other)
    state = type(state).model_validate(data)
    result = resolve_position_validation(
        state, PositionValidationBatch.model_validate(reply), materials
    )
    assert result.validation.validations[0].factChecks[0].evidenceId == second


def test_ambiguous_authorized_material_is_rejected(position_case):
    import copy

    state, reply, materials = position_case
    data = state.model_dump()
    second = str(uuid4())
    data["evidenceRefs"].append(second)
    data["candidates"][0]["evidenceIds"].append(second)
    data["handleRegistry"]["evidence"].append(
        {
            "evidenceHandle": "e2",
            "evidenceId": second,
            "selections": [{"sourceHandle": "s1", "startLine": 1, "endLine": 3}],
        }
    )
    other = copy.deepcopy(materials[0])
    other["evidence"]["evidenceId"] = second
    other["materials"][0]["text"] = "first\nconflicting text\nlast\n"
    materials.append(other)
    with pytest.raises(SubmissionIssue) as failed:
        resolve_position_validation(
            type(state).model_validate(data),
            PositionValidationBatch.model_validate(reply),
            materials,
        )
    assert failed.value.reason == "FACT_SOURCE_AMBIGUOUS"


@pytest.mark.parametrize("version", ["3.0", "4.0", "5.0", "6.0"])
async def test_checkpoint_versions_roundtrip_without_implicit_protocol_migration(
    position_case, tmp_path, version
):
    from pydantic import ValidationError

    from review_harness.domain.real_graph import parse_state
    from review_harness.infrastructure.checkpoints import Checkpoints
    from tests.integration.test_multipass_contracts import plan_data

    state, _, _ = position_case
    data = state.model_dump()
    data["stateVersion"] = version
    if version in {"3.0", "4.0"}:
        data.pop("validationProtocol")
        data.pop("validationRecordRefs")
    if version in {"4.0", "6.0"}:
        data.update(
            activePass="correctness",
            reviewPlan=plan_data(),
            runStartedAt="2026-10-16T00:00:00.000000Z",
        )
        data["handleRegistry"]["activePass"] = "correctness"
        for source in data["handleRegistry"]["sources"]:
            source["passId"] = "correctness"
    expected = parse_state(data)
    checkpoints = Checkpoints(tmp_path / "versions.sqlite")
    await checkpoints.open()
    identity = await checkpoints.save(expected)
    await checkpoints.close()
    await checkpoints.open()
    try:
        assert await checkpoints.read(expected, identity) == expected
        if version in {"5.0", "6.0"}:
            with pytest.raises(ValidationError):
                parse_state({**expected.model_dump(), "validationProtocol": "UNKNOWN"})
            with pytest.raises(ValidationError):
                parse_state(
                    {
                        **expected.model_dump(),
                        "stateVersion": "3.0" if version == "5.0" else "4.0",
                    }
                )
        with pytest.raises(ValidationError):
            parse_state({**expected.model_dump(), "stateVersion": "UNSUPPORTED"})
    finally:
        await checkpoints.close()


@pytest.mark.parametrize(
    "selection",
    [
        {"sourceHandle": "s1", "startLine": 2},
        {"sourceHandle": "s1", "startLine": 0, "endLine": 2},
        {"sourceHandle": "s1", "startLine": 3, "endLine": 2},
        {"sourceHandle": "s1", "startLine": 2, "endLine": 2, "path": "D:/hidden.java"},
    ],
)
def test_positions_require_complete_physical_coordinates_without_paths(
    position_case, selection
):
    from pydantic import ValidationError

    _, reply, _ = position_case
    reply["validations"][0]["factChecks"][0]["sourceSelection"] = selection
    with pytest.raises(ValidationError):
        PositionValidationBatch.model_validate(reply)


def test_legacy_batches_cannot_enter_the_position_resolver(position_case):
    from review_harness.domain.handles import LocalValidationBatch

    state, reply, materials = position_case
    legacy = LocalValidationBatch.model_validate(
        {"validations": [{**reply["validations"][0], "factChecks": []}]}
    )
    with pytest.raises(SubmissionIssue) as failed:
        resolve_position_validation(state, legacy, materials)
    assert failed.value.reason == "VALIDATION_PROTOCOL_MISMATCH"


def test_user_input_cannot_become_an_implementation_source(position_case):
    state, reply, materials = position_case
    data = state.model_dump()
    ref = {"sourceType": "USER_INPUT", "sha256": state.userGoalDigest}
    data["handleRegistry"]["sources"][0].update(source=ref, observedRanges=[])
    data["handleRegistry"]["evidence"][0]["selections"] = [{"sourceHandle": "s1"}]
    data["candidates"][0]["codeReferences"] = []
    materials[0]["evidence"]["sourceRefs"] = [ref]
    materials[0]["materials"][0]["sourceRef"] = ref
    with pytest.raises(SubmissionIssue) as failed:
        resolve_position_validation(
            type(state).model_validate(data),
            PositionValidationBatch.model_validate(reply),
            materials,
        )
    assert failed.value.reason == "FACT_IMPLEMENTATION_SOURCE_REQUIRED"


def test_complete_evidence_on_each_side_does_not_authorize_an_unread_hole(
    position_case,
):
    import copy

    state, reply, materials = position_case
    data = state.model_dump()
    data["handleRegistry"]["sources"][0].update(
        observedRanges=[[1, 1], [3, 3]], pendingLine=2
    )
    data["handleRegistry"]["evidence"][0]["selections"] = [
        {"sourceHandle": "s1", "startLine": n, "endLine": n} for n in [1, 3]
    ]
    ref = data["handleRegistry"]["sources"][0]["source"]
    refs = [{**ref, "startLine": n, "endLine": n} for n in [1, 3]]
    data["candidates"][0]["codeReferences"] = refs
    materials = copy.deepcopy(materials)
    materials[0]["evidence"]["sourceRefs"] = refs
    materials[0]["materials"] = [
        {"sourceRef": r, "text": t, "coverage": "FULL", "redactionState": "NONE"}
        for r, t in zip(refs, ["first\n", "last\n"], strict=True)
    ]
    reply["validations"][0]["factChecks"][0]["sourceSelection"].update(
        startLine=1, endLine=3
    )
    with pytest.raises(SubmissionIssue) as failed:
        resolve_position_validation(
            type(state).model_validate(data),
            PositionValidationBatch.model_validate(reply),
            materials,
        )
    assert failed.value.reason == "SOURCE_RANGE_NOT_OBSERVED"


def test_base_sources_remain_unsupported_and_are_not_synthesized(position_case):
    from pydantic import ValidationError

    state, _, _ = position_case
    data = state.model_dump()
    data["handleRegistry"]["sources"][0]["source"]["side"] = "base"
    with pytest.raises(ValidationError):
        type(state).model_validate(data)


def test_another_candidates_visible_source_is_not_authorized(position_case):
    import copy

    from review_harness.application.position_validation import position_validation_view

    state, reply, materials = position_case
    data = state.model_dump()
    cid, eid = str(uuid4()), str(uuid4())
    source = copy.deepcopy(data["handleRegistry"]["sources"][0])
    source["sourceHandle"] = "s2"
    source["source"]["path"] = "Other.java"
    data["handleRegistry"]["sources"].append(source)
    data["handleRegistry"]["evidence"].append(
        {
            "evidenceHandle": "e2",
            "evidenceId": eid,
            "selections": [{"sourceHandle": "s2", "startLine": 1, "endLine": 3}],
        }
    )
    candidate = copy.deepcopy(data["candidates"][0])
    candidate.update(
        candidateId=cid, evidenceIds=[eid], codeReferences=[source["source"]]
    )
    data["candidates"].append(candidate)
    data["candidateRefs"].append(cid)
    data["evidenceRefs"].append(eid)
    data["handleRegistry"]["candidates"].append(
        {"candidateHandle": "c2", "candidateId": cid}
    )
    other = copy.deepcopy(materials[0])
    other["evidence"].update(evidenceId=eid, sourceRefs=[source["source"]])
    other["materials"][0]["sourceRef"] = source["source"]
    materials.append(other)
    state = type(state).model_validate(data)
    assert position_validation_view(state, materials)["candidateFactSources"] == {
        "c1": ["s1"],
        "c2": ["s2"],
    }
    reply["validations"].append(
        {
            **reply["validations"][0],
            "candidateHandle": "c2",
            "disposition": "REJECTED",
            "factChecks": [],
        }
    )
    reply["validations"][0]["factChecks"][0]["sourceSelection"]["sourceHandle"] = "s2"
    with pytest.raises(SubmissionIssue) as failed:
        resolve_position_validation(
            state, PositionValidationBatch.model_validate(reply), materials
        )
    assert failed.value.reason == "FACT_SOURCE_INVALID"
