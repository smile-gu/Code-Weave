"""Versioned documentation evidence, separate from finite meter parity samples."""

from review_harness.domain.budget import CURRENT_CONTEXT_WINDOW, CURRENT_POLICY_VERSION
from review_harness.domain.graph import graph_error
from review_harness.runtime.deepseek_meter import METER_VERSION

PROFILE_VERSION = "deepseek-flash-text-2026-10-12-v2"
PROFILE = {
    "budgetPolicyVersion": CURRENT_POLICY_VERSION,
    "contextRecovery": {
        "status": "DISABLED",
        "reason": "No confirmed structured DeepSeek context rejection code",
        "maxAttemptsPerRun": 1,
    },
    "profileVersion": PROFILE_VERSION,
    "source": "https://api-docs.deepseek.com/quick_start/pricing/",
    "sourceType": "OFFICIAL_DOCUMENTATION",
    "checkedAt": "2026-10-10",
    "documentedModel": "DeepSeek-V4.1-Flash",
    "documentedContextLabel": "1M",
    "documentedFeatures": ["NON_THINKING", "JSON_OUTPUT", "TOOL_CALLS"],
    "operationalContextWindow": CURRENT_CONTEXT_WINDOW,
    "operationalOutputTokens": 4096,
    "protocolSafetyMargin": 1024,
    "meterVersion": METER_VERSION,
    "validatedSubset": [
        "TEXT",
        "NON_STREAMING",
        "THINKING_DISABLED",
        "TOOLS_AUTO",
        "JSON_OBJECT",
    ],
    "hostedSamples": {
        "plain": 17,
        "chinese_code": 35,
        "json": 47,
        "summary": 34,
        "auto_tools": 280,
        "auto_closed_history": 346,
    },
    "hostedSampleDate": "2026-10-10",
    "limitations": [
        "The compatible reserve is an engineering policy, not a proven tokenizer error bound; context-window recovery is disabled without documented error codes.",
        "Finite hosted samples do not certify global token parity.",
        "Reference assets were acquired from hf-mirror.com; main-host identity verification remains outstanding.",
    ],
}


def require_profile(capabilities):
    if (
        capabilities.model_id,
        capabilities.context_window,
        capabilities.reserved_output,
        capabilities.safety_margin,
        capabilities.meter_version,
    ) != (
        "deepseek-flash",
        PROFILE["operationalContextWindow"],
        PROFILE["operationalOutputTokens"],
        PROFILE["protocolSafetyMargin"],
        PROFILE["meterVersion"],
    ):
        raise graph_error("CONTEXT_MODEL_CAPACITY_UNKNOWN")
    return PROFILE
