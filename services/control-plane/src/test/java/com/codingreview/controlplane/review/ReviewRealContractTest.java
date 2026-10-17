package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReviewRealContractTest {
    @Test
    void bothServicesReadTheSamePositiveAndNegativeWireFixtures() throws Exception {
        var fixtures = ReviewJson.parse(Files.readAllBytes(Path.of("../../contracts/fixtures/review-real-v1/examples.json")));
        fixtures.fields().forEachRemaining(entry -> {
            ReviewWireSchemas.validate(entry.getKey(), entry.getValue().get("positive"));
            assertThatThrownBy(() -> ReviewWireSchemas.validate(entry.getKey(), entry.getValue().get("negative")))
                    .isInstanceOf(ReviewException.class);
        });
    }
    @Test
    void sharedExecutionContractRefusesUnknownFieldsAndUnsupportedVersion() {
        ReviewWireSchemas.validate("review-execution-contract-v1", ReviewJson.parse(
                "{\"executionMode\":\"REAL\",\"resultVersion\":\"2.0\"}".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> ReviewWireSchemas.validate("review-execution-contract-v1", ReviewJson.parse(
                "{\"executionMode\":\"REAL\",\"resultVersion\":\"3.0\"}".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ReviewException.class);
        assertThatThrownBy(() -> ReviewWireSchemas.validate("review-execution-contract-v1", ReviewJson.parse(
                "{\"executionMode\":\"REAL\",\"resultVersion\":\"2.0\",\"key\":\"secret\"}".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ReviewException.class);
    }
}
