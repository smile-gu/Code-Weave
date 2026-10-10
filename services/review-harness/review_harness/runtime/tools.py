import fnmatch
import logging
import re
import time
from uuid import uuid4

from pydantic import ValidationError

from review_harness.application.redaction import redact
from review_harness.domain.model import HarnessError, canonical
from review_harness.domain.snapshot import WorkCancelled, WorkspaceError
from review_harness.domain.tools import PARAMETERS, ToolEnvelope, ToolError
from review_harness.infrastructure.paths import portable
from review_harness.infrastructure.snapshot import Manifest


def tool_error(code):
    return HarnessError(code, 422, "工具操作未能完成")


def glob_pattern(pattern):
    # Restricted portable segments, without native glob filesystem expansion.
    if not pattern or "\\" in pattern or ":" in pattern or "\0" in pattern:
        raise tool_error("TOOL_ARGUMENT_INVALID")
    parts = pattern.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise tool_error("TOOL_ARGUMENT_INVALID")
    return GlobMatcher(tuple(parts))


class GlobMatcher:
    """Segment DP; fnmatch's atomic star matching avoids unbounded regex backtracking."""

    def __init__(self, parts):
        self.parts = parts

    def fullmatch(self, path):
        segments = path.split("/")
        previous = [True] + [False] * len(segments)
        for part in self.parts:
            current = [False] * (len(segments) + 1)
            if part == "**":
                current[0] = previous[0]
                for index in range(1, len(current)):
                    current[index] = previous[index] or current[index - 1]
            else:
                for index, segment in enumerate(segments, 1):
                    literal_brackets = "".join(
                        "[[]" if char == "[" else "[]]" if char == "]" else char
                        for char in part
                    )
                    current[index] = previous[index - 1] and fnmatch.fnmatchcase(
                        segment, literal_brackets
                    )
            previous = current
        return previous[-1]


def source_lines(text, budget):
    """LF/CRLF source lines, retained endings and bounded per-line iteration."""
    offset = 0
    while offset < len(text):
        budget.check()
        end = text.find("\n", offset)
        if end < 0:
            yield text[offset:]
            break
        yield text[offset : end + 1]
        offset = end + 1


