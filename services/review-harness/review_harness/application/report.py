"""Deterministic bounded display text; model prose cannot create a finding."""

import html

from review_harness.domain.graph import graph_error
from review_harness.domain.review import ReportDraft

LABELS = {
    "correctness": "正确性",
    "consistency": "一致性",
    "security": "安全",
    "performance": "性能",
    "architecture": "架构",
    "testing": "测试",
    "COMPLETED": "已完成",
    "INCOMPLETE": "未完成",
    "SKIPPED": "未执行",
    "FULL": "材料完整",
    "PARTIAL": "材料部分覆盖",
    "SAMPLED": "抽样材料",
    "FINAL_FILES": "最终文件",
    "FIXED_DIFF": "固定差异",
    "VERIFIED": "已验证",
    "RISK": "待确认风险",
    "SUGGESTION": "改进建议",
    "CRITICAL": "严重",
    "HIGH": "高",
    "MEDIUM": "中",
    "LOW": "低",
    "SELECTED_COMPLETE": "所选有限范围已完成",
    "SELECTED_INCOMPLETE": "所选范围尚未完成",
    "USER_NOT_SELECTED": "用户未选择",
    "RUN_BUDGET_EXHAUSTED": "总预算耗尽，尚未执行",
    "REVIEW_COVERAGE_PARTIAL": "审查覆盖尚不完整",
    "SOURCE_COVERAGE_PARTIAL": "来源材料覆盖不足",
    "TARGET_SCOPE_PARTIAL": "目标范围未全部检查",
    "NO_INSPECTED_MATERIAL": "没有已检查的材料",
    "CANDIDATE_VALIDATION_INCOMPLETE": "候选问题尚未全部验证",
    "final": "最终源码",
    "diff": "变更差异",
}


def label(value):
    return LABELS.get(value, value)


def display(text):
    text = html.escape(text, quote=True)
    for character in "\\`*_[]()#!>|":
        text = text.replace(character, "\\" + character)
    return text


def render_report(pass_result, findings, *, locale="zh-CN"):
    chinese = locale == "zh-CN"
    parts, size = [], 0

    def add(text):
        nonlocal size
        encoded = text.encode("utf-8")
        if size + len(encoded) > 65536:
            raise graph_error("REVIEW_REPORT_LIMIT_EXCEEDED")
        size += len(encoded)
        parts.append(text)

    add("# 正确性审查草稿\n\n" if chinese else "# Correctness review draft\n\n")
    add(
        f"状态：{label(pass_result.status)}；范围：{label(pass_result.scopeKind)}；覆盖：{label(pass_result.coverage)}。\n\n"
        if chinese
        else f"Status: {pass_result.status}; scope: {pass_result.scopeKind}; coverage: {pass_result.coverage}.\n\n"
    )
    for path in pass_result.checkedScope:
        add("- " + display(path) + "\n")
    add("\n## 范围与限制\n\n" if chinese else "\n## Limitations\n\n")
    for item in [*pass_result.limitations, *pass_result.incompleteReasons]:
        add("- " + display(label(item) if chinese else item) + "\n")
    if not findings:
        add(
            "\n本次有限范围未交付经验证的问题，不代表整个项目正确或不存在缺陷。\n"
            if chinese
            else "\nNo validated finding is delivered for this finite scope. This is not a whole-project correctness guarantee.\n"
        )
    for finding in findings:
        add("\n## " + display(finding.title) + "\n\n")
        add(
            f"{label(finding.disposition)} / {label(finding.severity)}。\n\n"
            if chinese
            else f"{finding.disposition} / {finding.severity}.\n\n"
        )
        add(display(finding.claim) + "\n\n")
        add(display(finding.reasoningSummary.trigger) + "\n\n")
        add(display(finding.validationSummary.basis) + "\n\n")
        add(display(finding.suggestion) + "\n")
        for ref in finding.codeReferences:
            add(
                f"\n- {display(ref.path)}（{label(ref.side)}，第 {ref.startLine}-{ref.endLine} 行）\n"
                if chinese
                else f"\n- {display(ref.path)} ({ref.side}, lines {ref.startLine}-{ref.endLine})\n"
            )
    return ReportDraft(markdown="".join(parts), byteSize=size)


def render_multipass_report(pass_results, findings, coverage_status, *, locale="zh-CN"):
    """Render each dimension, and each deduplicated finding at its primary pass."""
    chinese = locale == "zh-CN"
    parts = (
        ["# 多维审查草稿\n\n", f"覆盖情况：{label(coverage_status)}。\n\n"]
        if chinese
        else ["# Multi-pass review draft\n\n", f"Coverage: {coverage_status}.\n\n"]
    )
    for result in pass_results:
        parts.append(
            f"是否选择：{'是' if result.selected else '否'}；未执行原因：{label(result.skipReason) if result.skipReason else '无'}。\n\n"
            if chinese
            else f"Selected: {str(result.selected).lower()}; skipReason: {result.skipReason or 'NONE'}.\n\n"
        )
        section = render_report(
            result, [f for f in findings if f.passId == result.passId], locale=locale
        ).markdown
        parts.append(
            section.replace("# 正确性审查草稿", "## " + label(result.passId), 1)
            if chinese
            else section.replace("# Correctness review draft", "## " + result.passId, 1)
        )
    markdown = "".join(parts)
    if len(markdown.encode("utf-8")) > 65536:
        raise graph_error("REVIEW_REPORT_LIMIT_EXCEEDED")
    return ReportDraft(markdown=markdown, byteSize=len(markdown.encode("utf-8")))
