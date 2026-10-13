package com.codingreview.controlplane.review;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Projection of Python lifecycle facts, never inferred from Run success. */
public final class ReviewPassProgress {
    private ReviewPassProgress() {}

    private static void require(boolean value) {
        if (!value) throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
    }

    public static void validate(JsonNode passes, UUID runId) {
        require(passes.isArray() && passes.size() == 6);
        String[] order = {
            "correctness", "consistency", "security", "performance", "architecture", "testing"
        };
        int running = 0;
        boolean priorReady = false;
        for (int i = 0; i < 6; i++) {
            var pass = passes.get(i);
            ReviewWireSchemas.validate("review-pass-progress-v1", pass);
            require(
                    pass.path("runId").asText().equals(runId.toString())
                            && pass.path("ordinal").asInt() == i
                            && pass.path("passId").asText().equals(order[i]));
            String state = pass.path("status").asText();
            if (!pass.path("selected").asBoolean())
                require(
                        state.equals("SKIPPED")
                                && pass.path("reason").asText().equals("USER_NOT_SELECTED"));
            require(pass.path("stateRevision").isNull() == pass.path("stateDigest").isNull());
            for (String name : new String[] {"startedAt", "finishedAt"})
                if (!pass.path(name).isNull()) {
                    try {
                        Instant.parse(pass.path(name).asText());
                    } catch (RuntimeException error) {
                        throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
                    }
                }
            if (state.equals("READY")) {
                priorReady = true;
                require(
                        pass.path("startedAt").isNull()
                                && pass.path("finishedAt").isNull()
                                && pass.path("reason").isNull());
            }
            if (state.equals("RUNNING")) {
                running++;
                require(
                        !priorReady
                                && !pass.path("startedAt").isNull()
                                && pass.path("finishedAt").isNull()
                                && pass.path("reason").isNull());
            }
            if (state.equals("COMPLETED") || state.equals("INCOMPLETE"))
                require(!pass.path("startedAt").isNull() && !pass.path("finishedAt").isNull());
            if (state.equals("INCOMPLETE") || state.equals("SKIPPED"))
                require(!pass.path("reason").isNull());
        }
        require(running <= 1);
    }

    public static void transition(JsonNode before, JsonNode after, JsonNode plan) {
        var selected = new java.util.HashSet<String>();
        plan.path("selectedPasses").forEach(p -> selected.add(p.asText()));
        for (int i = 0; i < 6; i++) {
            var next = after.get(i);
            require(
                    next.path("selected").asBoolean()
                            == selected.contains(next.path("passId").asText()));
            if (before == null)
                require(
                        next.path("revision").asLong() == 1
                                && java.util.Set.of("READY", "SKIPPED")
                                        .contains(next.path("status").asText()));
            else {
                var old = before.get(i);
                if (old.equals(next)) continue;
                require(next.path("revision").asLong() == old.path("revision").asLong() + 1);
                var allowed =
                        old.path("status").asText().equals("READY")
                                ? java.util.Set.of("RUNNING", "SKIPPED")
                                : old.path("status").asText().equals("RUNNING")
                                        ? java.util.Set.of("RUNNING", "COMPLETED", "INCOMPLETE")
                                        : java.util.Set.<String>of();
                require(allowed.contains(next.path("status").asText()));
            }
        }
    }

    public static void matchesResult(JsonNode progress, JsonNode result) {
        require(progress != null && progress.size() == 6);
        for (int i = 0; i < 6; i++) {
            var fact = progress.get(i);
            var pass = result.path("passResults").get(i);
            require(
                    fact.path("passId").equals(pass.path("passId"))
                            && fact.path("selected").equals(pass.path("selected"))
                            && fact.path("status").equals(pass.path("status")));
            for (String field : new String[] {"modelCalls", "inputTokens", "outputTokens"})
                require(fact.path("usage").path(field).equals(pass.path("usage").path(field)));
            require(fact.path("usage").path("unknownCalls").asInt() == 0);
            if (pass.path("status").asText().equals("SKIPPED"))
                require(fact.path("reason").equals(pass.path("skipReason")));
        }
    }
}
