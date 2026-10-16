"""Autonomous investigation loop; deterministic source, validation and delivery gates."""

import json
import re
from typing import TypedDict
from uuid import uuid4

from langgraph.graph import END, START, StateGraph
from pydantic import ValidationError

from review_harness.application.position_validation import (
    candidate_fields,
    position_validation_view,
    resolve_position_validation,
    uses_positions,
)
from review_harness.application.redaction import redact, redact_structure
from review_harness.domain.graph import (
    ArtifactRef,
    GraphMessage,
    graph_error,
    pending_calls,
)
from review_harness.domain.handles import LocalInvestigation, LocalValidationBatch
from review_harness.domain.model import canonical, sha256
from review_harness.domain.multipass_state import (
    MultipassState,
    PassCandidate,
    PassFinding,
    PassInvestigation,
    PassLocalValidationBatch,
    PassOutcome,
    current_candidates,
)
from review_harness.domain.position import (
    PassPositionValidationBatch,
    PositionValidationBatch,
)
from review_harness.domain.provider import strict_json
from review_harness.domain.review import (
    EvidenceCandidate,
    Finding,
    InvestigationSubmission,
    ReviewCandidate,
    ReviewPackage,
    ValidationBatch,
)
from review_harness.domain.snapshot import WorkCancelled
from review_harness.domain.submission import SubmissionIssue
from review_harness.runtime.compaction import Compactor
from review_harness.runtime.provider_profile import PROFILE
from review_harness.runtime.tools import ToolGateway

SCOPE_INSTRUCTION = (
    "checkedScope must copy complete file paths verbatim from scope.targetPaths; "
    "do not append line numbers, method names or descriptions, or use directory names or excluded paths. "
    "Put line ranges in codeSelections or evidence selections instead."
)

FINDING_QUALIFICATION = (
    "Factually accurate descriptions alone do not qualify as defects. "
    "Do not submit behavior-preserving observations as candidates; retain them as checked scope or excluded hypotheses. "
    "A VERIFIED finding requires a supported defect: a violated requirement/invariant, a concrete trigger and an adverse consequence, "
    "not merely proof that the described code behavior exists. During validation, benign observations must be REJECTED, "
    "even if every factual description is supported. RISK requires a plausible adverse consequence with explicitly unresolved "
    "environmental assumptions. SUGGESTION requires a concrete improvement objective and justified value; optional observations "
    "with no improvement need must not automatically become suggestions. Judge the complete claim and evidence, not keywords "
    "or quoted phrases such as 'no risk'. Never change a disposition mechanically to make the report nonempty. "
)

SUBMISSION = (
    "Return json matching this schema and example. Evidence IDs and candidate IDs are assigned by the platform; new evidence is acknowledged before it can be referenced by a candidate. Never claim unseen material or global absence of bugs. Schema: "
    + canonical(InvestigationSubmission.model_json_schema()).decode()
    + ' Example: {"decision":"CONTINUE","evidenceCandidates":[],"candidates":[],"checkedScope":[],"scopeKind":"FINAL_FILES","coverage":"PARTIAL","limitations":["Investigation continues"],"incompleteReasons":[]}.'
)
SUBMISSION += (
    " " + SCOPE_INSTRUCTION + " "
    " Copy evidenceReferences from tool data; do not compute or invent hashes. "
    "For final CODE, sha256 is the raw whole-file contentSha256; side=final. "
    "For deleted CODE use side=diff, whole changes.diff sha256 and physical full-Diff "
    "hunk line numbers from pageCoordinates/evidenceReferences, not source line numbers. "
    "Reassemble half lines using startsMidLine/endsMidLine; headers are not code. "
    "side=base is unsupported. Artifact references use the stored Artifact's own "
    "line coordinates, hash and coverage, never source coordinates. "
    "DOMAIN_CONTEXT copies userInputEvidenceReference from fixed anchors. "
    "FULL means the declared material is completely available without redaction; "
    "paged/partial/redacted material must not be claimed as full without completion. "
    "Use evidenceCandidateExample in fixed anchors for a valid conservative USER_INPUT example. "
    "For CODE copy a tool evidenceReferences object and describe the observed material."
)
VALIDATION = (
    "Use only this candidate's evidenceIds for factChecks, not other evidenceRecords visible in the Run. candidateFactSources enumerates eligible implementation sources; use sourceQuote from matching materials. USER_INPUT is a requirement, not an implementation source: put its exact text in userQuote alongside a CODE/Artifact check. counterexampleHolds means an actual counterexample defeats the candidate; describing a hypothetical challenge that does not hold requires false. "
    "Return bounded factChecks for the candidate's key implementation facts, necessary assumptions and impact scope. Each check quotes actual candidateText, identifies acknowledged evidenceId and zero-based sourceIndex, and copies an exact sourceQuote from that original material. If checking a user description, also copy an exact userQuote. Distinguish SUPPORTED, CORRECTED (non-core conflict resolved in correctedCandidate), CORE_CONTRADICTED (reject), and UNRESOLVED (never verify). Explanations must compare the quoted materials; a claim that facts were checked without this projection is insufficient. "
    "Validate candidates independently against the supplied original materials and check the strongest counterexample. Check key facts, necessary assumptions and impact scope, not just whether the bug holds. If an incorrect premise defeats the core claim, REJECT it or mark unresolved assumptions; never verify it. If the core claim remains supported, return correctedCandidate with all inaccurate candidate text and assumptions corrected and the supported impact scope stated. Validate that corrected candidate against the original materials. Preserve passId, claimKind, evidenceIds and codeReferences exactly; do not invent sources or broaden the claim. Explain conflicting user descriptions in validationSummary. This judgment is not new evidence. Return json matching this schema, one result per supplied candidate; never invent candidate IDs. Schema: "
    + canonical(ValidationBatch.model_json_schema()).decode()
    + ' Example: {"validations":[{"candidateId":"11111111-1111-4111-8111-111111111111","disposition":"REJECTED","validationSummary":{"basis":"Insufficient static support","counterexampleChecked":"Checked guard and preconditions","counterexampleHolds":true,"assumptionsResolved":false}}]}.'
)
BUDGET_ERRORS = {
    "CONTEXT_MODEL_CALL_LIMIT_EXCEEDED",
    "CONTEXT_REQUEST_EXCEEDS_BUDGET",
    "CONTEXT_GRAPH_STEP_LIMIT_EXCEEDED",
    "COMPACTION_LIMIT_EXCEEDED",
    "PASS_CALL_BUDGET_EXHAUSTED",
    "RUN_CALL_BUDGET_EXHAUSTED",
    "FEE_BUDGET_EXHAUSTED",
    "PASS_TIME_BUDGET_EXHAUSTED",
    "RUN_TIME_BUDGET_EXHAUSTED",
}

