package com.codingreview.controlplane.review;

import com.codingreview.controlplane.review.snapshot.ReviewPaths;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Result 3 has its own gates; it never relaxes the historical Result 2 reader. */
public final class ReviewResultV3 {
    private ReviewResultV3() {}

    private static void require(boolean condition) {
        if (!condition) throw new ReviewException("REVIEW_RESULT_INTEGRITY_ERROR");
    }

    private static Set<String> ids(JsonNode values, String field) {
        Set<String> result = new HashSet<>();
        for (var value : values) require(result.add(field == null ? value.asText() : value.path(field).asText()));
        return result;
    }

    private static void usage(JsonNode value) {
        if (value.path("modelCalls").asInt() == 0)
            require(value.path("inputTokens").asLong() == 0 && value.path("outputTokens").asLong() == 0);
    }

    private static void pass(JsonNode value) {
        ids(value.path("checkedScope"), null);
        ids(value.path("findingIds"), null);
        value.path("checkedScope").forEach(p -> ReviewPaths.portable(p.asText()));
        usage(value.path("usage"));
        require(!value.path("checkedScope").isEmpty() || !value.path("coverage").asText().equals("FULL"));
        String status = value.path("status").asText();
        if (status.equals("SKIPPED")) {
            require(value.path("skipReason").asText().equals(value.path("selected").asBoolean()
                    ? "RUN_BUDGET_EXHAUSTED" : "USER_NOT_SELECTED"));
            require(value.path("checkedScope").isEmpty() && value.path("findingIds").isEmpty()
                    && value.path("unvalidatedCandidates").asInt() == 0
                    && value.path("usage").path("modelCalls").asInt() == 0
                    && !value.path("coverage").asText().equals("FULL")
                    && value.path("incompleteReasons").isEmpty());
        } else require(value.path("selected").asBoolean() && value.path("skipReason").isNull());
        if (status.equals("COMPLETED"))
            require(!value.path("checkedScope").isEmpty() && value.path("coverage").asText().equals("FULL")
                    && value.path("incompleteReasons").isEmpty() && value.path("unvalidatedCandidates").asInt() == 0);
        if (status.equals("INCOMPLETE")) require(!value.path("incompleteReasons").isEmpty());
    }

    public static void validate(JsonNode value) {
        ReviewWireSchemas.validate("review-harness-result-v3", value);
        require(ReviewJson.canonical(value).length <= 1024 * 1024);
        ReviewPlan.validate(value.path("reviewPlan"));
        var diagnostics = value.path("diagnostics");
        var fee = diagnostics.path("feeSummary");
        var auth = value.path("reviewPlan").path("feeAuthorization");
        require(fee.path("authorizationId").equals(auth.path("authorizationId")));
        require(new BigDecimal(auth.path("maxAmount").asText()).movePointRight(6).longValueExact()
                == fee.path("maxAmountMicros").asLong());
        BigDecimal spentAndReserved = BigDecimal.valueOf(fee.path("spentMicros").asLong())
                .add(BigDecimal.valueOf(fee.path("reservedMicros").asLong()));
        require(spentAndReserved.subtract(BigDecimal.valueOf(fee.path("maxAmountMicros").asLong()))
                .max(BigDecimal.ZERO).compareTo(BigDecimal.valueOf(fee.path("overrunMicros").asLong())) <= 0);
        require(fee.path("overrunMicros").asLong() <= fee.path("spentMicros").asLong());
        if (fee.path("unknownCalls").asInt() > 0) require(fee.path("reservedMicros").asLong() > 0);
        Set<String> calls = ids(diagnostics.path("modelCallIds"), null);
        ids(diagnostics.path("contextManifestArtifactIds"), null);
        require(fee.path("unknownCalls").asInt() <= calls.size());
        boolean real = diagnostics.path("executionMode").asText().equals("REAL");
        if (real) require(!diagnostics.path("meterVersion").isNull());
        else require(calls.isEmpty() && fee.path("spentMicros").asLong() == 0 && fee.path("reservedMicros").asLong() == 0);

        Map<String, JsonNode> passes = new LinkedHashMap<>();
        Set<String> selected = ids(value.path("reviewPlan").path("selectedPasses"), null);
        int callCount = diagnostics.path("dedupUsage").path("modelCalls").asInt();
        require(callCount <= 10);
        usage(diagnostics.path("dedupUsage"));
        boolean complete = true;
        for (int index = 0; index < 6; index++) {
            var item = value.path("passResults").get(index);
            String id = item.path("passId").asText();
            require(id.equals(ReviewPlan.PASS_ORDER.get(index)));
            pass(item);
            require(item.path("selected").asBoolean() == selected.contains(id));
            if (selected.contains(id)) complete &= item.path("status").asText().equals("COMPLETED");
            callCount += item.path("usage").path("modelCalls").asInt();
            passes.put(id, item);
        }
        require(complete == value.path("coverageStatus").asText().equals("SELECTED_COMPLETE"));
        require(callCount == calls.size() && callCount <= 2000);
        Instant generated;
        try {
            generated = Instant.parse(value.path("generatedAt").asText());
            require(!generated.isBefore(Instant.parse(auth.path("confirmedAt").asText())));
        } catch (RuntimeException error) { throw new ReviewException("REVIEW_RESULT_INTEGRITY_ERROR"); }
        Map<String, JsonNode> evidence = new HashMap<>();
        for (var item : value.path("evidence")) {
            require(evidence.put(item.path("evidenceId").asText(), item) == null);
            require(item.path("runId").equals(value.path("runId")) && item.path("snapshotId").equals(value.path("snapshotId")));
            require(calls.contains(item.path("producerId").asText()));
            try { require(!Instant.parse(item.path("capturedAt").asText()).isAfter(generated)); }
            catch (RuntimeException error) { throw new ReviewException("REVIEW_RESULT_INTEGRITY_ERROR"); }
            boolean code = item.path("evidenceType").asText().equals("CODE");
            for (var ref : item.path("sourceRefs")) {
                require(code ? Set.of("CODE", "ARTIFACT").contains(ref.path("sourceType").asText())
                        : ref.path("sourceType").asText().equals("USER_INPUT"));
                ReviewResultV2.source(ref);
            }
        }
        ids(value.path("findings"), "findingId");
        Set<String> allCandidates = new HashSet<>();
        Map<String, Set<String>> passFindingIds = new HashMap<>();
        ReviewPlan.PASS_ORDER.forEach(p -> passFindingIds.put(p, new HashSet<>()));
        for (var finding : value.path("findings")) {
            validateFinding(finding, evidence, calls, passes, allCandidates, passFindingIds);
        }
        int unvalidated = 0;
        for (var pass : passes.values()) unvalidated += pass.path("unvalidatedCandidates").asInt();
        require(allCandidates.size() + unvalidated <= 32);
        for (var entry : passes.entrySet())
            require(ids(entry.getValue().path("findingIds"), null).equals(passFindingIds.get(entry.getKey())));
        String markdown = render(value);
        require(markdown.getBytes(StandardCharsets.UTF_8).length <= 65536
                && markdown.getBytes(StandardCharsets.UTF_8).length == value.path("reportDraft").path("byteSize").asInt()
                && markdown.equals(value.path("reportDraft").path("markdown").asText()));
    }

