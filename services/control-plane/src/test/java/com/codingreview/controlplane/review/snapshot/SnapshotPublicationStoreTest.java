package com.codingreview.controlplane.review.snapshot;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.UUID;

class SnapshotPublicationStoreTest {
    @TempDir Path root;

    @Test
    void confirmsSaveThatThrowsAfterAtomicPublication() {
        var expected =
                new SnapshotPublication(
                        1,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "sha256:" + "a".repeat(64),
                        "2026-10-09T08:00:00.000000Z");
        var store =
                new SnapshotPublicationStore(
                        root,
                        (source, target) -> {
                            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                            throw new java.io.IOException("lost acknowledgement");
                        });
        store.save(expected);
        assertThat(store.find(expected.snapshotId())).contains(expected);
        store.save(expected);
        assertThatThrownBy(
                        () ->
                                store.save(
                                        new SnapshotPublication(
                                                1,
                                                expected.snapshotId(),
                                                expected.sessionId(),
                                                "sha256:" + "b".repeat(64),
                                                expected.createdAt())))
                .isInstanceOf(SnapshotException.class);
    }
}