LOCAL_SUBMISSION = (
    "Return json matching this local submission schema. Choose only sourceHandles actually shown in sourceCatalog/tool sources and observedRanges. "
    "Use unique n1/n2 localKey for new evidence; candidates may reference NEW evidence in this SAME submission, or SAVED e handles from platform receipts. "
    "Do not invent UUIDs/hashes, repeat links or enlarge observed ranges. USER_INPUT supports only DOMAIN_CONTEXT and omits line fields. "
    "COMPLETE may include new evidence and candidates together; candidate codeSelections must be CODE within selected evidence. "
    "Coverage of a selected excerpt does not prove full-file inspection. Preserve assumptions, counterexamples and actual checked scope. "
    + SCOPE_INSTRUCTION
    + " Schema: "
    + canonical(LocalInvestigation.model_json_schema()).decode()
)
LOCAL_VALIDATION = (
    "Validate candidates independently, every candidate in candidateMaterials exactly once by candidateHandle. "
    "User input specifies expected behavior; only original code/Diff/Artifact determines implementation facts. Verify descriptions and identify conflicts. "
    "factChecks must quote actual candidateText and sourceQuote from that candidate's exclusive candidateFactSources/materials, using candidateHandle/sourceHandle; "
    "other candidates or same-Run sources are not authorized. USER_INPUT never proves implementation facts. "
    "Check key facts, necessary assumptions and impact scope, and the strongest counterexample. CORE_CONTRADICTED requires REJECTED; UNRESOLVED cannot be VERIFIED. "
    "Non-core errors require correctedCandidate with corrections actually applied, preserving candidateHandle/passId/claimKind/evidenceLinks/codeSelections. "
    "CORRECTED checks must remove inaccurate candidateText; cannot merely claim checked. Keep quantitative/environment claims appropriately qualified. "
    "Return bounded json according to this schema, no UUIDs or sourceIndex. Schema: "
    + canonical(LocalValidationBatch.model_json_schema()).decode()
)

POSITION_VALIDATION = (
    "Validate candidates independently, every candidate in candidateMaterials exactly once by candidateHandle. "
    "Protocol POSITION_V1: factChecks select a complete candidateField JSON Pointer verbatim from that candidate's candidateFields, "
    "and a sourceSelection with sourceHandle/startLine/endLine from its exclusive candidateFactSources/materials. "
    "The platform extracts the original field and source text; do not submit candidateText, sourceQuote or fallback quotations. "
    "Use one-based inclusive physical coordinates in the displayed coordinateKind: DIFF_PHYSICAL_LINE is the Diff text, "
    "SOURCE_FILE_LINE is the source file, ARTIFACT_PHYSICAL_LINE is the Artifact body. Never convert Diff lines to file lines. "
    "Select only complete observed lines within one authorized Evidence material, no holes or half lines; keep the extracted source text within 4096 characters. "
    "User input defines expected behavior, never implementation facts; userQuote remains an optional exact original user quotation. "
    "Check key facts, necessary assumptions and impact scope and the strongest counterexample. CORE_CONTRADICTED requires REJECTED; UNRESOLVED cannot be VERIFIED. "
    "For CORRECTED provide correctedCandidate with the selected field actually changed; compare the same pointer in original and corrected versions. "
    "Preserve candidateHandle/passId/claimKind/evidenceLinks/codeSelections exactly. Never merely claim checked or rewrite provenance. "
    "counterexampleHolds means a counterexample refutes the claim, not an example triggering the bug. "
    "Return exactly one JSON object with validationVersion=POSITION_V1 according to this schema. Schema: "
    + canonical(PositionValidationBatch.model_json_schema()).decode()
)


def schema_expected(schema, location):
    """Project constraints from our schema, never from rejected model values."""
    node = schema

    def resolve(value):
        if "$ref" in value:
            return schema["$defs"][value["$ref"].removeprefix("#/$defs/")]
        return value

    for part in location:
        node = resolve(node)
        if type(part) is int:
            node = node.get("items", {})
        elif part in node.get("discriminator", {}).get("mapping", {}):
            node = {"$ref": node["discriminator"]["mapping"][part]}
        elif part in node.get("properties", {}):
            node = node["properties"][part]
        else:
            return {
                "additionalProperties": node.get("additionalProperties", False),
                "allowedFields": sorted(node.get("properties", {}))[:32],
            }
    node = resolve(node)
    return {
        key: node[key]
        for key in (
            "type",
            "enum",
            "const",
            "minItems",
            "maxItems",
            "minLength",
            "maxLength",
            "minimum",
            "maximum",
            "pattern",
            "format",
        )
        if key in node
    }


def redacted_submission(text):
    """Keep valid JSON editable; malformed JSON uses conservative text hygiene."""
    try:
        clean, state = redact_structure(
            strict_json(text, limit=65536), preserve_identity=False
        )
        return canonical(clean).decode(), state
    except (ValueError, TypeError):
        return redact(text)


