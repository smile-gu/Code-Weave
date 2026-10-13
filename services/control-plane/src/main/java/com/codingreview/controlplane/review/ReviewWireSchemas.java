package com.codingreview.controlplane.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 两端共用冻结 Schema；仅装载打包资源，不从 Schema ID 发起网络请求。 */
public final class ReviewWireSchemas {
    private static final Set<String> NAMES = Set.of(
            "review-execution-contract-v1", "review-capabilities-v1",
            "review-provider-request-v1", "review-provider-response-v1",
            "review-provider-call-mapping-v1", "review-model-call-v1",
            "review-harness-result-v2", "review-graph-state-v2",
            "review-plan-v1", "review-pass-result-v3", "review-execution-contract-v2",
            "review-capabilities-v2", "review-harness-create-run-v1.1", "review-harness-result-v3");
    private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

    private ReviewWireSchemas() {}

    public static void validate(String name, JsonNode value) {
        if (!NAMES.contains(name) || !CACHE.computeIfAbsent(name, ReviewWireSchemas::load).validate(value).isEmpty())
            throw new ReviewException("REVIEW_CONTRACT_INVALID");
    }

    private static JsonSchema load(String name) {
        try (var input = ReviewWireSchemas.class.getResourceAsStream("/review-schemas/" + name + ".schema.json")) {
            if (input == null) throw new ReviewException("REVIEW_CONTRACT_UNAVAILABLE");
            // Batch 5 schemas are self-contained: all references resolve within $defs.
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(ReviewJson.parse(input.readAllBytes()));
        } catch (IOException error) {
            throw new ReviewException("REVIEW_CONTRACT_UNAVAILABLE");
        }
    }
}
