package com.codingreview.controlplane.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** Explicit product selection and version negotiation, before any execution reservation. */
public final class ReviewPlan {
    public static final List<String> PASS_ORDER = List.of(
            "correctness", "consistency", "security", "performance", "architecture", "testing");

    private ReviewPlan() {}

    public static JsonNode create(List<String> selection, JsonNode feeAuthorization) {
        if (feeAuthorization == null || feeAuthorization.isNull()) throw new ReviewException("REVIEW_PLAN_INVALID");
        List<String> requested = selection == null ? PASS_ORDER : selection;
        if (requested.stream().anyMatch(Objects::isNull)) throw new ReviewException("REVIEW_PLAN_INVALID");
        if (requested.isEmpty() || new HashSet<>(requested).size() != requested.size()
                || !PASS_ORDER.containsAll(requested)) throw new ReviewException("REVIEW_PLAN_INVALID");
        var value = ReviewJson.parse(ReviewJson.canonical(Map.of(
                "planVersion", "1.0",
                "selectedPasses", PASS_ORDER.stream().filter(requested::contains).toList(),
                "executionBudget", Map.of(
                        "maxModelCallsPerPass", 300, "maxModelCallsPerRun", 2000, "maxDedupCalls", 10,
                        "requestTimeoutSeconds", 120, "passTimeoutSeconds", 1800,
                        "runTimeoutSeconds", 10800, "summaryTimeoutSeconds", 600),
                "feeAuthorization", feeAuthorization)));
        validate(value);
        return value;
    }

    public static void validate(JsonNode value) {
        ReviewWireSchemas.validate("review-plan-v1", value);
        List<String> selected = new ArrayList<>();
        value.path("selectedPasses").forEach(p -> selected.add(p.asText()));
        if (!selected.equals(PASS_ORDER.stream().filter(selected::contains).toList()))
            throw new ReviewException("REVIEW_PLAN_INVALID");
        try { Instant.parse(value.path("feeAuthorization").path("confirmedAt").asText()); }
        catch (RuntimeException error) { throw new ReviewException("REVIEW_PLAN_INVALID"); }
    }

    public static void requireCompatibility(JsonNode capabilities, String executionMode) {
        if (!capabilities.path("protocolVersion").asText().equals("1.1"))
            throw new ReviewException("EXECUTION_CONTRACT_UNSUPPORTED");
        ReviewWireSchemas.validate("review-capabilities-v2", capabilities);
        if (!capabilities.path("configuredContract").path("executionMode").asText().equals(executionMode))
            throw new ReviewException("EXECUTION_CONTRACT_UNSUPPORTED");
        if (!capabilities.path("status").asText().equals("READY")
                || !capabilities.path("feeReservation").asText().equals("READY")
                || !capabilities.path("failureCodes").isEmpty())
            throw new ReviewException("REVIEW_HARNESS_NOT_READY");
    }
}