def parse_submission(text, model):
    try:
        clean, _ = redact_structure(strict_json(text, limit=65536))
    except json.JSONDecodeError as error:
        # Coordinates refer to the original submitted text. Never expose an
        # arbitrary parser message or a fragment of possibly sensitive input.
        raise SubmissionIssue(
            "JSON_INVALID",
            stage="JSON",
            code="PROVIDER_STRUCTURED_OUTPUT_INVALID",
            json_error={
                "errorType": "EXTRA_DATA"
                if error.msg == "Extra data"
                else "SYNTAX_INVALID",
                "line": error.lineno,
                "column": error.colno,
                "position": error.pos,
            },
        ) from None
    except (ValueError, TypeError):
        raise SubmissionIssue(
            "JSON_INVALID", stage="JSON", code="PROVIDER_STRUCTURED_OUTPUT_INVALID"
        ) from None
    try:
        return model.model_validate(clean)
    except ValidationError as error:
        names = {"CODE", "ARTIFACT", "USER_INPUT"}

        def collect(schema):
            if isinstance(schema, dict):
                names.update(schema.get("properties", {}))
                for child in schema.values():
                    collect(child)
            elif isinstance(schema, list):
                for child in schema:
                    collect(child)

        schema = model.model_json_schema()
        collect(schema)
        violations = []
        for item in error.errors(include_input=False, include_context=False)[:8]:
            path = [
                part if type(part) is int or part in names else "<unknown-field>"
                for part in item["loc"][:8]
            ]
            violations.append(
                {
                    "fieldPath": path,
                    "errorType": item["type"],
                    "expected": schema_expected(schema, item["loc"]),
                }
            )
        raise SubmissionIssue(
            "SCHEMA_INVALID",
            stage="SCHEMA",
            path=violations[0]["fieldPath"],
            code="PROVIDER_STRUCTURED_OUTPUT_INVALID",
            schema_violations=violations,
        ) from None


class ReviewChannels(TypedDict):
    state: dict
    route: str
    package: dict | None


