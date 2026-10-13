"""Supported operation budgets, including immutable historical policies."""

from typing import Literal

BudgetPolicyVersion = Literal[
    "deepseek-compatible-budget-v1", "deepseek-compatible-budget-v2"
]
CURRENT_POLICY_VERSION = "deepseek-compatible-budget-v2"
CURRENT_CONTEXT_WINDOW = 262144
RESERVED_OUTPUT_TOKENS = 4096
PROTOCOL_SAFETY_MARGIN = 1024
CURRENT_INPUT_BUDGET = (
    CURRENT_CONTEXT_WINDOW - RESERVED_OUTPUT_TOKENS - PROTOCOL_SAFETY_MARGIN
)


def input_budget_for(version):
    if version is None or version == "deepseek-compatible-budget-v1":
        return 27648
    if version == CURRENT_POLICY_VERSION:
        return CURRENT_INPUT_BUDGET
    raise ValueError("Unsupported budget policy")
