package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewFeeContractTest {
    private ObjectNode example(String name) throws Exception {
        return (ObjectNode) ReviewJson.parse(Files.readAllBytes(
                Path.of("../../contracts/fixtures/review-fee-v1/examples.json")))
                .path(name).path("positive").deepCopy();
    }

    @Test
    void twoServicesShareNewFeeContractsButDoNotRelaxHistoricalCallSchema() throws Exception {
        var data = ReviewJson.parse(Files.readAllBytes(Path.of("../../contracts/fixtures/review-fee-v1/examples.json")));
        data.fields().forEachRemaining(entry -> {
            ReviewWireSchemas.validate(entry.getKey(), entry.getValue().path("positive"));
            assertThatThrownBy(() -> ReviewWireSchemas.validate(entry.getKey(), entry.getValue().path("negative")))
                    .isInstanceOf(ReviewException.class);
        });
        assertThatThrownBy(() -> ReviewWireSchemas.validate("review-model-call-v1", example("review-model-call-v2")))
                .isInstanceOf(ReviewException.class);
    }

    @Test
    void profileDigestAndValidityAreCheckedBeforeUserConfirmation() throws Exception {
        var value = example("review-price-profile-view-v1");
        ReviewPriceProfile.validate(value, Instant.parse("2026-10-13T12:00:00Z"));
        assertThatThrownBy(() -> ReviewPriceProfile.validate(value, Instant.parse("2099-01-01T00:00:00Z")))
                .extracting("code").isEqualTo("PRICE_PROFILE_EXPIRED");
        ((ObjectNode) value.path("priceProfile")).put("inputCnyPerMillion", "3");
        assertThatThrownBy(() -> ReviewPriceProfile.validate(value, Instant.parse("2026-10-13T12:00:00Z")))
                .extracting("code").isEqualTo("PRICE_PROFILE_MISMATCH");
    }
}
