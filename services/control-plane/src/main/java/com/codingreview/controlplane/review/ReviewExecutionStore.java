package com.codingreview.controlplane.review;

import com.codingreview.controlplane.review.snapshot.ReviewPaths;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** 受管的产品快照、原始事件和原始 Result；每次未知写入都重读核实。 */
public class ReviewExecutionStore {
    @FunctionalInterface
    public interface Writer {
        void move(Path source, Path target) throws IOException;
    }

    private final Path root;
    private final Writer writer;
    private final ObjectMapper mapper =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build()
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_NULL_FOR_PRIMITIVES);

    public ReviewExecutionStore(Path runtimeRoot) {
        this(
                runtimeRoot,
                (a, b) ->
                        Files.move(
                                a,
                                b,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING));
    }

    public ReviewExecutionStore(Path runtimeRoot, Writer writer) {
        if (!runtimeRoot.isAbsolute())
            throw new IllegalArgumentException("Absolute runtime root required");
        root = runtimeRoot.normalize().resolve("review-executions");
        this.writer = writer;
    }

    private Path directory(UUID session, UUID run) {
        return root.resolve(session.toString()).resolve(run.toString());
    }

    public void save(ReviewExecution execution) {
        atomic(
                directory(execution.sessionId(), execution.runId()),
                "execution.json",
                ReviewJson.canonical(execution),
                false);
    }

    public Optional<ReviewExecution> find(UUID session, UUID run) {
        Path directory = directory(session, run);
        try {
            ReviewPaths.directory(directory);
            if (!Files.exists(directory.resolve("execution.json"), LinkOption.NOFOLLOW_LINKS))
                return Optional.empty();
            byte[] raw = read(directory, "execution.json", 256 * 1024);
            ReviewExecution execution =
                    mapper.treeToValue(ReviewJson.parse(raw), ReviewExecution.class);
            if (!execution.sessionId().equals(session) || !execution.runId().equals(run))
                throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
            return Optional.of(execution);
        } catch (IOException | RuntimeException e) {
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        }
    }

    public void scan(UUID session, Consumer<ReviewExecution> consumer) {
        Path directory = root.resolve(session.toString());
        try {
            ReviewPaths.directory(directory);
            if (!Files.exists(directory)) return;
            try (var children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    UUID id = UUID.fromString(child.getFileName().toString());
                    if (!child.getFileName().toString().equals(id.toString()))
                        throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
                    consumer.accept(
                            find(session, id)
                                    .orElseThrow(
                                            () -> new ReviewException("REVIEW_HISTORY_CORRUPTED")));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        }
    }

    public List<UUID> sessions() {
        try {
            ReviewPaths.directory(root);
            if (!Files.exists(root)) return List.of();
            List<UUID> ids = new ArrayList<>();
            try (var children = Files.newDirectoryStream(root)) {
                for (Path child : children) {
                    UUID id = UUID.fromString(child.getFileName().toString());
                    if (!child.getFileName().toString().equals(id.toString()))
                        throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
                    ids.add(id);
                }
            }
            return ids;
        } catch (IOException | IllegalArgumentException e) {
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        }
    }

    public List<ReviewExecution> list(UUID session, int limit) {
        if (limit < 1 || limit > 200)
            throw new IllegalArgumentException("Review limit must be 1..200");
        Comparator<ReviewExecution> order =
                Comparator.comparing(ReviewExecution::createdAt)
                        .thenComparing(ReviewExecution::runId);
        PriorityQueue<ReviewExecution> latest = new PriorityQueue<>(order);
        scan(
                session,
                value -> {
                    latest.add(value);
                    if (latest.size() > limit) latest.remove();
                });
        return latest.stream().sorted(order.reversed()).toList();
    }

    public void saveEvent(ReviewExecution execution, long sequence, byte[] raw) {
        if (sequence < 1 || raw.length > 65536)
            throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
        atomic(
                directory(execution.sessionId(), execution.runId()),
                "event-" + sequence + ".json",
                raw,
                true);
    }

    public byte[] event(ReviewExecution execution, long sequence) {
        return read(
                directory(execution.sessionId(), execution.runId()),
                "event-" + sequence + ".json",
                65536);
    }

    public void saveResult(ReviewExecution execution, byte[] raw) {
        if (raw.length > 1024 * 1024) throw new ReviewException("REVIEW_RESULT_INTEGRITY_ERROR");
        atomic(directory(execution.sessionId(), execution.runId()), "result.json", raw, true);
    }

    public byte[] result(ReviewExecution execution) {
        return read(
                directory(execution.sessionId(), execution.runId()), "result.json", 1024 * 1024);
    }

    private byte[] read(Path directory, String name, int limit) {
        try {
            Path path = ReviewPaths.file(directory, name, false);
            if (Files.size(path) > limit) throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] value = input.readNBytes(limit + 1);
                if (value.length > limit) throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
                return value;
            }
        } catch (IOException | RuntimeException e) {
            throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
        }
    }

    private void atomic(Path directory, String name, byte[] raw, boolean immutable) {
        Path temporary = directory.resolve("write-" + UUID.randomUUID() + ".tmp");
        try {
            ReviewPaths.directory(directory);
            Files.createDirectories(directory);
            Path target = directory.resolve(name);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                ReviewPaths.file(directory, name, false);
                if (immutable) {
                    if (!Arrays.equals(
                            read(directory, name, Math.max(256 * 1024, raw.length)), raw))
                        throw new ReviewException(
                                name.equals("result.json")
                                        ? "REVIEW_RESULT_INTEGRITY_ERROR"
                                        : "REVIEW_EVENT_INTEGRITY_ERROR");
                    return;
                }
            }
            try (var channel =
                    FileChannel.open(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(raw);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            writer.move(temporary, target);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ReviewException problem
                    && Set.of("REVIEW_EVENT_INTEGRITY_ERROR", "REVIEW_RESULT_INTEGRITY_ERROR")
                            .contains(problem.code())) throw problem;
            boolean confirmed = false;
            try {
                confirmed =
                        Arrays.equals(read(directory, name, Math.max(256 * 1024, raw.length)), raw);
            } catch (RuntimeException ignored) {
            }
            if (!confirmed) throw new ReviewException("REVIEW_STATE_WRITE_UNCERTAIN");
        } finally {
            try {
                if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    ReviewPaths.file(directory, temporary.getFileName().toString(), false);
                    Files.delete(temporary);
                }
            } catch (IOException | RuntimeException ignored) {
                /* Keep bounded residue rather than delete an unverified path. */
            }
        }
    }
}