class RealReviewExecutor:
    def __init__(self, engine, evidence, gateway=None):
        self.engine, self.evidence = engine, evidence
        self.gateway = gateway or ToolGateway(
            engine.settings, engine.runs, engine.workspaces, engine.artifacts
        )
        engine.gateway = self.gateway
        self.last_state = None
        self.wallclock_seconds = engine.settings.run_timeout_seconds
        self.expected_contract = {"executionMode": "REAL", "resultVersion": "2.0"}

    async def _save(self, state, signal):
        await self.engine.persist(state, signal)
        self.last_state = state
        return state

    def _step(self, state):
        if state.graphStepCount >= (
            1204
            if isinstance(state, MultipassState)
            else self.engine.settings.graph_max_steps
        ):
            raise graph_error("CONTEXT_GRAPH_STEP_LIMIT_EXCEEDED")
        return state.updated(graphStepCount=state.graphStepCount + 1)

    async def _json(
        self,
        context,
        state,
        signal,
        model,
        instruction,
        *,
        purpose="INVESTIGATION",
        messages=None,
        apply_submission=False,
        validation_materials=None,
        dedup_handles=None,
    ):
        if purpose in {"INVESTIGATION", "VALIDATION"}:
            instruction = FINDING_QUALIFICATION + instruction
        if apply_submission and not hasattr(state, "handleRegistry"):
            instruction += (
                " Submission phase: CANDIDATES_ALLOWED. Candidate evidenceIds must be UUIDs copied from acknowledged evidenceRecords; new evidence cannot be referenced in this same submission."
                if state.evidenceRefs
                else " Submission phase: EVIDENCE_FIRST. No evidence IDs have been acknowledged. To report a candidate, first submit decision=CONTINUE with evidenceCandidates and candidates=[] first. After the platform receipt, submit candidates using its evidenceIds. Do not invent IDs or submit a candidate with an empty evidenceIds list. COMPLETE with no candidates remains allowed when justified by the declared scope and coverage."
            )
        if isinstance(state, MultipassState):
            instruction = (
                instruction.split(" Schema: ")[0]
                + " Schema: "
                + canonical(model.model_json_schema()).decode()
            )
            instruction += (
                " Active pass is "
                + state.activePass
                + "; all candidates must preserve that passId. New evidence selections require observedInPass matching the active pass. Previous pass conclusions are clues, never evidence. Saved evidence is revalidated against original material."
            )
        while True:
            state = await Compactor(self.engine).fit(
                context,
                state,
                signal,
                purpose=purpose,
                instruction=instruction,
                structured=True,
                messages=messages,
            )
            state, response = await self.engine.invoke(
                context,
                state,
                signal,
                purpose=purpose,
                instruction=instruction,
                structured=True,
                messages=messages,
            )
            self.last_state = state
            if response.toolCalls:
                raise graph_error("PROVIDER_RESPONSE_INVALID")
            try:
                submission = parse_submission(response.content, model)
                if purpose == "DEDUP":
                    if len(set(submission.candidateHandles)) != len(
                        submission.candidateHandles
                    ) or set(submission.candidateHandles) != set(dedup_handles):
                        raise SubmissionIssue(
                            "DEDUP_IDENTITY_MISMATCH",
                            stage="DEDUP",
                            code="PROVIDER_VALIDATION_INVALID",
                        )
                    state = await self._assistant(
                        context, state, response, signal, structured=True
                    )
                if apply_submission:
                    state = await self._submission(
                        context, state, submission, response, signal
                    )
                if purpose == "VALIDATION":
                    position_result = None
                    local_submission = submission
                    if uses_positions(state):
                        position_result = resolve_position_validation(
                            state, submission, validation_materials
                        )
                        submission = position_result.validation
                    elif hasattr(state, "handleRegistry"):
                        from review_harness.application.local_handles import (
                            resolve_validation,
                        )

                        submission = resolve_validation(
                            state, submission, validation_materials
                        )
                    goal, _ = await self.engine.anchors(context, signal)
                    self._validate_corrections(
                        state,
                        submission,
                        validation_materials,
                        goal,
                        position_bindings=position_result.bindings
                        if position_result is not None
                        else None,
                    )
                    if position_result is not None:
                        state = await self._position_record(
                            context,
                            state,
                            response,
                            local_submission,
                            position_result,
                            signal,
                        )
                    state = await self._assistant(
                        context, state, response, signal, structured=True
                    )
                return state, response, submission
            except SubmissionIssue as error:
                issue = error
            # The provider is stateless. Preserve the bounded, redacted reply
            # before its feedback so the next request can correct that reply.
            state = await self._assistant(
                context, state, response, signal, structured=True
            )
            diagnostic = await self._rejection(context, state, response, issue, signal)
            if state.correctionCount >= 2:
                raise issue
            correction_feedback = {}
            if issue.stage == "JSON":
                correction_feedback["instruction"] = (
                    "Return exactly one JSON object matching the supplied schema, "
                    "with no trailing characters, second object or Markdown fences. "
                    "Use jsonError coordinates (one-based line/column, zero-based character position) "
                    "in the original rejected submission when present. Correct and resubmit the complete object; "
                    "the platform will not trim or accept a JSON prefix. Nothing was published."
                )
            if issue.reason == "SCOPE_INVALID" and issue.path == ("checkedScope",):
                _, scope = await self.engine.anchors(context, signal)
                correction_feedback = {
                    "allowedCheckedScope": scope["targetPaths"],
                    "instruction": SCOPE_INSTRUCTION,
                }
            feedback = GraphMessage(
                messageId=str(uuid4()),
                role="user",
                artifactRefs=[diagnostic],
                content=canonical(
                    {
                        "platformSubmissionFeedback": issue.diagnostic(),
                        "diagnosticArtifactRef": diagnostic.model_dump(),
                        "instruction": "Correct only the rejected submission using trusted evidenceReferences. Nothing in this submission was published.",
                        **correction_feedback,
                        **(
                            {
                                "allowedHandles": {
                                    "sources": [
                                        s.view() for s in state.handleRegistry.sources
                                    ],
                                    "evidence": [
                                        e.evidenceHandle
                                        for e in state.handleRegistry.evidence
                                    ],
                                    "candidates": [
                                        c.candidateHandle
                                        for c in state.handleRegistry.candidates
                                    ],
                                }
                            }
                            if hasattr(state, "handleRegistry")
                            else {}
                        ),
                    }
                ).decode(),
            )
            state = state.updated(
                correctionCount=state.correctionCount + 1,
                revision=state.revision + 1,
                messages=[
                    *[message.model_dump() for message in state.messages],
                    feedback.model_dump(),
                ],
            )
            await self._save(state, signal)
            # VALIDATION uses isolated original-material blocks, while large
            # replies are stored outside inline State. Both still need the
            # complete rejected reply plus feedback in the next actual request.
            # This provider-only view is metered by fit()/invoke(); it does not
            # bypass State or Artifact limits or permit restart replay.
            messages = [
                *(messages if messages is not None else state.messages[:-2]),
                {
                    "role": "assistant",
                    "kind": "untrusted-rejected-submission",
                    "content": redacted_submission(response.content)[0],
                },
                feedback,
            ]
            instruction += " Previous submission was rejected; use the concrete platformSubmissionFeedback to correct the complete json object."
            if issue.reason == "VALIDATION_CONTRADICTION":
                instruction += (
                    " A counterexample refutes the candidate claim; a bug-triggering example supporting the claim is not a counterexample."
                    " VERIFIED cannot coexist with counterexampleHolds=true. Reassess the original materials and return a consistent judgment; do not mechanically flip the boolean."
                )

    async def _rejection(self, context, state, response, issue, signal):
        diagnostic = {
            **issue.diagnostic(),
            "diagnosticVersion": "1.0",
            "runId": state.runId,
            "snapshotId": state.snapshotId,
            "modelCallId": response.modelCallId,
            "submissionDigest": sha256(response.content.encode("utf-8")),
        }
        try:
            # Schema-validated reference identity only; never keep the raw
            # submission, arbitrary keys, model prose or exception messages.
            value = strict_json(response.content, limit=65536)
            reference = None
            if issue.path[:1] == ("evidenceCandidates",) and len(issue.path) >= 4:
                checked = EvidenceCandidate.model_validate(
                    value["evidenceCandidates"][issue.path[1]]
                )
                reference = checked.sourceRefs[issue.path[3]].model_dump()
                diagnostic["declaredCoverage"] = checked.coverage
            elif (
                issue.path[:1] == ("candidates",)
                and len(issue.path) >= 4
                and issue.path[2] == "codeReferences"
            ):
                checked = InvestigationSubmission.model_validate(
                    redact_structure(value)[0]
                )
                candidate = checked.candidates[issue.path[1]]
                reference = candidate.codeReferences[issue.path[3]].model_dump()
                diagnostic["evidenceIds"] = candidate.evidenceIds
            if reference is not None:
                known = {
                    ref.path
                    for message in state.messages
                    for ref in message.sourceRefs
                    if ref.path
                }
                if reference.get("path") not in known and "path" in reference:
                    reference["path"] = "<unrecognized-path>"
                diagnostic["rejectedReference"] = reference
        except (ValueError, TypeError, KeyError, IndexError):
            pass
        try:
            artifact = await self.engine.artifacts.publish(
                context,
                canonical(diagnostic).decode(),
                "TOOL_OUTPUT",
                "submission_diagnostic",
                signal,
                producer_type="SYSTEM",
                call_id=response.modelCallId,
                media_type="application/json",
            )
            if artifact.coverage != "FULL":
                raise graph_error("ARTIFACT_LIMIT_EXCEEDED")
        except WorkCancelled:
            raise
        except Exception:
            # No diagnostic means no retry. Preserve the original gate code,
            # and do not turn publication/storage failures into model feedback.
            raise graph_error(issue.code) from None
        return ArtifactRef.model_validate(
            {key: artifact.wire()[key] for key in ArtifactRef.model_fields}
        )

    async def _assistant(self, context, state, response, signal, *, structured=False):
        text, redaction = (
            redacted_submission(response.content)
            if structured
            else redact(response.content)
        )
        refs = []
        if len(text.encode("utf-8")) > 16384:
            artifact = await self.engine.artifacts.publish(
                context,
                text,
                "TOOL_OUTPUT",
                "model_reply",
                signal,
                producer_type="SYSTEM",
                call_id=response.modelCallId,
                prior_redaction=redaction,
            )
            if artifact.coverage != "FULL":
                raise graph_error("CONTEXT_RESPONSE_INVALID")
            ref = ArtifactRef.model_validate(
                {k: artifact.wire()[k] for k in ArtifactRef.model_fields}
            )
            refs.append(ref)
            text = text[:512] + "\nFull reply is in the referenced Artifact."
        message = GraphMessage(
            messageId=str(uuid4()),
            role="assistant",
            content=text,
            toolCalls=response.toolCalls,
            artifactRefs=refs,
        )
        messages = [*state.messages, message]
        return await self._save(
            state.updated(
                revision=state.revision + 1,
                messages=[m.model_dump() for m in messages],
                pendingToolCalls=pending_calls(messages),
            ),
            signal,
        )

    async def _tools(self, context, state, signal):
        inspections = {i.path: i.model_dump() for i in state.inspections}
        progress = {p.artifactId: p.model_dump() for p in state.artifactProgress}

        def inspected(path, start, end, final_page):
            item = inspections.setdefault(
                path,
                {"path": path, "coverage": "PARTIAL", "ranges": [], "finalLine": None},
            )
            ranges = sorted([*item["ranges"], [start, end]])
            merged = []
            for lower, upper in ranges:
                if merged and lower <= merged[-1][1] + 1:
                    merged[-1][1] = max(merged[-1][1], upper)
                else:
                    merged.append([lower, upper])
            item["ranges"] = merged
            if final_page:
                item["finalLine"] = end
            if (
                len(merged) == 1
                and merged[0][0] == 1
                and item["finalLine"] is not None
                and merged[0][1] >= item["finalLine"]
            ):
                item["coverage"] = "FULL"

        for call in state.messages[-1].toolCalls:
            envelope = await self.gateway.call(
                context, call.name, call.arguments, signal, output_budget=8192
            )
            if envelope.status == "CANCELLED":
                raise WorkCancelled()
            if envelope.status == "OK":
                if call.name == "read_file":
                    path = call.arguments["path"]
                    if envelope.artifactRef and envelope.sourceRefs:
                        ref = envelope.sourceRefs[0]
                        progress[envelope.artifactRef["artifactId"]] = {
                            "artifactId": envelope.artifactRef["artifactId"],
                            "path": path,
                            "startLine": ref["startLine"],
                            "endLine": ref["endLine"],
                            "finalPage": not envelope.data.get("hasMore", True),
                            "readable": envelope.artifactRef["redactionState"] == "NONE"
                            and envelope.data.get("captureComplete", False),
                            "nextCursor": None,
                            "completed": False,
                        }
                        inspections.setdefault(
                            path,
                            {
                                "path": path,
                                "coverage": "PARTIAL",
                                "ranges": [],
                                "finalLine": None,
                            },
                        )
                    elif envelope.data.get("redactionState") == "NONE":
                        inspected(
                            path,
                            envelope.data["startLine"],
                            envelope.data["endLine"],
                            not envelope.data.get("hasMore", False),
                        )
                elif call.name == "read_artifact":
                    item = progress.get(call.arguments["artifactId"])
                    if item and not item["completed"]:
                        continuous = call.arguments.get("cursor") == item["nextCursor"]
                        if not continuous:
                            # A skipped page cannot later prove full source coverage.
                            item["readable"] = False
                        item["nextCursor"] = envelope.data.get("nextCursor")
                        item["completed"] = not envelope.data.get("hasMore", False)
                        if item["completed"] and item["readable"]:
                            inspected(
                                item["path"],
                                item["startLine"],
                                item["endLine"],
                                item["finalPage"],
                            )
                elif call.name == "git_diff":
                    supplied = call.arguments.get("cursor")
                    continuous = supplied == state.diffNextCursor and (
                        state.diffStarted or supplied is None
                    )
                    state = state.updated(
                        diffStarted=continuous,
                        diffNextCursor=envelope.data.get("nextCursor"),
                        diffCompleted=state.diffCompleted
                        or (
                            continuous
                            and not envelope.data.get("hasMore", False)
                            and envelope.data.get("redactionState") == "NONE"
                        ),
                    )
            handle_fields = {}
            content = envelope.model_dump()
            if hasattr(state, "handleRegistry"):
                from review_harness.application.handle_observations import observe_tool

                registry, content = observe_tool(state, call, envelope, progress)
                handle_fields["handleRegistry"] = registry.model_dump()
            message = GraphMessage(
                messageId=str(uuid4()),
                role="tool",
                toolCallId=call.toolCallId,
                content=canonical(content).decode(),
                artifactRefs=[envelope.artifactRef] if envelope.artifactRef else [],
                sourceRefs=envelope.sourceRefs[:32],
            )
            messages = [*state.messages, message]
            state = await self._save(
                state.updated(
                    **handle_fields,
                    revision=state.revision + 1,
                    inspections=list(inspections.values()),
                    artifactProgress=list(progress.values()),
                    messages=[m.model_dump() for m in messages],
                    pendingToolCalls=pending_calls(messages),
                ),
                signal,
            )
        return state

    async def _submission(self, context, state, submission, response, signal):
        _, scope = await self.engine.anchors(context, signal)
        if not set(submission.checkedScope) <= set(scope["targetPaths"]) or len(
            set(submission.checkedScope)
        ) != len(submission.checkedScope):
            raise SubmissionIssue(
                "SCOPE_INVALID",
                stage="SUBMISSION",
                path=("checkedScope",),
                code="FINDING_SCOPE_VIOLATION",
            )
        if hasattr(state, "handleRegistry"):
            from review_harness.application.local_handles import (
                prepare_local_submission,
            )

            prepared, candidate_state, _ = await prepare_local_submission(
                self,
                context,
                state,
                submission,
                response,
                signal,
                scope,
            )
            # Independent stores: a failed/uncertain Checkpoint stops after the
            # exact Evidence batch, never enters semantic correction or replay.
            await self.evidence.store.save_batch(context.run, prepared)
            return await self._save(candidate_state, signal)
        ids = list(state.evidenceRefs)
        if len(ids) + len(submission.evidenceCandidates) > 128:
            raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
        if len(state.candidates) + len(submission.candidates) > 32:
            raise graph_error("FINDING_LIMIT_EXCEEDED")
        pool = list(state.candidates)
        for index, candidate in enumerate(submission.candidates):
            unknown = next(
                (
                    position
                    for position, identity in enumerate(candidate.evidenceIds)
                    if identity not in ids
                ),
                None,
            )
            if unknown is not None:
                raise SubmissionIssue(
                    "EVIDENCE_NOT_ACKNOWLEDGED",
                    stage="CANDIDATE",
                    path=("candidates", index, "evidenceIds", unknown),
                    code="FINDING_EVIDENCE_INSUFFICIENT",
                )
            records = [
                await self.evidence.get(context.run, identity)
                for identity in candidate.evidenceIds
            ]
            sources = [ref for record in records for ref in record.sourceRefs]
            for reference_index, ref in enumerate(candidate.codeReferences):
                if ref not in sources:
                    raise SubmissionIssue(
                        "REFERENCE_NOT_IN_EVIDENCE",
                        stage="CANDIDATE",
                        path=("candidates", index, "codeReferences", reference_index),
                    )
            if candidate.codeReferences and not any(
                ref.path in scope["targetPaths"] for ref in candidate.codeReferences
            ):
                raise SubmissionIssue(
                    "SCOPE_INVALID",
                    stage="CANDIDATE",
                    path=("candidates", index, "codeReferences"),
                    code="FINDING_SCOPE_VIOLATION",
                )
            pool.append(
                ReviewCandidate.model_validate(
                    {**candidate.model_dump(), "candidateId": str(uuid4())}
                )
            )
        prepared = await self.evidence.prepare_batch(
            context, submission.evidenceCandidates, response.modelCallId, signal
        )
        records = await self.evidence.store.save_batch(context.run, prepared)
        ids.extend(record.evidenceId for record in records)
        state = state.updated(
            evidenceRefs=ids,
            candidates=[c.model_dump() for c in pool],
            candidateRefs=[c.candidateId for c in pool],
            revision=state.revision + 1,
        )
        feedback = GraphMessage(
            messageId=str(uuid4()),
            role="user",
            content=canonical(
                {
                    "untrustedPlatformReceipt": True,
                    "acceptedEvidenceIds": ids,
                    "candidateIds": state.candidateRefs,
                }
            ).decode(),
        )
        return await self._save(
            state.updated(
                messages=[
                    *[m.model_dump() for m in state.messages],
                    feedback.model_dump(),
                ]
            ),
            signal,
        )

    async def _position_record(
        self, context, state, response, submitted, resolved, signal
    ):
        record = canonical(
            {
                "recordVersion": "1.0",
                "validationProtocol": "POSITION_V1",
                **state.identity(),
                "stateRevision": state.revision,
                "modelCallId": response.modelCallId,
                "submitted": submitted.model_dump(),
                "resolved": resolved.validation.model_dump(),
                "bindings": resolved.bindings,
            }
        )
        artifact = await self.engine.artifacts.publish(
            context,
            record.decode(),
            "TOOL_OUTPUT",
            "position_validation",
            signal,
            producer_type="SYSTEM",
            call_id=response.modelCallId,
            media_type="application/json",
        )
        if artifact.coverage != "FULL" or artifact.sha256 != sha256(record):
            raise graph_error("VALIDATION_RECORD_PERSISTENCE_FAILED")
        ref = ArtifactRef.model_validate(
            {k: artifact.wire()[k] for k in ArtifactRef.model_fields}
        )
        return state.updated(
            validationRecordRefs=[
                *[r.model_dump() for r in state.validationRecordRefs],
                ref.model_dump(),
            ],
            revision=state.revision + 1,
        )

    def _validate_corrections(
        self, state, validation, materials, goal, *, position_bindings=None
    ):
        """Allow factual revisions without changing acknowledged provenance."""
        candidates = {c.candidateId: c for c in current_candidates(state)}
        ids = [v.candidateId for v in validation.validations]
        if len(set(ids)) != len(ids) or set(ids) != set(candidates):
            raise SubmissionIssue(
                "CANDIDATE_ID_MISMATCH",
                stage="VALIDATION",
                path=("validations",),
                code="PROVIDER_VALIDATION_INVALID",
            )
        for index, result in enumerate(validation.validations):
            original = candidates[result.candidateId]

            def invalid(reason, *path):
                raise SubmissionIssue(
                    reason,
                    stage="VALIDATION",
                    path=("validations", index, *path),
                    code="PROVIDER_VALIDATION_INVALID",
                )

            if (
                result.disposition == "VERIFIED"
                and result.validationSummary.counterexampleHolds
            ):
                invalid(
                    "VALIDATION_CONTRADICTION",
                    "validationSummary",
                    "counterexampleHolds",
                )
            if result.disposition == "VERIFIED" and not result.factChecks:
                invalid("FACT_CHECK_REQUIRED", "factChecks")
            sources = {
                item["evidence"]["evidenceId"]: item["materials"]
                for item in materials or []
            }

            # Quotations are provenance checks, not a deterministic semantic proof.
            def texts(value):
                if isinstance(value, str):
                    yield value
                elif isinstance(value, dict):
                    for child in value.values():
                        yield from texts(child)
                elif isinstance(value, list):
                    for child in value:
                        yield from texts(child)

            candidate_text = list(
                texts(
                    {
                        field: original.model_dump()[field]
                        for field in (
                            "title",
                            "claim",
                            "reasoningSummary",
                            "category",
                            "suggestion",
                        )
                    }
                )
            )
            for check_index, check in enumerate(result.factChecks):
                path = ("factChecks", check_index)
                source = sources.get(check.evidenceId, [])
                if (
                    check.evidenceId not in original.evidenceIds
                    or check.sourceIndex >= len(source)
                ):
                    invalid("FACT_SOURCE_INVALID", *path)
                if source[check.sourceIndex]["sourceRef"]["sourceType"] not in {
                    "CODE",
                    "ARTIFACT",
                }:
                    invalid("FACT_IMPLEMENTATION_SOURCE_REQUIRED", *path)
                if check.sourceQuote not in source[check.sourceIndex][
                    "text"
                ] or not any(check.candidateText in t for t in candidate_text):
                    invalid("FACT_QUOTE_INVALID", *path)
                if check.userQuote is not None and check.userQuote not in goal:
                    invalid("FACT_USER_QUOTE_INVALID", *path)
                if (
                    check.outcome == "CORE_CONTRADICTED"
                    and result.disposition != "REJECTED"
                ):
                    invalid("FACT_CORE_REQUIRES_REJECTION", *path)
                if check.outcome == "UNRESOLVED" and (
                    result.disposition == "VERIFIED"
                    or result.validationSummary.assumptionsResolved
                ):
                    invalid("FACT_UNRESOLVED", *path)
                if (
                    check.outcome == "CORRECTED"
                    and result.disposition != "REJECTED"
                    and result.correctedCandidate is None
                ):
                    invalid("FACT_CORRECTION_REQUIRED", *path)
                if (
                    check.outcome == "CORRECTED"
                    and result.disposition != "REJECTED"
                    and result.correctedCandidate is not None
                    and position_bindings is None
                    and any(
                        check.candidateText in text
                        for text in texts(result.correctedCandidate.model_dump())
                    )
                ):
                    invalid("FACT_CORRECTION_UNCHANGED", *path)
                if (
                    position_bindings is not None
                    and check.outcome == "CORRECTED"
                    and result.disposition != "REJECTED"
                    and result.correctedCandidate is not None
                ):
                    binding = next(
                        b
                        for b in position_bindings
                        if (b["validationIndex"], b["checkIndex"])
                        == (index, check_index)
                    )
                    corrected_text = candidate_fields(result.correctedCandidate).get(
                        binding["candidateField"]
                    )
                    if (
                        corrected_text is None
                        or corrected_text.strip() == check.candidateText.strip()
                    ):
                        invalid("FACT_CORRECTION_UNCHANGED", *path)
            corrected = result.correctedCandidate
            if corrected is None:
                continue
            original = candidates[result.candidateId]
            for field in ("passId", "claimKind", "evidenceIds", "codeReferences"):
                if getattr(corrected, field) != getattr(original, field):
                    raise SubmissionIssue(
                        "CORRECTION_SOURCE_MISMATCH",
                        stage="VALIDATION",
                        path=("validations", index, "correctedCandidate", field),
                        code="PROVIDER_VALIDATION_INVALID",
                    )

    async def _finish(self, context, state, signal, submission=None, budget_error=None):
        _, scope = await self.engine.anchors(context, signal)
        evidence = [
            await self.evidence.get(context.run, identity)
            for identity in state.evidenceRefs
        ]
        candidate_pool = current_candidates(state)
        calls = await (
            self.engine.model_calls.dispatched_for_run(state.runId)
            if isinstance(state, MultipassState)
            else self.engine.model_calls.list_for_run(state.runId)
        )
        if isinstance(state, MultipassState):
            calls = [c for c in calls if c.passId == state.activePass]
            eligible = {c.modelCallId for c in calls}
            candidate_evidence = {e for c in candidate_pool for e in c.evidenceIds}
            evidence = [
                e
                for e in evidence
                if e.producerId in eligible or e.evidenceId in candidate_evidence
            ]
        findings, unvalidated = [], len(candidate_pool)
        if candidate_pool and budget_error is None:
            materials = []
            for record in evidence:
                materials.append(
                    {
                        "evidence": record.model_dump(),
                        "materials": await self.evidence.materials(
                            context.run, record.sourceRefs, signal
                        ),
                    }
                )
            # Material can exceed State's per-message allowance. It is a
            # bounded provider-only block, never a hidden mutable State view.
            extra = [
                {
                    "role": "user",
                    "kind": "untrusted-evidence",
                    "content": {
                        "materials": materials,
                        "candidateFactSources": {
                            candidate.candidateId: [
                                {
                                    "evidenceId": item["evidence"]["evidenceId"],
                                    "sourceIndex": index,
                                }
                                for item in materials
                                if item["evidence"]["evidenceId"]
                                in candidate.evidenceIds
                                for index, material in enumerate(item["materials"])
                                if material["sourceRef"]["sourceType"]
                                in {"CODE", "ARTIFACT"}
                            ]
                            for candidate in candidate_pool
                        },
                    },
                }
            ]
            handle_protocol = hasattr(state, "handleRegistry")
            if handle_protocol:
                from review_harness.application.local_handles import (
                    validation_handle_view,
                )

                extra[0]["content"] = (
                    position_validation_view(state, materials)
                    if uses_positions(state)
                    else validation_handle_view(state, materials)
                )
            state, validation_response, validation = await self._json(
                context,
                state,
                signal,
                (
                    PassPositionValidationBatch
                    if isinstance(state, MultipassState)
                    else PositionValidationBatch
                )
                if uses_positions(state)
                else PassLocalValidationBatch
                if isinstance(state, MultipassState)
                else LocalValidationBatch
                if handle_protocol
                else ValidationBatch,
                POSITION_VALIDATION
                if uses_positions(state)
                else LOCAL_VALIDATION
                if handle_protocol
                else VALIDATION,
                purpose="VALIDATION",
                messages=extra,
                validation_materials=materials,
            )
            results = {v.candidateId: v for v in validation.validations}
            for candidate in candidate_pool:
                result = results[candidate.candidateId]
                if (
                    result.disposition == "REJECTED"
                    or result.validationSummary.counterexampleHolds
                ):
                    continue
                if result.correctedCandidate is not None:
                    candidate = (
                        PassCandidate
                        if isinstance(state, MultipassState)
                        else ReviewCandidate
                    ).model_validate(
                        {
                            **result.correctedCandidate.model_dump(),
                            "candidateId": candidate.candidateId,
                        }
                    )
                disposition = result.disposition
                if disposition == "VERIFIED" and (
                    candidate.claimKind != "STATIC_CORRECTNESS"
                    or re.search(
                        r"(?i)(latency|throughput|faster|slower|benchmark|\d\s*(%|ms\b|seconds?\b|bytes?\b)|延迟|吞吐|耗时|百分之)",
                        candidate.category
                        + " "
                        + candidate.title
                        + " "
                        + candidate.claim,
                    )
                    or not candidate.codeReferences
                    or not result.validationSummary.assumptionsResolved
                ):
                    disposition = "RISK"
                findings.append(
                    (
                        PassFinding if isinstance(state, MultipassState) else Finding
                    ).model_validate(
                        {
                            **candidate.model_dump(),
                            "findingId": str(uuid4()),
                            "validationModelCallId": validation_response.modelCallId,
                            "disposition": disposition,
                            "validationSummary": result.validationSummary.model_dump(),
                        }
                    )
                )
            unvalidated = 0
        inspections = {i.path: i.coverage for i in state.inspections}
        checked = (
            submission.checkedScope
            if submission and submission.checkedScope
            else [path for path in scope["targetPaths"] if path in inspections]
        )
        if not checked and evidence:
            checked = sorted(
                {
                    ref.path
                    for e in evidence
                    for ref in e.sourceRefs
                    if ref.sourceType == "CODE" and ref.path in scope["targetPaths"]
                }
            )
        if not checked and not isinstance(state, MultipassState):
            raise graph_error("REVIEW_NO_DELIVERABLE")
        # Context references may lie outside target paths. Finding anchors in
        # confirmed targets still belong in the actual delivered finite scope.
        checked = sorted(
            set(checked)
            | {
                ref.path
                for finding in findings
                for ref in finding.codeReferences
                if ref.path in scope["targetPaths"]
            }
        )
        kind = submission.scopeKind if submission else "FINAL_FILES"
        coverage = submission.coverage if submission else "PARTIAL"
        reasons = list(submission.incompleteReasons if submission else [])
        if budget_error:
            reasons.append(budget_error)
        if (
            coverage != "FULL"
            or (
                kind == "FINAL_FILES"
                and any(inspections.get(p) != "FULL" for p in checked)
            )
            or (kind == "FIXED_DIFF" and not state.diffCompleted)
        ):
            coverage = "PARTIAL"
            reasons.append("SOURCE_COVERAGE_PARTIAL")
        if not checked:
            coverage = "PARTIAL"
            reasons.append("NO_INSPECTED_MATERIAL")
        if isinstance(state, MultipassState) and set(checked) != set(
            scope["targetPaths"]
        ):
            coverage = "PARTIAL"
            reasons.append("TARGET_SCOPE_PARTIAL")
        if unvalidated:
            reasons.append("CANDIDATE_VALIDATION_INCOMPLETE")
        calls = await (
            self.engine.model_calls.dispatched_for_run(state.runId)
            if isinstance(state, MultipassState)
            else self.engine.model_calls.list_for_run(state.runId)
        )
        if isinstance(state, MultipassState):
            calls = [c for c in calls if c.passId == state.activePass]
        await self.engine.validate(context, state, signal)
        for call in calls:
            await self.engine.artifacts.metadata(
                state.runId, call.manifestRef.artifactId
            )
        if (not calls and not isinstance(state, MultipassState)) or any(
            call.status != "LOCAL_CLOSED" or call.usage is None for call in calls
        ):
            raise graph_error("PROVIDER_CALL_INTEGRITY_FAILED")
        package_data = {
            "passResult": {
                "passId": state.passId,
                "status": "INCOMPLETE" if reasons else "COMPLETED",
                "scopeKind": kind,
                "checkedScope": checked,
                "coverage": coverage,
                "limitations": submission.limitations
                if submission
                else ["Investigation stopped at the platform budget."],
                "incompleteReasons": sorted(set(reasons)),
                "unvalidatedCandidates": unvalidated,
                "findingIds": [f.findingId for f in findings],
                "usage": {
                    "modelCalls": len(calls),
                    "inputTokens": sum(c.usage.prompt_tokens for c in calls),
                    "outputTokens": sum(c.usage.completion_tokens for c in calls),
                },
            },
            "evidence": [e.model_dump() for e in evidence],
            "findings": [f.model_dump() for f in findings],
            "diagnostics": {
                "executionMode": "REAL",
                "contextManifestArtifactIds": [c.manifestRef.artifactId for c in calls],
                "modelCallIds": [c.modelCallId for c in calls],
                "meterVersion": self.engine.adapter.capabilities.meter_version,
                "limitations": PROFILE["limitations"],
            },
        }
        self.last_state = state
        if isinstance(state, MultipassState):
            return PassOutcome.model_validate(
                {
                    "passResult": {
                        **package_data["passResult"],
                        "selected": True,
                        "skipReason": None,
                    },
                    "findings": package_data["findings"],
                }
            )
        return ReviewPackage.model_validate(package_data)

    async def execute(self, context, signal, *, state=None):
        if state is None:
            state = await self.engine.initial(context, signal)
            await self.engine.checkpoints.assert_fresh(state)
        await self._save(state, signal)

        async def model(channels):
            state = self._step(self.engine.state_type.model_validate(channels["state"]))
            state = await Compactor(self.engine).fit(context, state, signal)
            state, response = await self.engine.invoke(context, state, signal)
            state = await self._assistant(
                context,
                state,
                response,
                signal,
            )
            return {
                "state": state.model_dump(),
                "route": "tools" if response.toolCalls else "submit",
            }

        async def tools(channels):
            state = await self._tools(
                context,
                self._step(self.engine.state_type.model_validate(channels["state"])),
                signal,
            )
            return {"state": state.model_dump()}

        async def submit(channels):
            state, response, submission = await self._json(
                context,
                self._step(self.engine.state_type.model_validate(channels["state"])),
                signal,
                PassInvestigation
                if isinstance(self.last_state, MultipassState)
                else LocalInvestigation
                if "handleRegistry" in self.engine.state_type.model_fields
                else InvestigationSubmission,
                LOCAL_SUBMISSION
                if "handleRegistry" in self.engine.state_type.model_fields
                else SUBMISSION,
                apply_submission=True,
            )
            if submission.decision == "CONTINUE":
                return {"state": state.model_dump(), "route": "model"}
            package = await self._finish(context, state, signal, submission)
            return {
                "state": self.last_state.model_dump(),
                "route": "end",
                "package": package.model_dump(),
            }

        graph = StateGraph(ReviewChannels)
        graph.add_node("model", model)
        graph.add_node("tools", tools)
        graph.add_node("submit", submit)
        graph.add_edge(START, "model")
        graph.add_conditional_edges(
            "model", lambda c: c["route"], {"tools": "tools", "submit": "submit"}
        )
        graph.add_edge("tools", "model")
        graph.add_conditional_edges(
            "submit", lambda c: c["route"], {"model": "model", "end": END}
        )
        try:
            result = await graph.compile().ainvoke(
                {"state": state.model_dump(), "route": "model", "package": None},
                config={
                    "recursion_limit": (
                        1204
                        if isinstance(state, MultipassState)
                        else self.engine.settings.graph_max_steps
                    )
                    * 4
                    + 4
                },
            )
            return (
                PassOutcome if isinstance(state, MultipassState) else ReviewPackage
            ).model_validate(result["package"])
        except Exception as error:
            if (
                getattr(error, "code", None) not in BUDGET_ERRORS
                or self.last_state is None
                or (
                    not isinstance(state, MultipassState)
                    and not (
                        self.last_state.evidenceRefs or self.last_state.inspections
                    )
                )
            ):
                raise
            return await self._finish(
                context, self.last_state, signal, budget_error=error.code
            )

    async def cleanup(self, context):
        await self.engine.cleanup(context.run_id)

    async def abort(self, run_id):
        await self.engine.cancel_local(run_id)
