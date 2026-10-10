package com.codingreview.controlplane.review.snapshot;

import com.codingreview.controlplane.review.ReviewJson;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 只读 Git 观测：NUL 字节、明确输出预算、无仓库提供的外部程序。 */
final class SnapshotGit {
    private final Path repository;
    private final long deadline;

    SnapshotGit(Path repository, long deadline) {
        this.repository = repository;
        this.deadline = deadline;
    }

    byte[] run(String... args) {
        Process process = null;
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            var builder =
                    com.codingreview.controlplane.workspace.git.ReadOnlyGit.process(
                            "git", repository, List.of(args));
            process = builder.start();
            Process running = process;
            var stdout = io.submit(() -> bounded(running.getInputStream()));
            var stderr = io.submit(() -> bounded(running.getErrorStream()));
            try {
                if (!process.waitFor(remaining(), TimeUnit.NANOSECONDS))
                    throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED");
                byte[] bytes = stdout.get(remaining(), TimeUnit.NANOSECONDS);
                stderr.get(remaining(), TimeUnit.NANOSECONDS);
                if (process.exitValue() != 0)
                    throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED");
                return bytes;
            } finally {
                process.destroyForcibly();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        } catch (IOException | ExecutionException e) {
            throw new SnapshotException("SNAPSHOT_SOURCE_UNREADABLE");
        } catch (TimeoutException e) {
            throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED");
        }
    }

    private long remaining() {
        long value = deadline - System.nanoTime();
        if (value <= 0) throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED");
        return value;
    }

    private static byte[] bounded(InputStream stream) throws IOException {
        try (stream) {
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (out.size() + read > 16 * 1024 * 1024)
                    throw new IOException("Git metadata limit");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    String text(String... args) {
        return ReviewJson.utf8(run(args)).trim();
    }

    List<String> fields(String... args) {
        String value = ReviewJson.utf8(run(args));
        if (!value.isEmpty() && !value.endsWith("\0"))
            throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED");
        return value.isEmpty()
                ? List.of()
                : List.of(value.substring(0, value.length() - 1).split("\0", -1));
    }

    Map<String, String> inventory(String baseline) throws IOException {
        if (!baseline.matches("([0-9a-f]{40}|[0-9a-f]{64})"))
            throw new SnapshotException("SNAPSHOT_IDENTITY_MISMATCH");
        for (String field : fields("ls-tree", "-r", "-z", baseline)) {
            int tab = field.indexOf('\t');
            String mode = field.substring(0, field.indexOf(' '));
            if ("160000".equals(mode)) throw new SnapshotException("SNAPSHOT_GITLINK_UNSUPPORTED");
            ReviewPaths.portable(field.substring(tab + 1));
        }
        if (!text("config", "--type=bool", "--default", "false", "--get", "core.sparseCheckout")
                .equals("false"))
            throw new SnapshotException("SNAPSHOT_SPARSE_CHECKOUT_UNSUPPORTED");
        if (!fields("ls-files", "--unmerged", "-z").isEmpty())
            throw new SnapshotException("SNAPSHOT_UNMERGED_INDEX");
        for (String name :
                List.of(
                        "MERGE_HEAD",
                        "CHERRY_PICK_HEAD",
                        "REVERT_HEAD",
                        "rebase-merge",
                        "rebase-apply",
                        "sequencer")) {
            Path path = Path.of(text("rev-parse", "--git-path", name));
            if (!path.isAbsolute()) path = repository.resolve(path);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                throw new SnapshotException("SNAPSHOT_REPOSITORY_OPERATION_IN_PROGRESS");
        }
        Map<String, String> files = new TreeMap<>(ReviewJson.CODE_POINTS);
        for (String field : fields("ls-files", "--stage", "-z")) {
            int tab = field.indexOf('\t');
            String mode = field.substring(0, field.indexOf(' '));
            String path = ReviewPaths.portable(field.substring(tab + 1));
            if ("160000".equals(mode)) throw new SnapshotException("SNAPSHOT_GITLINK_UNSUPPORTED");
            Path current = repository.resolve(path);
            if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                // The old indexed file may now be a directory. Its deletion is
                // history; only the directory's current files belong in entries.
                ReviewPaths.directory(current);
                continue;
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) files.put(path, mode);
        }
        for (String path : fields("ls-files", "--others", "--exclude-standard", "-z"))
            files.put(ReviewPaths.portable(path), null);
        ReviewPaths.distinct(files.keySet());
        return files;
    }
}
