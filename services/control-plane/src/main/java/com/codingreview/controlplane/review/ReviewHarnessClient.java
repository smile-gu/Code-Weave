package com.codingreview.controlplane.review;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** 有界 HTTP/SSE 客户端；原始字节用于摘要校验，不在 Session 锁内调用。 */
public final class ReviewHarnessClient implements AutoCloseable {
    public record Result(byte[] bytes, String sha256, String etag) {
        public Result {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record Response(int status, byte[] bytes, HttpHeaders headers) {}

    private final URI base;
    private final String credential;
    private final Duration requestTimeout, idleTimeout, roundTimeout;
    private final HttpClient http;
    private final Semaphore connections = new Semaphore(2);
    private final String executionMode;

    public ReviewHarnessClient(
            URI base,
            String credential,
            Duration connectionTimeout,
            Duration requestTimeout,
            Duration idleTimeout) {
        this(base, credential, connectionTimeout, requestTimeout, idleTimeout, null);
    }

    public ReviewHarnessClient(URI base, String credential, Duration connectionTimeout,
            Duration requestTimeout, Duration idleTimeout, String executionMode) {
        if (executionMode != null && !Set.of("SIMULATED", "REAL").contains(executionMode))
            throw new IllegalArgumentException("Supported review execution mode required");
        this.executionMode = executionMode;
        if (!Set.of("http", "https").contains(base.getScheme())
                || !Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(base.getHost())
                || base.getRawUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException("Local Harness base URL required");
        for (Duration duration : List.of(connectionTimeout, requestTimeout, idleTimeout))
            if (duration.isZero() || duration.isNegative())
                throw new IllegalArgumentException("Positive network timeout required");
        this.base = base;
        this.credential = credential;
        this.requestTimeout = requestTimeout;
        this.idleTimeout = idleTimeout;
        roundTimeout = Duration.ofSeconds(30);
        http =
                HttpClient.newBuilder()
                        .connectTimeout(connectionTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public void requireAvailable() {
        if (credential == null || credential.isBlank() || credential.equalsIgnoreCase("change-me"))
            throw new ReviewException("REVIEW_HARNESS_NOT_READY");
    }

    public Map<String, String> executionContract() {
        String mode = executionMode == null ? "SIMULATED" : executionMode;
        return Map.of("executionMode", mode, "resultVersion", mode.equals("REAL") ? "2.0" : "1.0");
    }

    public void requireExecutionCompatibility() {
        requireAvailable();
        // The original constructor remains the old 1.0 test/integration seam.
        // Production configuration always supplies an explicit mode.
        if (executionMode == null) return;
        Response response = request("GET", "/review-capabilities", null, 65536);
        if (response.status() != 200) throw new ReviewException("REVIEW_HARNESS_NOT_READY");
        var value = ReviewJson.parse(response.bytes());
        ReviewWireSchemas.validate("review-capabilities-v1", value);
        if (!value.path("configuredContract").equals(ReviewJson.parse(ReviewJson.canonical(executionContract()))))
            throw new ReviewException("EXECUTION_CONTRACT_UNSUPPORTED");
        if (!value.path("status").asText().equals("READY") || !value.path("failureCodes").isEmpty())
            throw new ReviewException("REVIEW_HARNESS_NOT_READY");
    }

    public void requireMultipassCompatibility() {
        requireAvailable();
        Response response = request("GET", "/review-capabilities?protocolVersion=1.1", null, 65536);
        if (response.status() != 200) throw new ReviewException("REVIEW_HARNESS_NOT_READY");
        ReviewPlan.requireCompatibility(ReviewJson.parse(response.bytes()),
                executionMode == null ? "SIMULATED" : executionMode);
    }

    public Response status(UUID run) {
        return request("GET", "/review-runs/" + run, null, 65536);
    }

    public Response submit(ReviewExecution run) {
        // 首次接受为 202，同身份重试为 200；409 不创建新 runId。
        return request("POST", "/review-runs", run.requestBytes(), 65536);
    }

    public Response cancel(UUID run) {
        return request("POST", "/review-runs/" + run + "/cancel", null, 65536);
    }

    public Result result(UUID run) {
        Response response = request("GET", "/review-runs/" + run + "/result", null, 1024 * 1024);
        if (response.status() != 200) throw new ReviewException("REVIEW_RESULT_INTEGRITY_ERROR");
        return new Result(
                response.bytes(),
                response.headers().firstValue("X-Content-SHA256").orElse(""),
                response.headers().firstValue("ETag").orElse(""));
    }

    public byte[] evidenceMaterials(UUID run, UUID evidence) {
        Response response = request("GET", "/review-runs/" + run + "/evidence/" + evidence + "/materials", null, 1024 * 1024);
        if (response.status() != 200) throw new ReviewException("REVIEW_EVIDENCE_NOT_AVAILABLE");
        return response.bytes();
    }

    private HttpRequest.Builder builder(String path) {
        requireAvailable();
        return HttpRequest.newBuilder(base.resolve("/internal/v1" + path))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + credential);
    }

    private Response request(String method, String path, byte[] body, int limit) {
        if (body != null && body.length > 65536)
            throw new ReviewException("REVIEW_REQUEST_TOO_LARGE");
        boolean acquired = false;
        try {
            acquired = connections.tryAcquire(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
            var request =
                    builder(path)
                            .method(
                                    method,
                                    body == null
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofByteArray(body));
            if (body != null) request.header("Content-Type", "application/json; charset=utf-8");
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofPublisher());
            try (Chunks chunks = new Chunks(response.body())) {
                var raw = new ByteArrayOutputStream();
                long deadline = System.nanoTime() + requestTimeout.toNanos();
                byte[] bytes;
                while ((bytes = chunks.next(deadline, idleTimeout)) != null) {
                    if ((long) raw.size() + bytes.length > limit)
                        throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
                    raw.writeBytes(bytes);
                }
                return new Response(response.statusCode(), raw.toByteArray(), response.headers());
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
        } finally {
            if (acquired) connections.release();
        }
    }

    public void events(ReviewExecution run, Consumer<byte[]> consumer) {
        boolean acquired = false;
        try {
            acquired = connections.tryAcquire(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
            var request =
                    builder("/review-runs/" + run.runId() + "/events")
                            .header("Last-Event-ID", Long.toString(run.eventCursor()))
                            .GET()
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofPublisher());
            try (Chunks chunks = new Chunks(response.body())) {
                if (response.statusCode() != 200
                        || !response.headers()
                                .firstValue("Content-Type")
                                .orElse("")
                                .startsWith("text/event-stream"))
                    throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
                long deadline = System.nanoTime() + roundTimeout.toNanos();
                var line = new ByteArrayOutputStream();
                String id = null, event = null;
                var data = new ByteArrayOutputStream();
                int frameBytes = 0;
                byte[] chunk;
                while ((chunk = chunks.next(deadline, idleTimeout)) != null) {
                    for (byte value : chunk) {
                        if (++frameBytes > 65536)
                            throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
                        if (value != '\n') {
                            line.write(value);
                            continue;
                        }
                        String text = ReviewJson.utf8(line.toByteArray());
                        line.reset();
                        if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
                        if (text.isEmpty()) {
                            if (data.size() > 0) {
                                byte[] raw = data.toByteArray();
                                var parsed = ReviewProtocol.event(raw, run.runId());
                                if (!"review-run-event".equals(event)
                                        || id == null
                                        || !id.equals(
                                                Long.toString(parsed.path("sequence").asLong())))
                                    throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
                                consumer.accept(raw);
                                if (Set.of(
                                                "REVIEW_RUN_SUCCEEDED",
                                                "REVIEW_RUN_FAILED",
                                                "REVIEW_RUN_CANCELLED")
                                        .contains(parsed.path("eventType").asText())) return;
                            }
                            id = null;
                            event = null;
                            data.reset();
                            frameBytes = 0;
                        } else if (text.startsWith(":")) {
                            /* Non-persistent heartbeat. */
                        } else if (text.startsWith("id: ")) id = text.substring(4);
                        else if (text.startsWith("event: ")) event = text.substring(7);
                        else if (text.startsWith("data: ")) {
                            if (data.size() > 0) data.write('\n');
                            data.writeBytes(text.substring(6).getBytes(StandardCharsets.UTF_8));
                        } else throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
                    }
                }
                if (line.size() != 0 || data.size() != 0)
                    throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
        } finally {
            if (acquired) connections.release();
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    /** Flow demand is one chunk; body completion never requires an unbounded queue. */
    private static final class Chunks implements AutoCloseable, Flow.Subscriber<List<ByteBuffer>> {
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(1);
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean complete = new AtomicBoolean();
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        Chunks(Flow.Publisher<List<ByteBuffer>> body) {
            body.subscribe(this);
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription.set(value);
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            int size = items.stream().mapToInt(ByteBuffer::remaining).sum();
            if (size > 2 * 1024 * 1024) {
                error.set(new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR"));
                close();
                return;
            }
            byte[] raw = new byte[size];
            int offset = 0;
            for (ByteBuffer item : items) {
                int length = item.remaining();
                item.get(raw, offset, length);
                offset += length;
            }
            if (!queue.offer(raw)) {
                error.set(new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR"));
                close();
            }
        }

        @Override
        public void onError(Throwable value) {
            error.set(value);
            complete.set(true);
            queue.offer(new byte[0]);
        }

        @Override
        public void onComplete() {
            complete.set(true);
            queue.offer(new byte[0]);
        }

        byte[] next(long deadline, Duration idle) throws InterruptedException {
            while (true) {
                if (error.get() != null) throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
                if (complete.get() && queue.isEmpty()) return null;
                long remaining = Math.min(deadline - System.nanoTime(), idle.toNanos());
                if (remaining <= 0) throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
                byte[] value = queue.poll(remaining, TimeUnit.NANOSECONDS);
                if (value == null) throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
                Flow.Subscription current = subscription.get();
                if (current != null && !complete.get()) current.request(1);
                if (value.length > 0) return value;
            }
        }

        @Override
        public void close() {
            Flow.Subscription current = subscription.get();
            if (current != null) current.cancel();
        }
    }
}
