package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

class ReviewContractFixtureTest {
    @Test
    void sharedManifestUsesExactCanonicalUtf8BytesAndOriginalBinaryDigest() throws Exception {
        Path fixtures = Path.of("../../contracts/fixtures/review-snapshot-v1");
        Path snapshot = fixtures.resolve("22222222-2222-4222-8222-222222222222");
        byte[] raw = Files.readAllBytes(snapshot.resolve("manifest.json"));
        var manifest = ReviewJson.parse(raw);
        assertThat(ReviewJson.canonical(manifest)).containsExactly(raw);
        assertThat(ReviewJson.sha256(raw))
                .isEqualTo(Files.readString(fixtures.resolve("manifest-sha256.txt")).trim());
        assertThat(manifest.path("codingSessionId").asText())
                .isEqualTo("11111111-1111-4111-8111-111111111111");
        byte[] binary = Files.readAllBytes(snapshot.resolve("content/context.bin"));
        assertThat(binary).containsExactly((byte) 0, (byte) 255, (byte) 10);
        assertThat(manifest.path("entries").get(0).path("contentSha256").asText())
                .isEqualTo(ReviewJson.sha256(binary));
    }
}
