package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.codingreview.controlplane.application.session.*;
import com.codingreview.controlplane.review.snapshot.*;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.session.model.*;
import com.codingreview.controlplane.session.persistence.*;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluatorImpl;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspectorImpl;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Java 捕获 → 真实 Python HTTP/SSE → 清理 → 原始结果导入。 */
class ReviewSimulationIntegrationTest {
    @TempDir Path temp;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"ordinary", "directory-to-file", "file-to-directory"})
    void importsRealSnapshotSimulationAndReleasesOnlyAfterVerifiedOriginalResult(String shape)
            throws Exception {
        Path source = repository();
        if (!shape.equals("ordinary")) {
            Path previous = source.resolve(shape.equals("directory-to-file") ? "a/b.txt" : "a");
            Files.createDirectories(previous.getParent());
            Files.writeString(previous, "original shape\n");
            git(source, "add", previous.toString());
            git(
                    source,
                    "-c",
                    "user.name=Test",
                    "-c",
                    "user.email=test@example.local",
                    "commit",
                    "-m",
                    "baseline shape");
            Files.delete(previous);
            if (shape.equals("directory-to-file")) Files.delete(source.resolve("a"));
            Path current = source.resolve(shape.equals("directory-to-file") ? "a" : "a/b.txt");
            Files.createDirectories(current.getParent());
            Files.writeString(current, "final shape\n");
        }
        Path snapshots = Files.createDirectory(temp.resolve("snapshots"));
        Path executions = Files.createDirectory(temp.resolve("executions"));
        Path runtime = Files.createDirectory(temp.resolve("java-runtime"));
        var sessions =
                new FileCodingSessionRepository(
                        runtime, JsonMapper.builder().findAndAddModules().build());
        var session =
                CodingSession.initializing(
                                UUID.randomUUID(),
                                "simulation",
                                source,
                                "main",
                                new ChangePolicy(List.of(), List.of(".git/**")),
                                Instant.now())
                        .activateRealRepository(
                                new GitRepositorySnapshot(
                                        source,
                                        "main",
                                        git(source, "rev-parse", "HEAD").trim(),
                                        true),
                                Instant.now());
        sessions.save(session);
        CountDownLatch completed = new CountDownLatch(1);
        var store =
                new ReviewExecutionStore(
                        runtime,
                        (a, b) -> {
                            byte[] bytes = Files.readAllBytes(a);
                            Files.move(
                                    a,
                                    b,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                            if (b.getFileName().toString().equals("execution.json")
                                    && ReviewJson.parse(bytes)
                                            .path("state")
                                            .asText()
                                            .equals("COMPLETED")) completed.countDown();
                        });
        var guard = new ReviewExecutionGuard(store);
        var locks = new SessionOperationLockManager();
        var capture = new SnapshotCapture(snapshots, executions, SnapshotLimits.defaults());
        var publications = new SnapshotPublicationStore(runtime);
        try (PythonService python = new PythonService(temp, snapshots, executions);
                ReviewHarnessClient client =
                        new ReviewHarnessClient(
                                python.base,
                                python.credential,
                                Duration.ofSeconds(3),
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(5));
                ReviewCoordinator coordinator =
                        new ReviewCoordinator(
                                store,
                                guard,
                                client,
                                new ReviewFactImporter(store, guard, locks),
                                publications,
                                locks,
                                8,
                                Duration.ofMillis(50))) {
            CodingIdleSafety idle =
                    id -> {
                        if (!Thread.holdsLock(locks.lock(id)))
                            throw new IllegalStateException("lock required");
                    };
            var service =
                    new ReviewApplicationService(
                            sessions,
                            locks,
                            idle,
                            new SessionWorkspaceGuard(new GitRepositoryInspectorImpl()),
                            new ChangePolicyEvaluatorImpl(),
                            capture,
                            publications,
                            store,
                            guard,
                            client,
                            coordinator);
            var preview = service.preview(session.sessionId());
            var confirmation =
                    new ScopeConfirmation(
                            preview.activeBaselineCommit(),
                            preview.headCommit(),
                            preview.branch(),
                            preview.workspaceFingerprint(),
                            preview.changedPaths());
            ReviewExecution accepted =
                    service.start(session.sessionId(), confirmation, "fixture simulation");
            assertThat(completed.await(15, TimeUnit.SECONDS)).isTrue();
            ReviewExecution imported =
                    store.find(session.sessionId(), accepted.runId()).orElseThrow();
            assertThat(imported.state()).isEqualTo("COMPLETED");
            assertThat(imported.resourceState()).isEqualTo("RELEASED");
            assertThat(imported.eventCursor())
                    .isEqualTo(imported.observedLastEventSequence())
                    .isEqualTo(3);
            assertThat(guard.blocks(session.sessionId())).isFalse();
            var result = ReviewJson.parse(store.result(imported));
            assertThat(result.path("findings").isEmpty()).isTrue();
            assertThat(result.path("reportDraft").path("markdown").asText()).isEmpty();
            assertThat(Files.exists(executions.resolve(imported.runId().toString()))).isFalse();
            assertThat(
                            snapshots
                                    .resolve(imported.snapshotId().toString())
                                    .resolve("content/context.txt"))
                    .hasContent("context");
            assertThat(source.resolve("context.txt")).hasContent("context");
            // Two independent runs consume the fixed Snapshot without sharing writable files.
            var second = ReviewExecution.preparing(session.sessionId());
            var prior = publications.find(imported.snapshotId()).orElseThrow();
            second =
                    new ReviewExecution(
                                    1,
                                    second.runId(),
                                    second.sessionId(),
                                    second.reviewThreadId(),
                                    prior.snapshotId(),
                                    null,
                                    null,
                                    null,
                                    "PREPARING",
                                    false,
                                    null,
                                    "NOT_ACQUIRED",
                                    0,
                                    0,
                                    null,
                                    second.createdAt(),
                                    second.updatedAt(),
                                    null)
                            .submitting(prior, "second simulation");
            store.save(second);
            var outcome = coordinator.reconcile(second.sessionId(), second.runId());
            assertThat(outcome.state()).isEqualTo("COMPLETED");
            assertThat(Files.exists(executions.resolve(second.runId().toString()))).isFalse();
            assertThat(
                            snapshots
                                    .resolve(prior.snapshotId().toString())
                                    .resolve("content/context.txt"))
                    .hasContent("context");
            // Lose every POST response after Python accepted the exact request.
            // A later reconciliation must query that same Run, without recapture.
            try (LossyProxy proxy = new LossyProxy(python.base);
                    ReviewHarnessClient lossy =
                            new ReviewHarnessClient(
                                    proxy.base,
                                    python.credential,
                                    Duration.ofSeconds(2),
                                    Duration.ofSeconds(3),
                                    Duration.ofSeconds(3));
                    ReviewCoordinator retry =
                            new ReviewCoordinator(
                                    store,
                                    guard,
                                    lossy,
                                    new ReviewFactImporter(store, guard, locks),
                                    publications,
                                    locks,
                                    8,
                                    Duration.ofMillis(50))) {
                var third = ReviewExecution.preparing(session.sessionId());
                third =
                        new ReviewExecution(
                                        1,
                                        third.runId(),
                                        third.sessionId(),
                                        third.reviewThreadId(),
                                        prior.snapshotId(),
                                        null,
                                        null,
                                        null,
                                        "PREPARING",
                                        false,
                                        null,
                                        "NOT_ACQUIRED",
                                        0,
                                        0,
                                        null,
                                        third.createdAt(),
                                        third.updatedAt(),
                                        null)
                                .submitting(prior, "lost response");
                store.save(third);
                UUID thirdId = third.runId();
                assertThatThrownBy(() -> retry.reconcile(session.sessionId(), thirdId))
                        .isInstanceOf(ReviewException.class);
                assertThat(guard.blocks(session.sessionId())).isTrue();
                assertThat(proxy.acceptedBodies).isNotEmpty();
                for (byte[] body : proxy.acceptedBodies)
                    assertThat(body).containsExactly(third.requestBytes());
                int acceptedPosts = proxy.acceptedBodies.size();
                assertThat(retry.reconcile(session.sessionId(), thirdId).state())
                        .isEqualTo("COMPLETED");
                assertThat(proxy.acceptedBodies).hasSize(acceptedPosts);
                assertThat(store.list(session.sessionId(), 10)).hasSize(3);
                assertThat(guard.blocks(session.sessionId())).isFalse();
            }
        }
    }

    Path repository() throws Exception {
        Path source = Files.createDirectory(temp.resolve("repository"));
        git(source, "init", "-b", "main");
        git(source, "config", "core.autocrlf", "false");
        Files.writeString(source.resolve("context.txt"), "context");
        Files.writeString(source.resolve("changed.txt"), "before\n");
        git(source, "add", ".");
        git(
                source,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.local",
                "commit",
                "-m",
                "initial");
        Files.writeString(source.resolve("changed.txt"), "after\n");
        return source;
    }

    static String git(Path repository, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] bytes = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        return ReviewJson.utf8(bytes);
    }

    static final class PythonService implements AutoCloseable {
        final String credential = "test-only-" + UUID.randomUUID();
        final URI base;
        final Process process;
        final Thread reader;

        PythonService(Path temp, Path snapshots, Path executions) throws Exception {
            int port;
            try (java.net.ServerSocket socket =
                    new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            base = URI.create("http://127.0.0.1:" + port);
            Path harness = Path.of("../review-harness").toAbsolutePath().normalize();
            Path executable =
                    System.getenv("CODE_WEAVE_TEST_PYTHON") == null
                            ? harness.resolve(
                                    System.getProperty("os.name").toLowerCase().contains("win")
                                            ? ".venv/Scripts/python.exe"
                                            : ".venv/bin/python")
                            : Path.of(System.getenv("CODE_WEAVE_TEST_PYTHON"));
            if (!Files.isExecutable(executable))
                throw new IllegalStateException(
                        "Python 3.12 Harness venv is required for cross-service acceptance");
            var builder =
                    new ProcessBuilder(
                            executable.toString(),
                            "-c",
                            "import os; from review_harness.infrastructure.persistence.database"
                                    + " import migrate;"
                                    + " migrate(os.environ['CODE_WEAVE_REVIEW_DATABASE_URL']); from"
                                    + " review_harness.main import main; main()");
            builder.directory(harness.toFile()).redirectErrorStream(true);
            builder.environment().keySet().removeIf(name -> name.startsWith("CODE_WEAVE_REVIEW_"));
            builder.environment()
                    .put(
                            "CODE_WEAVE_REVIEW_DATABASE_URL",
                            "sqlite+aiosqlite:///"
                                    + temp.resolve("python.sqlite").toString().replace('\\', '/'));
            builder.environment().put("CODE_WEAVE_REVIEW_SNAPSHOT_ROOT", snapshots.toString());
            builder.environment().put("CODE_WEAVE_REVIEW_EXECUTION_ROOT", executions.toString());
            builder.environment().put("CODE_WEAVE_REVIEW_INTERNAL_BEARER_TOKEN", credential);
            builder.environment().put("CODE_WEAVE_REVIEW_PORT", Integer.toString(port));
            CountDownLatch ready = new CountDownLatch(1);
            process = builder.start();
            reader =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (var input =
                                                new java.io.BufferedReader(
                                                        new java.io.InputStreamReader(
                                                                process.getInputStream(),
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8))) {
                                            String line;
                                            while ((line = input.readLine()) != null)
                                                if (line.contains("Uvicorn running on"))
                                                    ready.countDown();
                                        } catch (java.io.IOException ignored) {
                                        }
                                    });
            if (!ready.await(20, TimeUnit.SECONDS)) {
                close();
                throw new IllegalStateException("Python service failed startup");
            }
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
            reader.join(2000);
        }
    }

    static final class LossyProxy implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final URI base;
        final List<byte[]> acceptedBodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.net.http.HttpClient forward = java.net.http.HttpClient.newHttpClient();

        LossyProxy(URI upstream) throws Exception {
            server =
                    com.sun.net.httpserver.HttpServer.create(
                            new InetSocketAddress("127.0.0.1", 0), 0);
            base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext(
                    "/",
                    exchange -> {
                        try {
                            byte[] body = exchange.getRequestBody().readAllBytes();
                            var request =
                                    java.net.http.HttpRequest.newBuilder(
                                                    upstream.resolve(exchange.getRequestURI()))
                                            .header(
                                                    "Authorization",
                                                    exchange.getRequestHeaders()
                                                            .getFirst("Authorization"))
                                            .method(
                                                    exchange.getRequestMethod(),
                                                    java.net.http.HttpRequest.BodyPublishers
                                                            .ofByteArray(body));
                            String cursor = exchange.getRequestHeaders().getFirst("Last-Event-ID");
                            if (cursor != null) request.header("Last-Event-ID", cursor);
                            if (body.length > 0) request.header("Content-Type", "application/json");
                            var response =
                                    forward.send(
                                            request.build(),
                                            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                            if (exchange.getRequestMethod().equals("POST")
                                    && exchange.getRequestURI()
                                            .getPath()
                                            .endsWith("/review-runs")) {
                                if (response.statusCode() == 200 || response.statusCode() == 202)
                                    acceptedBodies.add(body);
                                return;
                            }
                            for (String header :
                                    List.of("Content-Type", "ETag", "X-Content-SHA256"))
                                response.headers()
                                        .firstValue(header)
                                        .ifPresent(
                                                value ->
                                                        exchange.getResponseHeaders()
                                                                .set(header, value));
                            exchange.sendResponseHeaders(
                                    response.statusCode(), response.body().length);
                            exchange.getResponseBody().write(response.body());
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
        }

        @Override
        public void close() {
            server.stop(0);
            forward.shutdownNow();
        }
    }
}