class ToolGateway:
    """One authorized entrance; context and capabilities are injected by platform.

    Handlers never accept a model-supplied root or execution identity. Blocking
    reads share WorkspaceManager's bounded actual-Future resource evidence.
    """

    def __init__(self, settings, runs, workspaces, artifacts, *, allowed=None):
        self.settings, self.runs = settings, runs
        self.workspaces, self.artifacts = workspaces, artifacts
        self.allowed = frozenset(PARAMETERS if allowed is None else allowed)

    async def call(self, context, name, arguments, signal, *, output_budget=None):
        # Consumer budget is platform input, never a model parameter. Gateway
        # owns both Spill and page sizing before State sees the complete envelope.
        if output_budget is not None and (
            type(output_budget) is not int or not 1024 <= output_budget <= 32768
        ):
            raise ValueError("Invalid platform tool output budget")
        output_bytes = (
            min(self.settings.tool_output_bytes, output_budget)
            if output_budget is not None
            else self.settings.tool_output_bytes
        )
        if not isinstance(name, str):
            name = "unknown"
        call_id, started = str(uuid4()), time.monotonic()
        code, size = "OK", 0
        try:
            result = await self._execute(
                context, name, arguments, signal, call_id, output_bytes
            )
            code = result.error.code if result.error else "OK"
            size = len(canonical(result.model_dump()))
            return result
        except (HarnessError, WorkspaceError) as error:
            code = error.code
            raise
        finally:
            logging.getLogger("review_harness").info(
                '{"toolCallId":"%s","toolName":"%s","elapsedMs":%d,"byteSize":%d,"errorCode":"%s"}',
                call_id,
                name if name in PARAMETERS else "unknown",
                int((time.monotonic() - started) * 1000),
                size,
                code,
            )

    async def _execute(self, context, name, arguments, signal, call_id, output_bytes):
        deadline = time.monotonic() + self.settings.tool_timeout_seconds
        safe_name = name if name in PARAMETERS else "unknown"

        def remaining():
            seconds = deadline - time.monotonic()
            if seconds <= 0:
                raise tool_error("TOOL_TIMEOUT")
            return seconds

        def envelope(
            status,
            data=None,
            refs=None,
            coverage="FULL",
            truncated=False,
            artifact=None,
            code=None,
        ):
            return ToolEnvelope(
                toolCallId=call_id,
                toolName=safe_name,
                status=status,
                data=data,
                sourceRefs=refs or [],
                coverage=coverage,
                truncated=truncated,
                artifactRef=artifact,
                error=ToolError(code=code, message="工具操作未能完成", retryable=False)
                if code
                else None,
            )

        try:
            if name not in PARAMETERS or name not in self.allowed:
                raise tool_error("TOOL_ACCESS_DENIED")
            try:
                params = PARAMETERS[name].model_validate(arguments)
                if name == "read_file":
                    portable(params.path)
                if name == "glob_files":
                    glob_pattern(params.pattern)
                if name == "search_code" and params.glob is not None:
                    glob_pattern(params.glob)
            except (ValidationError, ValueError, UnicodeError, WorkspaceError):
                raise tool_error("TOOL_ARGUMENT_INVALID") from None
            current = await self.runs.get_run(context.run.run_id)
            if signal.is_set() or current.status == "CANCELLING":
                raise WorkCancelled()
            if (
                current.status != "RUNNING"
                or current.snapshot_id != context.run.snapshot_id
            ):
                raise tool_error("TOOL_ACCESS_DENIED")
            if name == "read_artifact":
                # Size the page against the actual consumer envelope overhead;
                # PARTIAL and false are the longest coverage/bool spellings.
                overhead = (
                    len(canonical(envelope("OK", {}, coverage="PARTIAL").model_dump()))
                    - 2
                )
                page = await self.artifacts.content(
                    current.run_id,
                    params.artifactId,
                    params.cursor,
                    signal=signal,
                    page_budget=output_bytes - overhead,
                    seconds=remaining(),
                )
                output = envelope(
                    "OK",
                    page.model_dump(),
                    coverage=page.coverage,
                    truncated=page.hasMore,
                )
                # Content page and Tool envelope have different wrapper overhead.
                if len(canonical(output.model_dump())) > output_bytes:
                    return envelope("ERROR", code="TOOL_LIMIT_EXCEEDED")
                if signal.is_set():
                    raise WorkCancelled()
                return output
            result = await self.workspaces.run_file_job(
                current,
                lambda budget: self._read(context, name, params, budget, output_bytes),
                signal,
                remaining(),
            )
            data, refs, coverage, truncated = result
            output = envelope("OK", data, refs, coverage, truncated)
            if len(canonical(output.model_dump())) > output_bytes:
                if name == "git_diff":
                    raise tool_error("TOOL_LIMIT_EXCEEDED")
                artifact = await self.artifacts.publish(
                    context,
                    canonical(
                        {
                            "toolName": name,
                            "data": data,
                            "sourceRefs": refs,
                            "coverage": coverage,
                            "truncated": truncated,
                        }
                    ).decode(),
                    "SOURCE_EXCERPT" if name == "read_file" else "TOOL_OUTPUT",
                    name,
                    signal,
                    call_id=call_id,
                    coverage=coverage,
                    media_type="application/json",
                    seconds=remaining(),
                    prior_redaction="APPLIED"
                    if data.get("redactionState") == "APPLIED"
                    or any(
                        item.get("redactionState") == "APPLIED"
                        for item in data.get("matches", [])
                    )
                    else "NONE",
                )
                reference = {
                    key: artifact.wire()[key]
                    for key in (
                        "artifactId",
                        "sha256",
                        "byteSize",
                        "coverage",
                        "redactionState",
                    )
                }
                # Bounded deterministic preview, never a substitute for READY storage.
                preview = canonical(data).decode()[:128]
                output = envelope(
                    "OK",
                    {"preview": preview},
                    refs[:1],
                    artifact.coverage,
                    True,
                    reference,
                )
            latest = await self.runs.get_run(current.run_id)
            remaining()
            if signal.is_set() or latest.status == "CANCELLING":
                raise WorkCancelled()
            if len(canonical(output.model_dump())) > output_bytes:
                raise tool_error("TOOL_LIMIT_EXCEEDED")
            return output
        except WorkCancelled:
            return envelope("CANCELLED", coverage="PARTIAL", code="TOOL_CANCELLED")
        except WorkspaceError as error:
            # Input integrity and uncertain file resources are fatal to the Run.
            # Supervisor receives them; an Agent cannot ignore them as Tool errors.
            if error.code in {"SNAPSHOT_LIMIT_EXCEEDED"}:
                return envelope("ERROR", coverage="PARTIAL", code="TOOL_TIMEOUT")
            raise
        except HarnessError as error:
            if error.code.startswith("TOOL_") or error.code in {
                "ARTIFACT_NOT_FOUND",
                "ARTIFACT_CURSOR_INVALID",
                "ARTIFACT_CONTENT_UNSUPPORTED",
            }:
                return envelope("ERROR", coverage="PARTIAL", code=error.code)
            raise

    def _manifest(self, context, budget):
        # Trust original Snapshot manifest rather than mutable executor dictionaries.
        _, digest, raw = self.workspaces.read_bounded(
            self.settings.snapshot_root / context.run.snapshot_id,
            "manifest.json",
            self.settings.snapshot_max_manifest_bytes,
            budget,
            collect=True,
        )
        if digest != context.run.manifest_sha256:
            raise WorkspaceError("SNAPSHOT_MANIFEST_MISMATCH")
        self.workspaces.assert_owner(context.workspace_root, context.run, budget)
        return Manifest.parse(raw, context.run, self.settings)

    def _source(self, context, entry, budget):
        try:
            size, digest, raw = self.workspaces.read_bounded(
                context.workspace_root / "content",
                entry.path,
                entry.byteSize,
                budget,
                collect=True,
            )
        except OSError:
            raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH") from None
        except WorkspaceError as error:
            if (
                error.code == "SNAPSHOT_LIMIT_EXCEEDED"
                and time.monotonic() < budget.deadline
            ):
                raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH") from None
            raise
        if (size, digest) != (entry.byteSize, entry.contentSha256):
            raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH")
        try:
            if b"\0" in raw:
                raise UnicodeError()
            text = raw.decode("utf-8", "strict")
        except UnicodeError:
            raise tool_error("TOOL_CONTENT_UNSUPPORTED") from None
        return text

    def _read(self, context, name, params, budget, output_bytes):
        manifest = self._manifest(context, budget)
        if name == "git_diff":
            return self._git_diff(context, manifest, params, budget, output_bytes)
        handlers = {
            "glob_files": self._glob_files,
            "read_file": self._read_file,
            "search_code": self._search_code,
        }
        handler = handlers.get(name)
        if handler is None:
            raise tool_error("TOOL_ACCESS_DENIED")
        return handler(context, manifest, params, budget)

    def _glob_files(self, context, manifest, params, budget):
        entries = manifest.entries
        pattern = glob_pattern(params.pattern)
        matches = []
        for entry in entries:
            budget.check()
            if pattern.fullmatch(entry.path):
                matches.append(entry.model_dump())
        limited = len(matches) > params.maxResults
        return (
            {"files": matches[: params.maxResults]},
            [],
            "PARTIAL" if limited else "FULL",
            limited,
        )

    def _read_file(self, context, manifest, params, budget):
        entries = manifest.entries
        entry = next((entry for entry in entries if entry.path == params.path), None)
        if entry is None:
            raise tool_error("TOOL_SOURCE_NOT_FOUND")
        if entry.type != "REGULAR_FILE":
            raise tool_error("TOOL_CONTENT_UNSUPPORTED")
        original = self._source(context, entry, budget)
        text, _ = redact(original)
        redaction = "NONE"
        selected, more, seen = [], False, 0
        for number, (original_line, line) in enumerate(
            zip(source_lines(original, budget), source_lines(text, budget)), 1
        ):
            seen = number
            if number < params.startLine:
                continue
            if len(selected) == params.maxLines:
                more = True
                break
            selected.append(line)
            if original_line != line:
                redaction = "APPLIED"
        if params.startLine > max(1, seen):
            raise tool_error("TOOL_ARGUMENT_INVALID")
        end = params.startLine + len(selected) - 1
        refs = [
            {
                "path": entry.path,
                "startLine": params.startLine,
                "endLine": max(params.startLine, end),
                "contentSha256": entry.contentSha256,
            }
        ]
        partial = params.startLine != 1 or more
        return (
            {
                "text": "".join(selected),
                "startLine": params.startLine,
                "endLine": end,
                "hasMore": more,
                "redactionState": redaction,
            },
            refs,
            "PARTIAL" if partial else "FULL",
            more,
        )

    def _search_code(self, context, manifest, params, budget):
        entries = manifest.entries
        pattern = glob_pattern(params.glob) if params.glob else None
        results, refs, skipped, scanned = [], [], 0, 0
        partial, excerpt_partial = False, False
        for entry in entries:
            budget.check()
            if pattern and not pattern.fullmatch(entry.path):
                continue
            if entry.type != "REGULAR_FILE":
                skipped += 1
                continue
            if scanned + entry.byteSize > self.settings.tool_scan_bytes:
                partial = True
                break
            scanned += entry.byteSize
            try:
                text = self._source(context, entry, budget)
            except HarnessError as error:
                if error.code != "TOOL_CONTENT_UNSUPPORTED":
                    raise
                skipped += 1
                continue
            # Redact whole bounded files before selecting lines, so a
            # multi-line private key cannot escape via a single match.
            displayed_text, _ = redact(text)
            displayed_lines = iter(source_lines(displayed_text, budget))
            displayed_number, displayed_line = 0, ""
            number, previous_start = 1, 0
            source_line_start = 0
            previous_number = 0
            for match in re.finditer(re.escape(params.query), text):
                budget.check()
                number += text.count("\n", previous_start, match.start())
                previous_start = match.start()
                if len(results) == params.maxResults:
                    partial = True
                    break
                if number != previous_number:
                    source_line_start = text.rfind("\n", 0, match.start()) + 1
                    while displayed_number < number:
                        displayed_line = next(displayed_lines, "")
                        displayed_number += 1
                    snippet = displayed_line[:4096].rstrip("\r\n")
                    original_end = text.find("\n", source_line_start)
                    if original_end < 0:
                        original_end = len(text)
                    original_snippet = text[
                        source_line_start : min(original_end, source_line_start + 4096)
                    ].rstrip("\r\n")
                    redaction = "APPLIED" if snippet != original_snippet else "NONE"
                    line_ending = (
                        2
                        if displayed_line.endswith("\r\n")
                        else 1
                        if displayed_line.endswith("\n")
                        else 0
                    )
                    clipped = len(displayed_line) - line_ending > 4096
                    previous_number = number
                excerpt_partial = excerpt_partial or clipped
                end_line = number + params.query.count("\n")
                results.append(
                    {
                        "path": entry.path,
                        "line": number,
                        "column": match.start() - source_line_start + 1,
                        "endLine": end_line,
                        "text": snippet,
                        "snippetTruncated": clipped,
                        "redactionState": redaction,
                    }
                )
                refs.append(
                    {
                        "path": entry.path,
                        "startLine": number,
                        "endLine": end_line,
                        "contentSha256": entry.contentSha256,
                    }
                )
            if partial:
                break
        partial = partial or skipped > 0
        return (
            {
                "matches": results,
                "skippedFiles": skipped,
                "scannedBytes": scanned,
                "scanComplete": not partial,
            },
            refs,
            "PARTIAL" if partial or excerpt_partial else "FULL",
            partial or excerpt_partial,
        )

    def _git_diff(self, context, manifest, params, budget, output_bytes):
        expected = manifest.changes.diff
        try:
            size, digest, raw = self.workspaces.read_bounded(
                context.workspace_root,
                expected.path,
                expected.byteSize,
                budget,
                collect=True,
            )
        except OSError:
            raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH") from None
        except WorkspaceError as error:
            if (
                error.code == "SNAPSHOT_LIMIT_EXCEEDED"
                and time.monotonic() < budget.deadline
            ):
                raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH") from None
            raise
        if (size, digest) != (expected.byteSize, expected.diffSha256):
            raise WorkspaceError("SNAPSHOT_CONTENT_MISMATCH")
        try:
            original = raw.decode("utf-8", "strict")
        except UnicodeError:
            raise tool_error("TOOL_CONTENT_UNSUPPORTED") from None
        text, redaction = redact(original)
        display = text.encode()
        # Cursor binds the original fixed source, offset addresses the
        # deterministic redacted projection; no Git subprocess is invoked.
        identity = "fixed-diff"
        offset = self.artifacts.cursors.decode(
            params.cursor, context.run.run_id, identity, digest
        )
        if offset > len(display):
            raise tool_error("TOOL_ARGUMENT_INVALID")
        try:
            remaining = display[offset:].decode()
        except UnicodeError:
            raise tool_error("TOOL_ARGUMENT_INVALID") from None
        # One code point can need six JSON bytes (e.g. a control character).
        # Reserve protocol/cursor/source wrappers separately from body bytes.
        limit = (output_bytes - 1024) // 6
        if limit <= 0:
            raise tool_error("TOOL_LIMIT_EXCEEDED")
        selected = remaining[: max(1, limit)]
        next_offset = offset + len(selected.encode())
        more = next_offset < len(display)
        cursor = (
            self.artifacts.cursors.encode(
                context.run.run_id, identity, digest, next_offset
            )
            if more
            else None
        )
        return (
            {
                "text": selected,
                "nextCursor": cursor,
                "hasMore": more,
                "redactionState": redaction,
            },
            [{"diffSha256": digest}],
            "PARTIAL" if more or offset else "FULL",
            more,
        )
