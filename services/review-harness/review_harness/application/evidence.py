"""Verify original safe material, then publish immutable evidence metadata."""

import io
import json
from uuid import uuid4

from review_harness.application.redaction import redact
from review_harness.application.source_references import diff_lines
from review_harness.domain.graph import graph_error
from review_harness.domain.model import HarnessError, sha256, utc_now
from review_harness.domain.review import EvidenceRecord
from review_harness.domain.snapshot import WorkCancelled
from review_harness.domain.submission import SubmissionIssue
from review_harness.infrastructure.persistence.evidence import EvidenceStore
from review_harness.infrastructure.snapshot import Manifest


def excerpt(raw, start, end):
    try:
        text = raw.decode("utf-8", "strict")
        if "\x00" in text:
            raise ValueError("Binary material")
    except (ValueError, UnicodeError):
        raise SubmissionIssue("SOURCE_KIND_UNSUPPORTED") from None
    selected, seen, size = [], 0, 0
    for seen, line in enumerate(io.StringIO(text), 1):
        if start <= seen <= end:
            if len(line) > 16384:
                raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
            size += len(line.encode("utf-8"))
            if size > 16384:
                raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
            selected.append(line)
    if start < 1 or end < start or end > seen:
        raise SubmissionIssue("RANGE_INVALID", path=("endLine",), stage="COORDINATES")
    return "".join(selected)


