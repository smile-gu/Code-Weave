package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewMultipassContractTest {
    private ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ReviewJson.parse(Files.readAllBytes(
                Path.of("../../contracts/fixtures/review-multipass-v1/examples.json")))
                .path(name).path("positive").deepCopy();
    }

    @Test
    void sharedNewSchemasRejectUnknownFieldsWithoutChangingOldReaders() throws Exception {
        var fixtures = ReviewJson.parse(Files.readAllBytes(
                Path.of("../../contracts/fixtures/review-multipass-v1/examples.json")));
        fixtures.fields().forEachRemaining(entry -> {
            ReviewWireSchemas.validate(entry.getKey(), entry.getValue().path("positive"));
            assertThatThrownBy(() -> ReviewWireSchemas.validate(entry.getKey(), entry.getValue().path("negative")))
                    .isInstanceOf(ReviewException.class);
        });
        assertThatThrownBy(() -> ReviewResultV2.validate(fixture("review-harness-result-v3")))
                .isInstanceOf(ReviewException.class);
    }

    @Test
    void javaMakesDefaultSelectionExplicitAndCanonical() throws Exception {
        var fee = fixture("review-plan-v1").path("feeAuthorization");
        assertThat(ReviewPlan.create(null, fee).path("selectedPasses").size()).isEqualTo(6);
        var selected = ReviewPlan.create(List.of("testing", "correctness"), fee);
        assertThat(selected.path("selectedPasses").get(0).asText()).isEqualTo("correctness");
        assertThatThrownBy(() -> ReviewPlan.create(List.of(), fee)).isInstanceOf(ReviewException.class);
        assertThatThrownBy(() -> ReviewPlan.create(List.of("security", "security"), fee)).isInstanceOf(ReviewException.class);
        assertThatThrownBy(() -> ReviewPlan.create(List.of("unknown"), fee)).isInstanceOf(ReviewException.class);
    }

    @Test
    void capabilitiesFailClosedUntilPlanAndFeeRuntimeAreReady() throws Exception {
        var capabilities = fixture("review-capabilities-v2");
        assertThatThrownBy(() -> ReviewPlan.requireCompatibility(capabilities, "REAL"))
                .isInstanceOf(ReviewException.class);
        capabilities.put("status", "READY");
        capabilities.put("feeReservation", "READY");
        capabilities.putArray("failureCodes");
        ReviewPlan.requireCompatibility(capabilities, "REAL");
        assertThatThrownBy(() -> ReviewPlan.requireCompatibility(capabilities, "SIMULATED"))
                .isInstanceOf(ReviewException.class);
    }

    @Test
    void productionClientRequestsNewVersionAndDoesNotSubmitWhenUnavailable() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var unavailable = fixture("review-capabilities-v2");
        server.createContext("/internal/v1/review-capabilities", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("protocolVersion=1.1");
            byte[] bytes = ReviewJson.canonical(unavailable);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var client = new ReviewHarnessClient(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "test-only", java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(2), "REAL")) {
            assertThatThrownBy(client::requireMultipassCompatibility).extracting("code").isEqualTo("REVIEW_HARNESS_NOT_READY");
            assertThat(requests.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test
    void result3AllowsEmptySkippedDimensionsButChecksSelectionAndRenderedFacts() throws Exception {
        var result = fixture("review-harness-result-v3");
        ReviewResultV3.validate(result);
        ((ObjectNode) result.path("passResults").get(1)).put("selected", true);
        assertThatThrownBy(() -> ReviewResultV3.validate(result)).isInstanceOf(ReviewException.class);
        var forged = fixture("review-harness-result-v3");
        ((ObjectNode) forged.path("reportDraft")).put("markdown", "invented report");
        assertThatThrownBy(() -> ReviewResultV3.validate(forged)).isInstanceOf(ReviewException.class);
    }
}
