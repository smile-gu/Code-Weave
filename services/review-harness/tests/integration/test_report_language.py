from types import SimpleNamespace

import pytest

from review_harness.application.report import (
    display,
    render_multipass_report,
    render_report,
)


def pass_result():
    return SimpleNamespace(
        passId="correctness",
        status="COMPLETED",
        scopeKind="FINAL_FILES",
        coverage="FULL",
        checkedScope=["src/CheckoutService.java"],
        limitations=["仅进行静态审查"],
        incompleteReasons=[],
        selected=True,
        skipReason=None,
    )


def test_report_wrapper_is_chinese_preserves_source_and_counts_utf8_bytes():
    draft = render_report(pass_result(), [])
    assert "# 正确性审查草稿" in draft.markdown
    assert "已完成" in draft.markdown and "材料完整" in draft.markdown
    assert "src/CheckoutService.java" in draft.markdown
    assert "不代表整个项目正确" in draft.markdown
    assert draft.byteSize == len(draft.markdown.encode("utf-8"))


def test_multipass_report_uses_chinese_headings_and_never_translates_quotes():
    result = pass_result()
    result.limitations = ["Original quoted text: null != 0"]
    draft = render_multipass_report([result], [], "SELECTED_COMPLETE")
    assert "# 多维审查草稿" in draft.markdown
    assert "## 正确性" in draft.markdown
    assert "所选有限范围已完成" in draft.markdown
    assert display("Original quoted text: null != 0") in draft.markdown
    assert draft.byteSize == len(draft.markdown.encode("utf-8"))


@pytest.mark.parametrize("locale", ["en", "zh-CN"])
def test_both_templates_still_require_exact_structured_report(locale):
    from review_harness.domain.multipass import ReviewResultV3
    from tests.integration.test_multipass_contracts import result_with_finding

    value = result_with_finding()
    parsed = ReviewResultV3.model_validate(value)
    value["reportDraft"] = render_multipass_report(
        parsed.passResults, parsed.findings, parsed.coverageStatus, locale=locale
    ).model_dump()
    assert (
        ReviewResultV3.model_validate(value).reportDraft.model_dump()
        == value["reportDraft"]
    )
    value["reportDraft"]["markdown"] += "\n额外的无依据问题"
    value["reportDraft"]["byteSize"] = len(
        value["reportDraft"]["markdown"].encode("utf-8")
    )
    with pytest.raises(ValueError, match="Report must match"):
        ReviewResultV3.model_validate(value)
