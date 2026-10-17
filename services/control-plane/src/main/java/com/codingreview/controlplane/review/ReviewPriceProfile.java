package com.codingreview.controlplane.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** The browser sees the same operator-verified snapshot the user authorizes. */
public final class ReviewPriceProfile {
    private ReviewPriceProfile() {}

    public static void validate(JsonNode value, Instant at) {
        ReviewWireSchemas.validate("review-price-profile-view-v1", value);
        var profile = value.path("priceProfile");
        if (!ReviewJson.sha256(ReviewJson.canonical(profile)).equals(value.path("digest").asText()))
            throw new ReviewException("PRICE_PROFILE_MISMATCH");
        try {
            if (at.isBefore(Instant.parse(profile.path("verifiedAt").asText()))
                    || !at.isBefore(Instant.parse(profile.path("expiresAt").asText())))
                throw new ReviewException("PRICE_PROFILE_EXPIRED");
        } catch (java.time.format.DateTimeParseException error) {
            throw new ReviewException("PRICE_PROFILE_EXPIRED");
        }
    }
}
