"""Deterministic projections shared by read-only views and Evidence checks."""

import io
import re

_HUNK_HEADER = re.compile(r"^@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@(?:[^\r\n]*)\r?\n?$")


def final_reference(ref):
    return {
        "sourceType": "CODE",
        "side": "final",
        "path": ref["path"],
        "sha256": ref["contentSha256"],
        "startLine": ref["startLine"],
        "endLine": ref["endLine"],
    }


def diff_lines(text):
    """Physical full-Diff LF coordinates, including deleted paths and renames.

    Headers and hunk markers are not code. A context/change line belongs to
    the current old/new paths; consumers still check Snapshot ownership.
    Consume hunk counts before recognizing headers: `---` and `+++` inside
    a hunk are ordinary deletion/addition lines. Only LF advances coordinates.
    """
    paths = set()
    old_remaining = new_remaining = 0
    for number, line in enumerate(io.StringIO(text), 1):
        if old_remaining or new_remaining:
            if line.rstrip("\r\n") == "\\ No newline at end of file":
                continue
            old_used = int(line.startswith(("-", " ")))
            new_used = int(line.startswith(("+", " ")))
            if (
                (old_used or new_used)
                and old_used <= old_remaining
                and new_used <= new_remaining
            ):
                old_remaining -= old_used
                new_remaining -= new_used
                if paths:
                    yield number, tuple(sorted(paths))
                continue
            # Malformed/truncated hunks must not grant references to metadata.
            old_remaining = new_remaining = 0
        if line.startswith("diff --git "):
            paths = set()
        elif line.startswith(("--- ", "+++ ")):
            path = line[4:].rstrip("\r\n").split("\t", 1)[0]
            if path.startswith(("a/", "b/")):
                paths.add(path[2:])
        elif match := _HUNK_HEADER.fullmatch(line):
            old_remaining = int(match[1]) if match[1] is not None else 1
            new_remaining = int(match[2]) if match[2] is not None else 1


def diff_page_references(original, displayed, start, end, digest, allowed_paths):
    selected = displayed[start:end]
    first = displayed.count("\n", 0, start) + 1
    last = first + selected.count("\n") - int(selected.endswith("\n"))
    coordinates = {
        "startLine": first,
        "endLine": max(first, last),
        "startsMidLine": start > 0 and displayed[start - 1] != "\n",
        "endsMidLine": end < len(displayed)
        and end > start
        and displayed[end - 1] != "\n",
    }
    ranges = {}
    if selected:
        for number, paths in diff_lines(original):
            if number < first:
                continue
            if number > last:
                break
            for path in paths:
                if path not in allowed_paths:
                    continue
                groups = ranges.setdefault(path, [])
                if groups and groups[-1][1] == number - 1:
                    groups[-1][1] = number
                else:
                    groups.append([number, number])
    refs = [
        {
            "sourceType": "CODE",
            "side": "diff",
            "path": path,
            "sha256": digest,
            "startLine": first_line,
            "endLine": last_line,
        }
        for path, groups in sorted(ranges.items())
        for first_line, last_line in groups
    ]
    return coordinates, refs
