package com.codingreview.controlplane.review.snapshot;

import com.codingreview.controlplane.review.ReviewJson;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 不可变 Snapshot 产品身份；保存异常后核对完整字节，不删除已发布目录。 */
public final class SnapshotPublicationStore {
    private final Path root;
    private final SnapshotCapture.Publisher publisher;
    private final ObjectMapper mapper = new ObjectMapper();

    public SnapshotPublicationStore(Path runtimeRoot) {
        this(
                runtimeRoot,
                (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    public SnapshotPublicationStore(Path runtimeRoot, SnapshotCapture.Publisher publisher) {
        if (!runtimeRoot.isAbsolute())
            throw new IllegalArgumentException("Absolute runtime root required");
        root = runtimeRoot.normalize().resolve("snapshot-records");
        this.publisher = publisher;
    }

    public synchronized void save(SnapshotPublication value) {
        validate(value);
        byte[] bytes = ReviewJson.canonical(value);
        Path target = root.resolve(value.snapshotId() + ".json");
        Path temp = root.resolve(value.snapshotId() + "-" + UUID.randomUUID() + ".tmp");
        try {
            ReviewPaths.directory(root);
            Files.createDirectories(root);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (find(value.snapshotId()).filter(value::equals).isPresent()) return;
                throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED");
            }
            try (var file =
                    FileChannel.open(
                            temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            publisher.move(temp, target);
        } catch (IOException | RuntimeException error) {
            boolean confirmed = false;
            try {
                confirmed = find(value.snapshotId()).filter(value::equals).isPresent();
            } catch (RuntimeException ignored) {
                /* Preserve the original publication and protection. */
            }
            if (!confirmed) throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED", false);
        } finally {
            try {
                if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
                    ReviewPaths.file(root, temp.getFileName().toString(), false);
                    Files.delete(temp);
                }
            } catch (IOException | RuntimeException ignored) {
                /* Bounded diagnostic residue, never a Snapshot deletion. */
            }
        }
    }

    public Optional<SnapshotPublication> find(UUID id) {
        Path target = root.resolve(id + ".json");
        try {
            ReviewPaths.directory(root);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            ReviewPaths.file(root, target.getFileName().toString(), false);
            if (Files.size(target) > 4096)
                throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED");
            var node = ReviewJson.parse(Files.readAllBytes(target));
            SnapshotPublication value = mapper.treeToValue(node, SnapshotPublication.class);
            validate(value);
            if (!value.snapshotId().equals(id))
                throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED");
            return Optional.of(value);
        } catch (IOException | IllegalArgumentException e) {
            throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED", false);
        }
    }

    private void validate(SnapshotPublication value) {
        if (value.schemaVersion() != 1
                || value.snapshotId() == null
                || value.sessionId() == null
                || !value.manifestSha256().matches("sha256:[0-9a-f]{64}")
                || !value.createdAt().matches(".*\\.\\d{6}Z"))
            throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED");
    }
}
