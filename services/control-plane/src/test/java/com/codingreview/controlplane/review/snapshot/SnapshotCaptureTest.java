package com.codingreview.controlplane.review.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

class SnapshotCaptureTest {
    @TempDir Path temp;

    @Test
    void handoffComparisonUsesCapturedFullInputAndDoesNotModifyIndex() throws Exception {
        Path repo = repository();
        Files.writeString(repo.resolve("changed.txt"), "review input\n");
        var capture = capture();
        var preview = capture.preview(UUID.randomUUID(), repo, head(repo));
        var publication =
                capture.capture(
                        UUID.randomUUID(),
                        preview.sessionId(),
                        repo,
                        head(repo),
                        confirmation(preview));
        byte[] index = Files.readAllBytes(repo.resolve(".git/index"));
        capture.assertCurrent(
                publication.snapshotId(),
                publication.sessionId(),
                publication.manifestSha256(),
                repo,
                head(repo));
        assertThat(Files.readAllBytes(repo.resolve(".git/index"))).containsExactly(index);
        Files.writeString(repo.resolve("context.txt"), "external edit outside selected changes");
        assertThatThrownBy(
                        () ->
                                capture.assertCurrent(
                                        publication.snapshotId(),
                                        publication.sessionId(),
                                        publication.manifestSha256(),
                                        repo,
                                        head(repo)))
                .isInstanceOf(SnapshotException.class)
                .hasMessageContaining("SNAPSHOT_SOURCE_DRIFTED");
        assertThat(Files.readAllBytes(repo.resolve(".git/index"))).containsExactly(index);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "true,false",
        "true,true",
        "false,false",
        "false,true"
    })
    void capturesFileDirectoryTransitionsWithAndWithoutIndexStaging(
            boolean directoryToFile, boolean staged) throws Exception {
        Path repo = repository();
        Path old = repo.resolve(directoryToFile ? "a/b.txt" : "a");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "old baseline content\n");
        git(repo, "add", ".");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.local",
                "commit",
                "-m",
                "old shape");
        String baseline = head(repo);
        Files.delete(old);
        if (directoryToFile) Files.delete(repo.resolve("a"));
        Path added = repo.resolve(directoryToFile ? "a" : "a/b.txt");
        Files.createDirectories(added.getParent());
        Files.writeString(added, "new final content\n");
        if (staged) git(repo, "add", "-A");
        var capture = capture();
        var preview = capture.preview(UUID.randomUUID(), repo, baseline);
        assertThat(preview.changedPaths()).containsExactly("a", "a/b.txt");
        UUID snapshotId = UUID.randomUUID();
        capture.capture(snapshotId, preview.sessionId(), repo, baseline, confirmation(preview));
        Path published = temp.resolve("snapshots").resolve(snapshotId.toString());
        assertThat(published.resolve("content").resolve(directoryToFile ? "a" : "a/b.txt"))
                .hasContent("new final content\n");
        var manifest =
                new ObjectMapper().readTree(Files.readAllBytes(published.resolve("manifest.json")));
        var paths = new ArrayList<String>();
        manifest.path("entries").forEach(entry -> paths.add(entry.path("path").asText()));
        assertThat(paths)
                .contains(directoryToFile ? "a" : "a/b.txt")
                .doesNotContain(directoryToFile ? "a/b.txt" : "a");
        assertThat(manifest.path("changes").path("files").toString()).contains("ADDED", "DELETED");
    }

    @Test
    void capturesRenameDeletionBinaryAndTrackedIgnoredFilesWithScopeComplement() throws Exception {
        Path repo = repository();
        Files.writeString(repo.resolve("tracked.tmp"), "tracked despite ignore");
        git(repo, "add", "-f", "tracked.tmp");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.local",
                "commit",
                "-m",
                "tracked ignored");
        String baseline = head(repo);
        Files.move(repo.resolve("changed.txt"), repo.resolve("renamed.txt"));
        Files.delete(repo.resolve("context.txt"));
        git(repo, "add", "-A");
        Files.write(repo.resolve("binary.bin"), new byte[] {0, (byte) 255, 10});
        Files.writeString(repo.resolve("ignored.tmp"), "excluded by gitignore");
        var capture = capture();
        var preview = capture.preview(UUID.randomUUID(), repo, baseline);
        var expected =
                new ScopeConfirmation(
                        baseline,
                        preview.headCommit(),
                        preview.branch(),
                        preview.workspaceFingerprint(),
                        List.of("renamed.txt", "renamed.txt"));
        UUID snapshot = UUID.randomUUID();
        capture.capture(snapshot, preview.sessionId(), repo, baseline, expected);
        Path published = temp.resolve("snapshots").resolve(snapshot.toString());
        var manifest =
                new ObjectMapper().readTree(Files.readAllBytes(published.resolve("manifest.json")));
        assertThat(published.resolve("content/tracked.tmp")).hasContent("tracked despite ignore");
        assertThat(published.resolve("content/context.txt")).doesNotExist();
        assertThat(published.resolve("content/ignored.tmp")).doesNotExist();
        assertThat(Files.readAllBytes(published.resolve("content/binary.bin")))
                .containsExactly((byte) 0, (byte) 255, (byte) 10);
        assertThat(manifest.path("targetPaths")).hasSize(1);
        assertThat(manifest.path("excludedChangedPaths")).hasSize(2);
        assertThat(manifest.path("changes").path("files").toString())
                .contains("RENAMED", "DELETED", "ADDED", "changed.txt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"set", "mode", "head", "branch", "git-operation"})
    void detectsMetadataAndInventoryDriftDuringCopy(String drift) throws Exception {
        Path repo = repository();
        Files.writeString(repo.resolve("changed.txt"), "after");
        capture();
        var capture =
                new SnapshotCapture(
                        temp.resolve("snapshots"),
                        temp.resolve("workspaces"),
                        SnapshotLimits.defaults(),
                        () -> {
                            try {
                                switch (drift) {
                                    case "set" ->
                                            Files.writeString(
                                                    repo.resolve("later.txt"), "new context");
                                    case "mode" ->
                                            git(repo, "update-index", "--chmod=+x", "context.txt");
                                    case "head" ->
                                            git(
                                                    repo,
                                                    "-c",
                                                    "user.name=Test",
                                                    "-c",
                                                    "user.email=test@example.local",
                                                    "commit",
                                                    "--allow-empty",
                                                    "-m",
                                                    "later");
                                    case "branch" -> git(repo, "checkout", "-b", "later");
                                    case "git-operation" ->
                                            Files.writeString(
                                                    repo.resolve(".git/MERGE_HEAD"), head(repo));
                                    default -> throw new AssertionError(drift);
                                }
                            } catch (Exception error) {
                                throw new RuntimeException(error);
                            }
                        },
                        (a, b) -> Files.move(a, b, StandardCopyOption.ATOMIC_MOVE));
        var preview = capture.preview(UUID.randomUUID(), repo, head(repo));
        UUID snapshot = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                capture.capture(
                                        snapshot,
                                        preview.sessionId(),
                                        repo,
                                        preview.activeBaselineCommit(),
                                        confirmation(preview)))
                .isInstanceOf(SnapshotException.class);
        assertThat(temp.resolve("snapshots").resolve(snapshot.toString())).doesNotExist();
        assertThat(temp.resolve("snapshots/.staging").resolve(snapshot.toString())).doesNotExist();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void rejectsSourceDirectoryJunctionWithoutReadingItsTarget() throws Exception {
        Path repo = repository();
        Path nested = Files.createDirectory(repo.resolve("nested"));
        Files.writeString(nested.resolve("data.txt"), "original");
        git(repo, "add", "nested");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.local",
                "commit",
                "-m",
                "nested");
        String baseline = head(repo);
        Files.writeString(repo.resolve("changed.txt"), "after");
        Files.delete(nested.resolve("data.txt"));
        Files.delete(nested);
        Path foreign = Files.createDirectory(temp.resolve("foreign"));
        Files.writeString(foreign.resolve("data.txt"), "must remain");
        var process =
                new ProcessBuilder(
                                "cmd", "/c", "mklink", "/J", nested.toString(), foreign.toString())
                        .redirectErrorStream(true)
                        .start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        try {
            var capture = capture();
            assertThatThrownBy(() -> capture.preview(UUID.randomUUID(), repo, baseline))
                    .isInstanceOf(SnapshotException.class);
            assertThat(foreign.resolve("data.txt")).hasContent("must remain");
        } finally {
            Files.delete(nested);
        }
    }

    @Test
    void capturesFullVisibleContentAndPreservesRawDiff() throws Exception {
        Path repo = repository();
        Files.write(repo.resolve("changed.txt"), new byte[] {(byte) 0xff, 10});
        Files.writeString(repo.resolve("added.txt"), "added");
        Files.writeString(repo.resolve("ignored.tmp"), "secret");
        SnapshotCapture capture = capture();
        var preview = capture.preview(UUID.randomUUID(), repo, head(repo));
        UUID id = UUID.randomUUID();
        var publication =
                capture.capture(
                        id,
                        preview.sessionId(),
                        repo,
                        preview.activeBaselineCommit(),
                        confirmation(preview));
        Path root = temp.resolve("snapshots").resolve(id.toString());
        var manifest =
                new ObjectMapper().readTree(Files.readAllBytes(root.resolve("manifest.json")));
        assertThat(manifest.get("snapshotId").asText()).isEqualTo(id.toString());
        assertThat(publication.manifestSha256())
                .isEqualTo(
                        com.codingreview.controlplane.review.ReviewJson.sha256(
                                Files.readAllBytes(root.resolve("manifest.json"))));
        assertThat(Files.readAllBytes(root.resolve("content/changed.txt")))
                .containsExactly((byte) 0xff, (byte) 10);
        assertThat(root.resolve("content/context.txt")).hasContent("context");
        assertThat(root.resolve("content/ignored.tmp")).doesNotExist();
        assertThat(Files.readAllBytes(root.resolve("artifacts/changes.diff")))
                .contains((byte) 0xff);
        assertThat(manifest.get("entries")).hasSize(4);
        assertThat(repo.resolve("context.txt")).hasContent("context");
    }

    @Test
    void refusesExpiredPreviewAndDoesNotPublish() throws Exception {
        Path repo = repository();
        Files.writeString(repo.resolve("changed.txt"), "after");
        var capture = capture();
        var preview = capture.preview(UUID.randomUUID(), repo, head(repo));
        Files.writeString(repo.resolve("changed.txt"), "later");
        UUID id = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                capture.capture(
                                        id,
                                        preview.sessionId(),
                                        repo,
                                        preview.activeBaselineCommit(),
                                        confirmation(preview)))
                .isInstanceOf(SnapshotException.class)
                .extracting("code")
                .isEqualTo("REVIEW_SCOPE_PREVIEW_EXPIRED");
        assertThat(temp.resolve("snapshots").resolve(id.toString())).doesNotExist();
    }

    @Test
    void refusesExistingPublicationWithoutReplacingIt() throws Exception {
        Path repo = repository();
        Files.writeString(repo.resolve("changed.txt"), "after");
        var capture = capture();
        var preview = capture.preview(UUID.randomUUID(), repo, head(repo));
        UUID id = UUID.randomUUID();
        Path destination = temp.resolve("snapshots").resolve(id.toString());
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("owner.txt"), "original");
        assertThatThrownBy(
                        () ->
                                capture.capture(
                                        id,
                                        preview.sessionId(),
                                        repo,
                                        preview.activeBaselineCommit(),
                                        confirmation(preview)))
                .isInstanceOf(SnapshotException.class);
        assertThat(destination.resolve("owner.txt")).hasContent("original");
    }

    private SnapshotCapture capture() throws Exception {
        Files.createDirectories(temp.resolve("snapshots"));
        Files.createDirectories(temp.resolve("workspaces"));
        return new SnapshotCapture(
                temp.resolve("snapshots"), temp.resolve("workspaces"), SnapshotLimits.defaults());
    }

    @Test
    void detectsUnchangedContextDriftDuringCopy() throws Exception {
        Path repo = repository();
        Files.writeString(repo.resolve("changed.txt"), "after");
        capture();
        var capture =
                new SnapshotCapture(
                        temp.resolve("snapshots"),
                        temp.resolve("workspaces"),
                        SnapshotLimits.defaults(),
                        () -> {
                            try {
                                Files.writeString(repo.resolve("context.txt"), "drift");
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        (a, b) -> Files.move(a, b, StandardCopyOption.ATOMIC_MOVE));
        var preview = capture.preview(UUID.randomUUID(), repo, head(repo));
        UUID id = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                capture.capture(
                                        id,
                                        preview.sessionId(),
                                        repo,
                                        preview.activeBaselineCommit(),
                                        confirmation(preview)))
                .isInstanceOf(SnapshotException.class)
                .extracting("code")
                .isEqualTo("SNAPSHOT_SOURCE_DRIFTED");
        assertThat(temp.resolve("snapshots").resolve(id.toString())).doesNotExist();
        assertThat(temp.resolve("snapshots/.staging").resolve(id.toString())).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../x", "/x", "a//b", "a/./b", "C:x", "a\\b", "CON.txt", "a/aux", "a.", "a ",
                ".git/x", "x?y"
            })
    void rejectsPortablePathViolations(String path) {
        assertThatThrownBy(() -> ReviewPaths.portable(path)).isInstanceOf(SnapshotException.class);
    }

    @Test
    void rejectsCaseUnicodeAndFileDirectoryCollisions() {
        for (var paths : List.of(List.of("a", "A"), List.of("é", "e\u0301"), List.of("a", "a/b")))
            assertThatThrownBy(() -> ReviewPaths.distinct(paths))
                    .isInstanceOf(SnapshotException.class);
    }

    @Test
    void doesNotFallbackWhenAtomicMoveIsUnsupported() throws Exception {
        Path repo = repository();
        Files.writeString(repo.resolve("changed.txt"), "after");
        capture();
        var capture =
                new SnapshotCapture(
                        temp.resolve("snapshots"),
                        temp.resolve("workspaces"),
                        SnapshotLimits.defaults(),
                        () -> {},
                        (a, b) -> {
                            throw new AtomicMoveNotSupportedException(
                                    a.toString(), b.toString(), "injected");
                        });
        var preview = capture.preview(UUID.randomUUID(), repo, head(repo));
        UUID id = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                capture.capture(
                                        id,
                                        preview.sessionId(),
                                        repo,
                                        preview.activeBaselineCommit(),
                                        confirmation(preview)))
                .isInstanceOf(SnapshotException.class)
                .extracting("code")
                .isEqualTo("SNAPSHOT_ATOMIC_PUBLISH_UNSUPPORTED");
        assertThat(temp.resolve("snapshots").resolve(id.toString())).doesNotExist();
    }

    @Test
    void textRepresentationOfSymlinkUsesTargetBytesWithoutDereferencing() throws Exception {
        Path repo = repository();
        git(repo, "config", "core.symlinks", "false");
        Files.writeString(repo.resolve("link"), "context.txt");
        String blob = git(repo, "hash-object", "-w", "link").trim();
        git(repo, "update-index", "--add", "--cacheinfo", "120000," + blob + ",link");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.local",
                "commit",
                "-m",
                "link");
        String baseline = head(repo);
        Files.writeString(repo.resolve("link"), "changed.txt");
        var capture = capture();
        var preview = capture.preview(UUID.randomUUID(), repo, baseline);
        UUID id = UUID.randomUUID();
        capture.capture(
                id,
                preview.sessionId(),
                repo,
                preview.activeBaselineCommit(),
                confirmation(preview));
        assertThat(temp.resolve("snapshots").resolve(id.toString()).resolve("content/link"))
                .hasContent("changed.txt");
    }

    @Test
    void indexStagingDoesNotChangeFinalContentFingerprint() throws Exception {
        Path repo = repository();
        Files.writeString(repo.resolve("changed.txt"), "after");
        var capture = capture();
        UUID session = UUID.randomUUID();
        String baseline = head(repo);
        String before = capture.preview(session, repo, baseline).workspaceFingerprint();
        git(repo, "add", "changed.txt");
        assertThat(capture.preview(session, repo, baseline).workspaceFingerprint())
                .isEqualTo(before);
    }

    @Test
    void enforcesEachCaptureBudgetWithoutPublishing() throws Exception {
        Path repo = repository();
        Files.writeString(repo.resolve("changed.txt"), "after");
        var normal = capture();
        var preview = normal.preview(UUID.randomUUID(), repo, head(repo));
        var defaults = SnapshotLimits.defaults();
        for (var limits :
                List.of(
                        new SnapshotLimits(
                                1,
                                defaults.fileBytes(),
                                defaults.totalBytes(),
                                defaults.manifestBytes(),
                                defaults.diffBytes(),
                                defaults.timeout()),
                        new SnapshotLimits(
                                defaults.files(),
                                1,
                                defaults.totalBytes(),
                                defaults.manifestBytes(),
                                defaults.diffBytes(),
                                defaults.timeout()),
                        new SnapshotLimits(
                                defaults.files(),
                                defaults.fileBytes(),
                                1,
                                defaults.manifestBytes(),
                                defaults.diffBytes(),
                                defaults.timeout()),
                        new SnapshotLimits(
                                defaults.files(),
                                defaults.fileBytes(),
                                defaults.totalBytes(),
                                1,
                                defaults.diffBytes(),
                                defaults.timeout()),
                        new SnapshotLimits(
                                defaults.files(),
                                defaults.fileBytes(),
                                defaults.totalBytes(),
                                defaults.manifestBytes(),
                                1,
                                defaults.timeout()),
                        new SnapshotLimits(
                                defaults.files(),
                                defaults.fileBytes(),
                                defaults.totalBytes(),
                                defaults.manifestBytes(),
                                defaults.diffBytes(),
                                Duration.ofNanos(1)))) {
            var limited =
                    new SnapshotCapture(
                            temp.resolve("snapshots"), temp.resolve("workspaces"), limits);
            UUID id = UUID.randomUUID();
            assertThatThrownBy(
                            () ->
                                    limited.capture(
                                            id,
                                            preview.sessionId(),
                                            repo,
                                            preview.activeBaselineCommit(),
                                            confirmation(preview)))
                    .isInstanceOf(SnapshotException.class);
            assertThat(temp.resolve("snapshots").resolve(id.toString())).doesNotExist();
        }
    }

    static ScopeConfirmation confirmation(ScopePreview p) {
        return new ScopeConfirmation(
                p.activeBaselineCommit(),
                p.headCommit(),
                p.branch(),
                p.workspaceFingerprint(),
                p.changedPaths());
    }

    private Path repository() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        git(repo, "init", "-b", "main");
        git(repo, "config", "core.autocrlf", "false");
        Files.writeString(repo.resolve("changed.txt"), "before\n");
        Files.writeString(repo.resolve("context.txt"), "context");
        Files.writeString(repo.resolve(".gitignore"), "*.tmp\n");
        git(repo, "add", ".");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.local",
                "commit",
                "-m",
                "initial");
        return repo;
    }

    private String head(Path repo) throws Exception {
        return git(repo, "rev-parse", "HEAD").trim();
    }

    static String git(Path repo, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }
}