class EvidenceService:
    def __init__(self, settings, database, runs, workspaces, artifacts, *, store=None):
        self.settings, self.runs, self.workspaces, self.artifacts = (
            settings,
            runs,
            workspaces,
            artifacts,
        )
        self.store = store or EvidenceStore(database)

    async def get(self, run, identity):
        return await self.store.get(run, identity)

    async def list_for_run(self, run):
        return await self.store.list_for_run(run)

    def _code(self, run, ref, budget):
        root = self.settings.snapshot_root / run.snapshot_id
        _, digest, raw = self.workspaces.read_bounded(
            root,
            "manifest.json",
            self.settings.snapshot_max_manifest_bytes,
            budget,
            collect=True,
        )
        if digest != run.manifest_sha256:
            raise graph_error("EVIDENCE_SNAPSHOT_MISMATCH")
        manifest = Manifest.parse(raw, run, self.settings)
        if ref.side == "final":
            entry = next((e for e in manifest.entries if e.path == ref.path), None)
            if entry is None:
                raise SubmissionIssue(
                    "SOURCE_NOT_FOUND", code="EVIDENCE_SOURCE_NOT_FOUND"
                )
            if entry.type != "REGULAR_FILE":
                raise SubmissionIssue("SOURCE_KIND_UNSUPPORTED")
            size, digest, raw = self.workspaces.read_bounded(
                root,
                "content/" + ref.path,
                self.settings.snapshot_max_file_bytes,
                budget,
                collect=True,
            )
            if (size, digest) != (
                entry.byteSize,
                entry.contentSha256,
            ):
                raise graph_error("EVIDENCE_SNAPSHOT_MISMATCH")
            if digest != ref.sha256:
                raise SubmissionIssue("HASH_MISMATCH", path=("sha256",), stage="DIGEST")
        elif ref.side == "diff":
            if not any(
                ref.path in {c.path, c.oldPath} and not c.binary
                for c in manifest.changes.files
            ):
                raise SubmissionIssue("SOURCE_NOT_FOUND")
            size, digest, raw = self.workspaces.read_bounded(
                root,
                "artifacts/changes.diff",
                self.settings.snapshot_max_diff_bytes,
                budget,
                collect=True,
            )
            if (size, digest) != (
                manifest.changes.diff.byteSize,
                manifest.changes.diff.diffSha256,
            ):
                raise graph_error("EVIDENCE_SNAPSHOT_MISMATCH")
            if digest != ref.sha256:
                raise SubmissionIssue("HASH_MISMATCH", path=("sha256",), stage="DIGEST")
            try:
                diff_text = raw.decode("utf-8", "strict")
            except UnicodeError:
                raise SubmissionIssue("SOURCE_KIND_UNSUPPORTED") from None
            valid = {
                number for number, paths in diff_lines(diff_text) if ref.path in paths
            }
            if ref.endLine - ref.startLine > 4096 or not all(
                number in valid for number in range(ref.startLine, ref.endLine + 1)
            ):
                raise SubmissionIssue(
                    "RANGE_INVALID", path=("endLine",), stage="COORDINATES"
                )
        else:
            # Current entries and fixed diff are stored; full baseline blobs are
            # not. Deleted code must use checked physical diff coordinates.
            raise SubmissionIssue(
                "SOURCE_KIND_UNSUPPORTED", code="EVIDENCE_SOURCE_NOT_FOUND"
            )
        original = excerpt(raw, ref.startLine, ref.endLine)
        safe, _ = redact(raw.decode("utf-8", "strict"))
        selected = excerpt(safe.encode("utf-8"), ref.startLine, ref.endLine)
        return selected, "APPLIED" if selected != original else "NONE"

    async def materials(self, run, refs, signal):
        if signal.is_set():
            raise WorkCancelled()
        materials = []
        for index, ref in enumerate(refs):
            try:
                material = await self._material(run, ref, signal)
            except SubmissionIssue as error:
                raise error.at("sourceRefs", index) from None
            materials.append(material)
        return materials

    async def _material(self, run, ref, signal):
        coverage = "FULL"
        redaction = "NONE"
        if ref.sourceType == "CODE":
            text, redaction = await self.workspaces.run_file_job(
                run,
                lambda budget: self._code(run, ref, budget),
                signal,
                self.settings.tool_timeout_seconds,
            )
        elif ref.sourceType == "USER_INPUT":
            text = json.loads(run.request_payload)["input"]["message"]
            if sha256(text.encode("utf-8")) != ref.sha256:
                raise SubmissionIssue("HASH_MISMATCH", path=("sha256",), stage="DIGEST")
        else:
            try:
                meta = await self.artifacts.metadata(run.run_id, ref.artifactId)
            except HarnessError as error:
                if error.code == "ARTIFACT_NOT_FOUND":
                    raise SubmissionIssue(
                        "SOURCE_NOT_FOUND",
                        path=("artifactId",),
                        stage="ARTIFACT_LOOKUP",
                    ) from None
                raise
            if meta.snapshot_id != run.snapshot_id:
                raise graph_error("EVIDENCE_INTEGRITY_FAILED")
            if meta.sha256 != ref.sha256:
                raise SubmissionIssue("HASH_MISMATCH", path=("sha256",), stage="DIGEST")
            if meta.coverage != ref.coverage:
                raise SubmissionIssue(
                    "COVERAGE_MISMATCH", path=("coverage",), stage="ARTIFACT_METADATA"
                )
            if meta.producer_type != "TOOL" or meta.kind not in {
                "SOURCE_EXCERPT",
                "TOOL_OUTPUT",
            }:
                raise SubmissionIssue(
                    "SOURCE_KIND_UNSUPPORTED", code="EVIDENCE_ARTIFACT_NOT_READY"
                )
            parts, cursor, size = [], None, 0
            while True:
                page = await self.artifacts.content(
                    run.run_id, ref.artifactId, cursor, signal=signal
                )
                size += len(page.text.encode("utf-8"))
                if size > self.settings.artifact_max_bytes:
                    raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
                parts.append(page.text)
                cursor = page.nextCursor
                if cursor is None:
                    break
            text = excerpt("".join(parts).encode("utf-8"), ref.startLine, ref.endLine)
            coverage = meta.coverage
            redaction = meta.redaction_state
        text, detected = redact(text)
        redaction = (
            "APPLIED" if detected == "APPLIED" or redaction == "APPLIED" else "NONE"
        )
        limit = 32768 if ref.sourceType == "USER_INPUT" else 16384
        if len(text.encode("utf-8")) > limit:
            raise graph_error("EVIDENCE_LIMIT_EXCEEDED")
        return {
            "sourceRef": ref.model_dump(),
            "text": text,
            "coverage": "PARTIAL" if redaction == "APPLIED" else coverage,
            "redactionState": redaction,
        }

    async def capture(self, context, candidate, producer_id, signal):
        records = await self.prepare_batch(context, [candidate], producer_id, signal)
        return (await self.store.save_batch(context.run, records))[0]

    async def prepare_batch(self, context, candidates, producer_id, signal):
        run = await self.runs.get_run(context.run_id)
        if signal.is_set() or run.status == "CANCELLING":
            raise WorkCancelled()
        if (
            run.status != "RUNNING"
            or run.resource_state != "ACTIVE"
            or run.snapshot_id != context.run.snapshot_id
        ):
            raise graph_error("CONTEXT_RUN_NOT_ACTIVE")
        records = []
        for index, candidate in enumerate(candidates):
            try:
                record = await self._prepare(run, candidate, producer_id, signal)
            except SubmissionIssue as error:
                raise error.at("evidenceCandidates", index) from None
            records.append(record)
        return records

    async def _prepare(self, run, candidate, producer_id, signal):
        materials = await self.materials(run, candidate.sourceRefs, signal)
        if candidate.coverage == "FULL":
            for index, material in enumerate(materials):
                if material["coverage"] != "FULL":
                    raise SubmissionIssue(
                        "COVERAGE_INCOMPLETE",
                        path=("sourceRefs", index),
                        stage="MATERIAL_COVERAGE",
                    )
        summary, _ = redact(candidate.summary)
        record = EvidenceRecord.model_validate(
            {
                **candidate.model_dump(),
                "summary": summary,
                "evidenceId": str(uuid4()),
                "runId": run.run_id,
                "snapshotId": run.snapshot_id,
                "producerType": "MODEL_SUBMISSION",
                "producerId": producer_id,
                "capturedAt": utc_now(),
            }
        )
        return record
