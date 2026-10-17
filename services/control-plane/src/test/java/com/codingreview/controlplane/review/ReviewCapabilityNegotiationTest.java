package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewCapabilityNegotiationTest {
    @Test
    void realClientRefusesSimulatedHarnessBeforeSubmittingAnything() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/review-capabilities", exchange -> {
            byte[] body = ReviewJson.canonical(Map.of(
                    "protocolVersion", "1.0", "configuredContract", Map.of("executionMode", "SIMULATED", "resultVersion", "1.0"),
                    "status", "READY", "provider", "DEEPSEEK", "model", "deepseek-flash", "meterVersion", "v4.1-2cba9e42-v1", "failureCodes", java.util.List.of()));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var client = new ReviewHarnessClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "test-only", Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), "REAL")) {
            assertThatThrownBy(client::requireExecutionCompatibility).extracting("code").isEqualTo("EXECUTION_CONTRACT_UNSUPPORTED");
        } finally { server.stop(0); }
    }
}