    private static void validateFinding(JsonNode finding, Map<String, JsonNode> evidence, Set<String> calls,
            Map<String, JsonNode> passes, Set<String> allCandidates, Map<String, Set<String>> passFindingIds) {
        JsonNode primary = null;
        Set<String> evidenceIds = new HashSet<>();
        Set<JsonNode> codeRefs = new HashSet<>();
        for (var contribution : finding.path("contributions")) {
            require(allCandidates.add(contribution.path("candidateId").asText()));
            String passId = contribution.path("passId").asText();
            var pass = passes.get(passId);
            require(pass.path("selected").asBoolean() && !pass.path("status").asText().equals("SKIPPED"));
            passFindingIds.get(passId).add(finding.path("findingId").asText());
            require(calls.contains(contribution.path("validationModelCallId").asText()));
            if (primary == null || comparePrimary(contribution, primary) < 0) primary = contribution;
            Set<String> ownEvidence = ids(contribution.path("evidenceIds"), null);
            evidenceIds.addAll(ownEvidence);
            Set<JsonNode> authorized = new HashSet<>();
            boolean code = false;
            for (String id : ownEvidence) {
                var item = evidence.get(id);
                require(item != null);
                code |= item.path("evidenceType").asText().equals("CODE");
                item.path("sourceRefs").forEach(authorized::add);
            }
            Set<String> checked = ids(pass.path("checkedScope"), null);
            boolean anchor = false;
            for (var ref : contribution.path("codeReferences")) {
                ReviewResultV2.source(ref);
                require(authorized.contains(ref));
                anchor |= checked.contains(ref.path("path").asText());
                codeRefs.add(ref);
            }
            require(contribution.path("codeReferences").isEmpty() || anchor);
            if (contribution.path("disposition").asText().equals("VERIFIED"))
                require(code && !contribution.path("codeReferences").isEmpty());
        }
        for (String field : List.of("candidateId", "passId", "disposition", "validationModelCallId"))
            require(finding.path(field).equals(primary.path(field)));
        require(ids(finding.path("evidenceIds"), null).equals(evidenceIds));
        Set<JsonNode> findingRefs = new HashSet<>();
        finding.path("codeReferences").forEach(findingRefs::add);
        require(findingRefs.equals(codeRefs));
        var validation = finding.path("validationSummary");
        require(!validation.path("counterexampleHolds").asBoolean());
        if (finding.path("disposition").asText().equals("VERIFIED"))
            require(finding.path("claimKind").asText().equals("STATIC_CORRECTNESS")
                    && validation.path("assumptionsResolved").asBoolean() && !findingRefs.isEmpty());
    }

    private static int comparePrimary(JsonNode left, JsonNode right) {
        int order = Integer.compare(ReviewPlan.PASS_ORDER.indexOf(left.path("passId").asText()),
                ReviewPlan.PASS_ORDER.indexOf(right.path("passId").asText()));
        return order != 0 ? order : left.path("candidateId").asText().compareTo(right.path("candidateId").asText());
    }

    private static String render(JsonNode value) {
        StringBuilder text = new StringBuilder("# Multi-pass review draft\n\nCoverage: ")
                .append(value.path("coverageStatus").asText()).append(".\n\n");
        for (var pass : value.path("passResults")) {
            text.append("Selected: ").append(pass.path("selected").asBoolean()).append("; skipReason: ")
                    .append(pass.path("skipReason").isNull() ? "NONE" : pass.path("skipReason").asText()).append(".\n\n");
            var findings = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            for (var finding : value.path("findings"))
                if (finding.path("passId").equals(pass.path("passId"))) findings.add(finding);
            text.append(ReviewResultV2.render(pass, findings).replaceFirst("# Correctness review draft", "## " + pass.path("passId").asText()));
        }
        return text.toString();
    }
}
