package com.codingreview.controlplane.review;

import com.codingreview.controlplane.review.snapshot.ReviewPaths;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Shared structural schema plus deterministic cross-reference and display checks. */
public final class ReviewResultV2 {
    private ReviewResultV2() {}

    private static void require(boolean value) {
        if (!value) throw new ReviewException("REVIEW_RESULT_INTEGRITY_ERROR");
    }

    private static Set<String> ids(JsonNode values, String field) {
        Set<String> result = new HashSet<>();
        for (var value : values) require(result.add(field == null ? value.asText() : value.path(field).asText()));
        return result;
    }

    public static void validate(JsonNode value) {
        ReviewWireSchemas.validate("review-harness-result-v2", value);
        var pass = value.path("passResults").get(0);
        var diagnostics = value.path("diagnostics");
        Set<String> calls = ids(diagnostics.path("modelCallIds"), null);
        ids(diagnostics.path("contextManifestArtifactIds"), null);
        require(calls.size() == pass.path("usage").path("modelCalls").asInt());
        require(ids(pass.path("findingIds"), null).equals(ids(value.path("findings"), "findingId")));
        ids(value.path("findings"), "candidateId");
        for (var path : pass.path("checkedScope")) ReviewPaths.portable(path.asText());
        if (pass.path("status").asText().equals("COMPLETED"))
            require(pass.path("coverage").asText().equals("FULL")
                    && pass.path("incompleteReasons").isEmpty()
                    && pass.path("unvalidatedCandidates").asInt() == 0);
        else require(!pass.path("incompleteReasons").isEmpty());

        Map<String, JsonNode> evidence = new HashMap<>();
        for (var item : value.path("evidence")) {
            require(evidence.put(item.path("evidenceId").asText(), item) == null);
            require(item.path("runId").equals(value.path("runId"))
                    && item.path("snapshotId").equals(value.path("snapshotId")));
            require(calls.contains(item.path("producerId").asText()));
            try {
                require(!Instant.parse(item.path("capturedAt").asText())
                        .isAfter(Instant.parse(value.path("generatedAt").asText())));
            } catch (RuntimeException error) { throw new ReviewException("REVIEW_RESULT_INTEGRITY_ERROR"); }
            boolean code = item.path("evidenceType").asText().equals("CODE");
            for (var ref : item.path("sourceRefs")) {
                String type = ref.path("sourceType").asText();
                require(code ? Set.of("CODE", "ARTIFACT").contains(type) : type.equals("USER_INPUT"));
                source(ref);
            }
        }
        for (var finding : value.path("findings")) {
            Set<String> checked = ids(pass.path("checkedScope"), null);
            require(finding.path("codeReferences").isEmpty()
                    || java.util.stream.StreamSupport.stream(finding.path("codeReferences").spliterator(), false)
                            .anyMatch(ref -> checked.contains(ref.path("path").asText())));
            require(calls.contains(finding.path("validationModelCallId").asText()));
            Set<JsonNode> references = new HashSet<>();
            boolean code = false;
            for (var id : finding.path("evidenceIds")) {
                var item = evidence.get(id.asText());
                require(item != null);
                code |= item.path("evidenceType").asText().equals("CODE");
                item.path("sourceRefs").forEach(references::add);
            }
            for (var ref : finding.path("codeReferences")) {
                source(ref);
                require(references.contains(ref));
            }
            var validation = finding.path("validationSummary");
            require(!validation.path("counterexampleHolds").asBoolean());
            if (finding.path("disposition").asText().equals("VERIFIED"))
                require(code && !finding.path("codeReferences").isEmpty()
                        && finding.path("claimKind").asText().equals("STATIC_CORRECTNESS")
                        && validation.path("assumptionsResolved").asBoolean());
        }
        String markdown = render(pass, value.path("findings"));
        int size = markdown.getBytes(StandardCharsets.UTF_8).length;
        require(size <= 65536 && size == value.path("reportDraft").path("byteSize").asInt()
                && markdown.equals(value.path("reportDraft").path("markdown").asText()));
    }

    private static void source(JsonNode ref) {
        if (ref.has("path")) ReviewPaths.portable(ref.path("path").asText());
        if (ref.has("startLine")) require(ref.path("endLine").asLong() >= ref.path("startLine").asLong());
    }

    private static String display(String text) {
        text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#x27;");
        for (char character : "\\`*_[]()#!>|".toCharArray())
            text = text.replace(String.valueOf(character), "\\" + character);
        return text;
    }

    private static String render(JsonNode pass, JsonNode findings) {
        StringBuilder text = new StringBuilder("# Correctness review draft\n\n");
        text.append("Status: ").append(pass.path("status").asText()).append("; scope: ")
                .append(pass.path("scopeKind").asText("FINAL_FILES")).append("; coverage: ")
                .append(pass.path("coverage").asText()).append(".\n\n");
        for (var path : pass.path("checkedScope")) text.append("- ").append(display(path.asText())).append('\n');
        text.append("\n## Limitations\n\n");
        for (String field : List.of("limitations", "incompleteReasons"))
            for (var item : pass.path(field)) text.append("- ").append(display(item.asText())).append('\n');
        if (findings.isEmpty()) text.append("\nNo validated finding is delivered for this finite scope. This is not a whole-project correctness guarantee.\n");
        for (var finding : findings) {
            text.append("\n## ").append(display(finding.path("title").asText())).append("\n\n")
                    .append(finding.path("disposition").asText()).append(" / ")
                    .append(finding.path("severity").asText()).append(".\n\n")
                    .append(display(finding.path("claim").asText())).append("\n\n")
                    .append(display(finding.path("reasoningSummary").path("trigger").asText())).append("\n\n")
                    .append(display(finding.path("validationSummary").path("basis").asText())).append("\n\n")
                    .append(display(finding.path("suggestion").asText())).append('\n');
            for (var ref : finding.path("codeReferences")) text.append("\n- ").append(display(ref.path("path").asText()))
                    .append(" (").append(ref.path("side").asText()).append(", lines ")
                    .append(ref.path("startLine").asLong()).append('-').append(ref.path("endLine").asLong()).append(")\n");
        }
        return text.toString();
    }
}
