"""Versioned DeepSeek engineering reserve; not a proven tokenizer error bound."""

from dataclasses import dataclass

from review_harness.domain.budget import (
    CURRENT_CONTEXT_WINDOW,
    CURRENT_INPUT_BUDGET,
    CURRENT_POLICY_VERSION,
)
from review_harness.domain.graph import graph_error

POLICY_VERSION = CURRENT_POLICY_VERSION


@dataclass(frozen=True)
class TokenBudgetPolicy:
    version: str = POLICY_VERSION
    minimum_reserve: int = 1024
    reserve_percent: int = 10
    context_window: int = CURRENT_CONTEXT_WINDOW
    input_budget: int = CURRENT_INPUT_BUDGET

    def validate(self):
        if self != TokenBudgetPolicy():
            raise graph_error("CONTEXT_BUDGET_POLICY_UNSUPPORTED")

    def reserve(self, estimate):
        self.validate()
        if type(estimate) is not int or estimate < 0:
            raise graph_error("CONTEXT_REQUEST_INVALID")
        return max(self.minimum_reserve, (estimate + 9) // 10)

    def conservative(self, estimate):
        return estimate + self.reserve(estimate)

    def warning(self, estimate, actual):
        if actual <= estimate:
            return None
        conservative = self.conservative(estimate)
        return {
            "code": "INPUT_ESTIMATE_UNDERCOUNT"
            if actual <= conservative
            else "INPUT_ESTIMATE_RESERVE_EXCEEDED",
            "estimatedInputTokens": estimate,
            "conservativeInputTokens": conservative,
            "uncertaintyReserveTokens": conservative - estimate,
            "actualInputTokens": actual,
            "differenceTokens": actual - estimate,
            "policyVersion": self.version,
        }


def context_rejection(status, value, confirmed_codes):
    """Only documented structured codes may classify a definitely rejected call."""
    if status != 400 or not isinstance(value, dict):
        return False
    error = value.get("error")
    return (
        isinstance(error, dict)
        and isinstance(error.get("code"), str)
        and error["code"] in confirmed_codes
    )
