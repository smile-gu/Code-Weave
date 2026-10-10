package com.codingreview.controlplane.review.snapshot;

import com.codingreview.controlplane.review.ReviewJson;
import com.codingreview.controlplane.workspace.change.GitChangeCollectorImpl;
import com.codingreview.controlplane.workspace.change.WorkspaceFingerprintCalculator;
import com.codingreview.controlplane.workspace.change.model.*;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 两轮完整观测、字节级复制和不覆盖原子发布；调用方持有 Session 锁。 */
public final class SnapshotCapture {
    @FunctionalInterface
    public interface Publisher {
        void move(Path staging, Path target) throws IOException;
    }

    private final Path root;
    private final Path executionRoot;
    private final SnapshotLimits limits;
    private final Runnable afterCopy;
    private final Publisher publisher;
    private final Object publishLock = new Object();
    private static final Set<String> PREPARING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SnapshotCapture(Path root, Path executionRoot, SnapshotLimits limits) {
        this(
                root,
                executionRoot,
                limits,
                () -> {},
                (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    public SnapshotCapture(
            Path root,
            Path executionRoot,
            SnapshotLimits limits,
            Runnable afterCopy,
            Publisher publisher) {
        this.root = root.toAbsolutePath().normalize();
        this.executionRoot = executionRoot.toAbsolutePath().normalize();
        if (!root.isAbsolute() || !executionRoot.isAbsolute())
            throw new SnapshotException("SNAPSHOT_PATH_INVALID");
        ReviewPaths.separate(this.root, this.executionRoot);
        this.limits = Objects.requireNonNull(limits);
        this.afterCopy = afterCopy;
        this.publisher = publisher;
    }

    public ScopePreview preview(UUID sessionId, Path repository, String baseline) {
        try {
            Observation current = observe(repository, baseline, deadline());
            if (current.changes().isEmpty()) throw new SnapshotException("NO_REVIEW_CHANGES");
            return preview(sessionId, baseline, current);
        } catch (SnapshotException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new SnapshotException("SNAPSHOT_SOURCE_UNREADABLE");
        }
    }

    public SnapshotPublication capture(
            UUID id,
            UUID sessionId,
            Path repository,
            String trustedBaseline,
            ScopeConfirmation expected) {
        String lease = root + "/" + id;
        if (!PREPARING.add(lease)) throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED", false);
        long deadline = deadline();
        Path staging = root.resolve(".staging").resolve(id.toString());
        boolean ownedStaging = false;
        try {
            rootsOutside(repository);
            Observation first = observe(repository, trustedBaseline, deadline);
            ScopePreview preview = preview(sessionId, trustedBaseline, first);
            if (!preview.activeBaselineCommit().equals(expected.expectedActiveBaselineCommit())
                    || !preview.headCommit().equals(expected.expectedHeadCommit())
                    || !preview.branch().equals(expected.expectedBranch())
                    || !preview.workspaceFingerprint()
                            .equals(expected.expectedWorkspaceFingerprint()))
                throw new SnapshotException("REVIEW_SCOPE_PREVIEW_EXPIRED");
            if (first.changes().isEmpty()) throw new SnapshotException("NO_REVIEW_CHANGES");
            var targets = new TreeSet<String>(ReviewJson.CODE_POINTS);
            targets.addAll(expected.targetPaths());
            if (targets.isEmpty() || !preview.changedPaths().containsAll(targets))
                throw new SnapshotException("REVIEW_SCOPE_EMPTY");
            targets.forEach(ReviewPaths::portable);
            ReviewPaths.directory(staging.getParent());
            Files.createDirectories(staging.getParent());
            Files.createDirectory(staging);
            ownedStaging = true;
            write(
                    staging.resolve(".capture-owner.json"),
                    ReviewJson.canonical(
                            Map.of(
                                    "snapshotId",
                                    id.toString(),
                                    "sessionId",
                                    sessionId.toString())));
            for (Entry entry : first.entries()) {
                checkDeadline(deadline);
                Path destination = staging.resolve("content").resolve(entry.path());
                ReviewPaths.directory(destination.getParent());
                Files.createDirectories(destination.getParent());
                Entry copied =
                        readEntry(
                                repository,
                                entry.path(),
                                entry.type().equals("SYMLINK") ? "120000" : entry.gitMode(),
                                entry.tracked(),
                                deadline,
                                destination);
                if (!entry.equals(copied)) throw new SnapshotException("SNAPSHOT_SOURCE_DRIFTED");
            }
            Files.createDirectories(staging.resolve("content"));
            Files.createDirectories(staging.resolve("artifacts"));
            write(staging.resolve("artifacts/changes.diff"), first.changes().rawDiff());
            afterCopy.run();
            Observation second = observe(repository, trustedBaseline, deadline);
            if (!same(first, second)) throw new SnapshotException("SNAPSHOT_SOURCE_DRIFTED");
            // Validate the written copy too, so source and destination cannot silently diverge.
            for (Entry entry : first.entries()) {
                Path copied = ReviewPaths.file(staging.resolve("content"), entry.path(), false);
                if (!hashFile(copied, deadline, null)
                        .equals(new Content(entry.byteSize(), entry.contentSha256())))
                    throw new SnapshotException("SNAPSHOT_CONTENT_MISMATCH");
            }
            String at = ReviewJson.timestamp(Instant.now());
            var excluded = new TreeSet<>(ReviewJson.CODE_POINTS);
            excluded.addAll(preview.changedPaths());
            excluded.removeAll(targets);
            var manifest = new TreeMap<String, Object>(ReviewJson.CODE_POINTS);
            manifest.put("snapshotFormatVersion", "1.0");
            manifest.put("snapshotId", id.toString());
            manifest.put("codingSessionId", sessionId.toString());
            manifest.put("baseCommit", trustedBaseline);
            manifest.put("capturedHeadCommit", first.head());
            manifest.put("branch", first.branch());
            manifest.put("workspaceFingerprint", first.fingerprint());
            manifest.put("createdAt", at);
            manifest.put("targetPaths", targets);
            manifest.put("excludedChangedPaths", excluded);
            manifest.put("entries", first.entries());
            manifest.put(
                    "changes",
                    Map.of(
                            "files",
                            first.changes().files().stream()
                                    .sorted(
                                            Comparator.comparing(
                                                    GitChangedFile::path, ReviewJson.CODE_POINTS))
                                    .toList(),
                            "diff",
                            Map.of(
                                    "path",
                                    "artifacts/changes.diff",
                                    "byteSize",
                                    first.changes().rawDiff().length,
                                    "diffSha256",
                                    ReviewJson.sha256(first.changes().rawDiff()))));
            byte[] document = ReviewJson.canonical(manifest);
            if (document.length > limits.manifestBytes())
                throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED");
            write(staging.resolve("manifest.json"), document);
            synchronized (publishLock) {
                checkDeadline(deadline);
                ReviewPaths.directory(staging);
                ReviewPaths.directory(root);
                Path target = root.resolve(id.toString());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED");
                Files.delete(staging.resolve(".capture-owner.json"));
                publisher.move(staging, target);
            }
            ownedStaging = false;
            return new SnapshotPublication(1, id, sessionId, ReviewJson.sha256(document), at);
        } catch (AtomicMoveNotSupportedException e) {
            throw new SnapshotException("SNAPSHOT_ATOMIC_PUBLISH_UNSUPPORTED");
        } catch (SnapshotException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED");
        } finally {
            try {
                if (ownedStaging) {
                    try {
                        ReviewPaths.removeOwnedTree(root, staging);
                    } catch (IOException | RuntimeException e) {
                        throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED", false);
                    }
                }
            } finally {
                PREPARING.remove(lease);
            }
        }
    }

    /** 新进程只清理有可验证身份的 Staging；保留所有已发布 Snapshot。 */
    public void cleanupInterrupted(UUID snapshotId, UUID sessionId) {
        publishedIdentity(snapshotId, sessionId);
        if (PREPARING.contains(root + "/" + snapshotId))
            throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED", false);
        Path path = root.resolve(".staging").resolve(snapshotId.toString());
        try {
            ReviewPaths.directory(root);
            ReviewPaths.directory(path);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            boolean owned = false;
            Path marker = path.resolve(".capture-owner.json");
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                ReviewPaths.file(path, ".capture-owner.json", false);
                if (Files.size(marker) <= 4096)
                    owned =
                            ReviewJson.parse(Files.readAllBytes(marker))
                                    .equals(
                                            ReviewJson.parse(
                                                    ReviewJson.canonical(
                                                            Map.of(
                                                                    "snapshotId",
                                                                    snapshotId.toString(),
                                                                    "sessionId",
                                                                    sessionId.toString()))));
            } else if (Files.exists(path.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)) {
                ReviewPaths.file(path, "manifest.json", false);
                if (Files.size(path.resolve("manifest.json")) <= limits.manifestBytes()) {
                    var manifest =
                            ReviewJson.parse(Files.readAllBytes(path.resolve("manifest.json")));
                    owned =
                            manifest.path("snapshotId").asText().equals(snapshotId.toString())
                                    && manifest.path("codingSessionId")
                                            .asText()
                                            .equals(sessionId.toString());
                }
            }
            if (!owned) throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED", false);
            ReviewPaths.removeOwnedTree(root, path);
        } catch (IOException | RuntimeException e) {
            throw new SnapshotException("SNAPSHOT_CAPTURE_FAILED", false);
        }
    }

    /** Re-read the fixed published identity; recovery never captures new input. */
    public Optional<SnapshotPublication> publishedIdentity(UUID snapshotId, UUID sessionId) {
        Path published = root.resolve(snapshotId.toString());
        try {
            ReviewPaths.directory(root);
            if (!Files.exists(published, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            Path manifest = ReviewPaths.file(published, "manifest.json", false);
            if (Files.size(manifest) > limits.manifestBytes())
                throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED", false);
            byte[] raw;
            try (InputStream input = Files.newInputStream(manifest, LinkOption.NOFOLLOW_LINKS)) {
                raw = input.readNBytes(Math.toIntExact(limits.manifestBytes()) + 1);
            }
            if (raw.length > limits.manifestBytes())
                throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED", false);
            var value = ReviewJson.parse(raw);
            com.codingreview.controlplane.review.ReviewProtocol.fields(
                    value,
                    Set.of(
                            "snapshotFormatVersion",
                            "snapshotId",
                            "codingSessionId",
                            "baseCommit",
                            "capturedHeadCommit",
                            "branch",
                            "workspaceFingerprint",
                            "createdAt",
                            "targetPaths",
                            "excludedChangedPaths",
                            "entries",
                            "changes"),
                    Set.of(),
                    "REVIEW_HISTORY_CORRUPTED");
            String createdAt = value.path("createdAt").asText();
            if (!value.path("snapshotFormatVersion").asText().equals("1.0")
                    || !value.path("snapshotId").asText().equals(snapshotId.toString())
                    || !value.path("codingSessionId").asText().equals(sessionId.toString())
                    || !createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z"))
                throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED", false);
            Instant.parse(createdAt);
            return Optional.of(
                    new SnapshotPublication(
                            1, snapshotId, sessionId, ReviewJson.sha256(raw), createdAt));
        } catch (IOException | RuntimeException error) {
            throw new SnapshotException("SNAPSHOT_STATE_WRITE_FAILED", false);
        }
    }

    private Observation observe(Path repository, String baseline, long deadline)
            throws IOException {
        rootsOutside(repository);
        var git = new SnapshotGit(repository, deadline);
        Map<String, String> inventory = git.inventory(baseline);
        if (inventory.size() > limits.files())
            throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED");
        String head = git.text("rev-parse", "HEAD"),
                branch = git.text("symbolic-ref", "--short", "HEAD");
        if (!head.matches("([0-9a-f]{40}|[0-9a-f]{64})"))
            throw new SnapshotException("SNAPSHOT_IDENTITY_MISMATCH");
        git.run("merge-base", "--is-ancestor", baseline, head);
        boolean fileMode =
                git.text("config", "--type=bool", "--default", "true", "--get", "core.filemode")
                        .equals("true");
        boolean symlinks =
                git.text("config", "--type=bool", "--default", "true", "--get", "core.symlinks")
                        .equals("true");
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        for (var file : inventory.entrySet()) {
            Path source = ReviewPaths.file(repository, file.getKey(), true);
            String mode = file.getValue();
            if (Files.isSymbolicLink(source)) mode = "120000";
            else if ("120000".equals(mode)) {
                if (symlinks) throw new SnapshotException("SNAPSHOT_SOURCE_UNREADABLE");
            } else if (mode == null || fileMode) {
                mode = ordinaryMode(source, mode);
            }
            if (!Set.of("100644", "100755", "120000").contains(mode))
                throw new SnapshotException("SNAPSHOT_SPECIAL_FILE_UNSUPPORTED");
            Entry entry =
                    readEntry(
                            repository,
                            file.getKey(),
                            mode,
                            file.getValue() != null,
                            deadline,
                            null);
            total = Math.addExact(total, entry.byteSize());
            if (total > limits.totalBytes()) throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED");
            entries.add(entry);
        }
        GitChangeSet changes =
                new GitChangeCollectorImpl(
                                "git", Duration.ofSeconds(30), limits.diffBytes(), deadline)
                        .collect(new WorkspaceHandle(repository, baseline));
        changes.files()
                .forEach(
                        f -> {
                            ReviewPaths.portable(f.path());
                            if (f.oldPath() != null) ReviewPaths.portable(f.oldPath());
                        });
        ReviewPaths.unique(changes.files().stream().map(GitChangedFile::path).toList());
        String fingerprint = new WorkspaceFingerprintCalculator().calculate(changes, !symlinks);
        checkDeadline(deadline);
        return new Observation(head, branch, entries, changes, fingerprint);
    }

    private void rootsOutside(Path repository) throws IOException {
        Path repo = ReviewPaths.directory(repository);
        ReviewPaths.directory(root);
        ReviewPaths.directory(executionRoot);
        ReviewPaths.separate(root, repo);
        ReviewPaths.separate(executionRoot, repo);
        if (!Files.isDirectory(root) || !Files.isDirectory(executionRoot))
            throw new SnapshotException("SNAPSHOT_PATH_INVALID");
    }

    private ScopePreview preview(UUID sessionId, String baseline, Observation observation) {
        var files =
                observation.changes().files().stream()
                        .sorted(Comparator.comparing(GitChangedFile::path, ReviewJson.CODE_POINTS))
                        .toList();
        return new ScopePreview(
                sessionId,
                baseline,
                observation.head(),
                observation.branch(),
                observation.fingerprint(),
                files,
                files.stream().map(GitChangedFile::path).toList());
    }

    private boolean same(Observation a, Observation b) {
        return a.head().equals(b.head())
                && a.branch().equals(b.branch())
                && a.entries().equals(b.entries())
                && a.changes().files().equals(b.changes().files())
                && Arrays.equals(a.changes().rawDiff(), b.changes().rawDiff())
                && a.fingerprint().equals(b.fingerprint());
    }

    private String ordinaryMode(Path path, String fallback) throws IOException {
        try {
            var p = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            return p.contains(PosixFilePermission.OWNER_EXECUTE)
                            || p.contains(PosixFilePermission.GROUP_EXECUTE)
                            || p.contains(PosixFilePermission.OTHERS_EXECUTE)
                    ? "100755"
                    : "100644";
        } catch (UnsupportedOperationException e) {
            return fallback == null ? "100644" : fallback;
        }
    }

    private Entry readEntry(
            Path repo,
            String relative,
            String mode,
            boolean tracked,
            long deadline,
            Path destination)
            throws IOException {
        Path source = ReviewPaths.file(repo, relative, true);
        Content content;
        if (Files.isSymbolicLink(source)) {
            byte[] bytes =
                    Files.readSymbolicLink(source)
                            .toString()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > limits.fileBytes())
                throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED");
            if (destination != null) write(destination, bytes);
            content = new Content(bytes.length, ReviewJson.sha256(bytes));
        } else content = hashFile(source, deadline, destination);
        return new Entry(
                relative,
                "120000".equals(mode) ? "SYMLINK" : "REGULAR_FILE",
                tracked,
                mode,
                content.size(),
                content.sha256());
    }

    private Content hashFile(Path source, long deadline, Path destination) throws IOException {
        MessageDigest hash = ReviewJson.digest();
        long size = 0;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                FileChannel output =
                        destination == null
                                ? null
                                : FileChannel.open(
                                        destination,
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkDeadline(deadline);
                size += read;
                if (size > limits.fileBytes())
                    throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED");
                hash.update(buffer, 0, read);
                if (output != null) {
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                    while (bytes.hasRemaining()) output.write(bytes);
                }
            }
            if (output != null) output.force(true);
        }
        return new Content(size, "sha256:" + HexFormat.of().formatHex(hash.digest()));
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        try (var channel =
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private long deadline() {
        return System.nanoTime() + limits.timeout().toNanos();
    }

    private void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new SnapshotException("SNAPSHOT_LIMIT_EXCEEDED");
    }

    private record Entry(
            String path,
            String type,
            boolean tracked,
            String gitMode,
            long byteSize,
            String contentSha256) {}

    private record Content(long size, String sha256) {}

    private record Observation(
            String head,
            String branch,
            List<Entry> entries,
            GitChangeSet changes,
            String fingerprint) {}
}
