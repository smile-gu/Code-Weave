package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.codingreview.controlplane.application.session.SessionOperationLockManager;
import com.codingreview.controlplane.review.snapshot.*;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.persistence.FileCodingSessionRepository;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluatorImpl;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspectorImpl;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.*;
import java.util.*;

/** Public Review entry points must not execute repository-supplied programs. */
class ReviewGitSafetyTest {
    @TempDir Path root;

    @Test
    void rejectsLfsAttributesWithoutHydratingOrChangingPointerDiffSemantics() throws Exception {
        Path repo = Files.createDirectory(root.resolve("lfs"));
        git(repo, "init", "-b", "main");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.local",
                "commit",
                "--allow-empty",
                "-m",
                "base");
        Files.writeString(repo.resolve(".gitattributes"), "*.bin filter=lfs\n");
        Files.writeString(
                repo.resolve("asset.bin"),
                "version https://git-lfs.github.com/spec/v1\noid sha256:"
                        + "a".repeat(64)
                        + "\nsize 3\n");
        assertThatThrownBy(() -> new GitRepositoryInspectorImpl().inspect(repo))
                .extracting("code")
                .isEqualTo("REPOSITORY_FILTER_UNSUPPORTED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "audit,clean",
        "audit,process",
        "unset,clean",
        "unset,process",
        "unspecified,clean",
        "unspecified,process"
    })
    void rejectsConversionFiltersBeforeDiffCanExecuteThem(String driver, String kind)
            throws Exception {
        Path repo = Files.createDirectory(root.resolve("filtered"));
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("change.txt"), "before\n");
        git(repo, "add", ".");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.local",
                "commit",
                "-m",
                "base");
        Files.writeString(repo.resolve(".gitattributes"), "*.txt filter=" + driver + "\n");
        Path script = probe(kind, kind.equals("clean") ? "cat\n" : "exit 1\n");
        git(repo, "config", "filter." + driver + "." + kind, script.toString().replace('\\', '/'));
        Files.writeString(repo.resolve("change.txt"), "after\n");
        assertThatThrownBy(
                        () -> {
                            var process =
                                    com.codingreview.controlplane.workspace.git.ReadOnlyGit.process(
                                                    "git", repo, List.of("diff", "HEAD", "--"))
                                            .start();
                            process.getInputStream().readAllBytes();
                            process.waitFor();
                        })
                .isInstanceOf(
                        com.codingreview.controlplane.workspace.git.GitRepositoryException.class)
                .extracting("code")
                .isEqualTo("REPOSITORY_FILTER_UNSUPPORTED");
        assertThat(root.resolve(kind + ".invoked")).doesNotExist();
        assertThatThrownBy(() -> new GitRepositoryInspectorImpl().inspect(repo))
                .extracting("code")
                .isEqualTo("REPOSITORY_FILTER_UNSUPPORTED");
    }

    @Test
    void previewAndStartDisableFsmonitorExternalDiffAndTextconvWithoutRefreshingIndex()
            throws Exception {
        Path repo = Files.createDirectory(root.resolve("repository"));
        git(repo, "init", "-b", "main");
        git(repo, "config", "core.autocrlf", "false");
        Files.writeString(repo.resolve("change.txt"), "before\n");
        Files.writeString(repo.resolve(".gitattributes"), "*.txt diff=probe\n");
        git(repo, "add", ".");
        git(
                repo,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.local",
                "commit",
                "-m",
                "base");
        String baseline = git(repo, "rev-parse", "HEAD").trim();
        Path fsmonitor = probe("fsmonitor", "printf 'token\\n/\\n'\n");
        Path externalDiff = probe("external-diff", "printf 'diff probe\\n'\n");
        Path textconv = probe("textconv", "printf 'textconv probe\\n'\n");
        git(repo, "config", "core.fsmonitor", fsmonitor.toString().replace('\\', '/'));
        git(repo, "config", "diff.external", externalDiff.toString().replace('\\', '/'));
        git(repo, "config", "diff.probe.textconv", textconv.toString().replace('\\', '/'));
        Files.writeString(repo.resolve("change.txt"), "after\n");
        byte[] index = Files.readAllBytes(repo.resolve(".git/index"));
        var sessions =
                new FileCodingSessionRepository(
                        root, JsonMapper.builder().findAndAddModules().build());
        var session =
                CodingSession.initializing(
                                UUID.randomUUID(),
                                "safe git",
                                repo,
                                "main",
                                new ChangePolicy(List.of(), List.of()),
                                Instant.now())
                        .activateRealRepository(
                                new GitRepositorySnapshot(repo, "main", baseline, false),
                                Instant.now());
        sessions.save(session);
        var store = new ReviewExecutionStore(root);
        var guard = new ReviewExecutionGuard(store);
        var locks = new SessionOperationLockManager();
        var publications = new SnapshotPublicationStore(root);
        var capture =
                new SnapshotCapture(
                        Files.createDirectory(root.resolve("snapshots")),
                        Files.createDirectory(root.resolve("executions")),
                        SnapshotLimits.defaults());
        try (var client =
                        new ReviewHarnessClient(
                                URI.create("http://127.0.0.1:1"),
                                "test-only",
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(1));
                var coordinator =
                        new ReviewCoordinator(
                                store,
                                guard,
                                client,
                                new ReviewFactImporter(store, guard, locks),
                                publications,
                                locks,
                                1,
                                Duration.ofSeconds(5))) {
            var review =
                    new ReviewApplicationService(
                            sessions,
                            locks,
                            id -> {},
                            new SessionWorkspaceGuard(new GitRepositoryInspectorImpl()),
                            new ChangePolicyEvaluatorImpl(),
                            capture,
                            publications,
                            store,
                            guard,
                            client,
                            coordinator);
            var preview = review.preview(session.sessionId());
            assertNoProbes();
            assertThat(Files.readAllBytes(repo.resolve(".git/index"))).containsExactly(index);
            var confirmation =
                    new ScopeConfirmation(
                            baseline,
                            preview.headCommit(),
                            preview.branch(),
                            preview.workspaceFingerprint(),
                            preview.changedPaths());
            for (String driver : List.of("audit", "unset", "unspecified")) {
                for (String kind : List.of("clean", "process")) {
                    Files.writeString(
                            repo.resolve(".gitattributes"), "*.txt filter=" + driver + "\n");
                    Path script = probe(kind, kind.equals("clean") ? "cat\n" : "exit 1\n");
                    git(
                            repo,
                            "config",
                            "filter." + driver + "." + kind,
                            script.toString().replace('\\', '/'));
                    byte[] beforeRejectedCalls = Files.readAllBytes(repo.resolve(".git/index"));
                    assertThatThrownBy(() -> review.preview(session.sessionId()))
                            .extracting("code")
                            .isEqualTo("REPOSITORY_FILTER_UNSUPPORTED");
                    assertThatThrownBy(
                                    () ->
                                            review.start(
                                                    session.sessionId(),
                                                    confirmation,
                                                    "simulation"))
                            .extracting("code")
                            .isEqualTo("REPOSITORY_FILTER_UNSUPPORTED");
                    assertThat(store.list(session.sessionId(), 10)).isEmpty();
                    assertThat(root.resolve(kind + ".invoked")).doesNotExist();
                    assertThat(Files.readAllBytes(repo.resolve(".git/index")))
                            .containsExactly(beforeRejectedCalls);
                    git(repo, "config", "--unset", "filter." + driver + "." + kind);
                }
            }
            Files.writeString(repo.resolve(".gitattributes"), "*.txt diff=probe\n");
            var refreshed = review.preview(session.sessionId());
            var refreshedConfirmation =
                    new ScopeConfirmation(
                            baseline,
                            refreshed.headCommit(),
                            refreshed.branch(),
                            refreshed.workspaceFingerprint(),
                            refreshed.changedPaths());
            byte[] beforeStart = Files.readAllBytes(repo.resolve(".git/index"));
            var execution = review.start(session.sessionId(), refreshedConfirmation, "simulation");
            assertThat(publications.find(execution.snapshotId())).isPresent();
            assertNoProbes();
            assertThat(Files.readAllBytes(repo.resolve(".git/index"))).containsExactly(beforeStart);
        }
    }

    private Path probe(String name, String output) throws Exception {
        Path marker = root.resolve(name + ".invoked");
        Path script = root.resolve(name + ".sh");
        Files.writeString(
                script,
                "#!/bin/sh\nprintf 'invoked\\n' >> '"
                        + marker.toString().replace('\\', '/')
                        + "'\n"
                        + output);
        if (Files.getFileStore(script).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                    script,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }
        return script;
    }

    private void assertNoProbes() {
        for (String name : List.of("fsmonitor", "external-diff", "textconv"))
            assertThat(root.resolve(name + ".invoked")).doesNotExist();
    }

    private String git(Path repo, String... args) throws Exception {
        return ReviewSimulationIntegrationTest.git(repo, args);
    }
}
