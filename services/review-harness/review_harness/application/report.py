"""Deterministic bounded display text; model prose cannot create a finding."""

import html

from review_harness.domain.graph import graph_error
from review_harness.domain.review import ReportDraft


def display(text):
    text = html.escape(text, quote=True)
    for character in "\\`*_[]()#!>|":
        text = text.replace(character, "\\" + character)
    return text


def render_report(pass_result, findings):
    parts, size = [], 0

    def add(text):
        nonlocal size
        encoded = text.encode("utf-8")
        if size + len(encoded) > 65536:
            raise graph_error("REVIEW_REPORT_LIMIT_EXCEEDED")
        size += len(encoded)
        parts.append(text)

    add("# Correctness review draft\n\n")
    add(
        f"Status: {pass_result.status}; scope: {pass_result.scopeKind}; coverage: {pass_result.coverage}.\n\n"
    )
    for path in pass_result.checkedScope:
        add("- " + display(path) + "\n")
    add("\n## Limitations\n\n")
    for item in [*pass_result.limitations, *pass_result.incompleteReasons]:
        add("- " + display(item) + "\n")
    if not findings:
        add(
            "\nNo validated finding is delivered for this finite scope. This is not a whole-project correctness guarantee.\n"
        )
    for finding in findings:
        add("\n## " + display(finding.title) + "\n\n")
        add(f"{finding.disposition} / {finding.severity}.\n\n")
        add(display(finding.claim) + "\n\n")
        add(display(finding.reasoningSummary.trigger) + "\n\n")
        add(display(finding.validationSummary.basis) + "\n\n")
        add(display(finding.suggestion) + "\n")
        for ref in finding.codeReferences:
            add(
                f"\n- {display(ref.path)} ({ref.side}, lines {ref.startLine}-{ref.endLine})\n"
            )
    return ReportDraft(markdown="".join(parts), byteSize=size)


def render_multipass_report(pass_results, findings, coverage_status):
    """Render each dimension, and each deduplicated finding at its primary pass."""
    parts = ["# Multi-pass review draft\n\n", f"Coverage: {coverage_status}.\n\n"]
    for result in pass_results:
        parts.append(
            f"Selected: {str(result.selected).lower()}; skipReason: {result.skipReason or 'NONE'}.\n\n"
        )
        section = render_report(
            result, [f for f in findings if f.passId == result.passId]
        ).markdown
        parts.append(
            section.replace("# Correctness review draft", "## " + result.passId, 1)
        )
    markdown = "".join(parts)
    if len(markdown.encode("utf-8")) > 65536:
        raise graph_error("REVIEW_REPORT_LIMIT_EXCEEDED")
    return ReportDraft(markdown=markdown, byteSize=len(markdown.encode("utf-8")))
