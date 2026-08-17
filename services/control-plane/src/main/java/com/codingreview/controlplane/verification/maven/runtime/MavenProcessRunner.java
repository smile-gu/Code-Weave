package com.codingreview.controlplane.verification.maven.runtime;

import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.verification.maven.model.MavenLogArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 启动 Maven、可靠采集两路原始日志并管理进程树生命周期。 */
public final class MavenProcessRunner {

    private static final Duration STREAM_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final long LIFECYCLE_POLL_MILLIS = 25;
    private static final int READ_BUFFER_BYTES = 16 * 1024;
    private static final int OUTPUT_EVENT_BYTES = 64 * 1024;
    private static final int OUTPUT_PREVIEW_BYTES = 4 * 1024;
    private static final long OUTPUT_EVENT_INTERVAL_NANOS = Duration.ofMillis(250).toNanos();

    private final ObjectMapper objectMapper;

    public MavenProcessRunner(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    /** 启动 Maven 进程并汇总生命周期、日志与退出状态。 */
    public MavenProcessResult run(MavenProcessSpec spec, MavenProcessEventSink eventSink) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(eventSink, "eventSink");
        Instant startedAt = Instant.now();

        try {
            Files.createDirectories(spec.artifactDirectory());
        } catch (IOException | RuntimeException exception) {
            return result(MavenProcessStatus.LOG_CAPTURE_FAILED, null, startedAt, null, null,
                    "Unable to create Maven artifact directory: " + exception.getMessage());
        }

        publish(eventSink, RunEventType.PROCESS_START_REQUESTED,
                objectMapper.createObjectNode()
                        .set("command", objectMapper.valueToTree(spec.command())));

        final Process process;
        try {
            process = new ProcessBuilder(spec.command())
                    .directory(spec.workingDirectory().toFile())
                    .start();
        } catch (IOException | RuntimeException exception) {
            return result(MavenProcessStatus.START_FAILED, null, startedAt, null, null,
                    "Unable to start Maven: " + exception.getMessage());
        }

        try {
            publish(eventSink, RunEventType.PROCESS_STARTED,
                    objectMapper.createObjectNode().put("pid", process.pid()));
            process.getOutputStream().close();
        } catch (IOException exception) {
            terminateWithEvents(process, spec.cancellationGracePeriod(), "STDIN_CLOSE_FAILED", eventSink);
            return result(MavenProcessStatus.LOG_CAPTURE_FAILED, waitForExit(process),
                    startedAt, null, null, "Unable to close Maven stdin: " + exception.getMessage());
        } catch (RuntimeException exception) {
            destroyProcessTree(process, spec.cancellationGracePeriod());
            throw exception;
        }

        AtomicLong combinedBytes = new AtomicLong();
        AtomicReference<CaptureFailure> captureFailure = new AtomicReference<>();
        ExecutorService streamExecutor = Executors.newFixedThreadPool(2);
        Future<MavenLogArtifact> stdoutFuture = streamExecutor.submit(() -> capture(
                process.getInputStream(), "stdout", RunEventType.PROCESS_STDOUT,
                spec.artifactDirectory().resolve("stdout.log"), spec,
                combinedBytes, captureFailure, eventSink
        ));
        Future<MavenLogArtifact> stderrFuture = streamExecutor.submit(() -> capture(
                process.getErrorStream(), "stderr", RunEventType.PROCESS_STDERR,
                spec.artifactDirectory().resolve("stderr.log"), spec,
                combinedBytes, captureFailure, eventSink
        ));

        MavenProcessStatus forcedStatus = awaitLifecycle(process, spec, captureFailure);
        CaptureFailure failure = captureFailure.get();
        if (process.isAlive()) {
            String reason = failure != null ? failure.status().name()
                    : forcedStatus == null ? "PROCESS_CLEANUP" : forcedStatus.name();
            if (failure != null && failure.eventPublishFailure()) {
                destroyProcessTree(process, spec.cancellationGracePeriod());
            } else {
                terminateWithEvents(process, spec.cancellationGracePeriod(), reason, eventSink);
            }
        }

        Integer exitCode = waitForExit(process);
        CapturedLogs logs = awaitLogs(stdoutFuture, stderrFuture, captureFailure);
        streamExecutor.shutdownNow();
        failure = captureFailure.get();

        if (failure != null && failure.eventPublishFailure()) {
            throw failure.runtimeCause();
        }

        publish(eventSink, RunEventType.PROCESS_EXIT_OBSERVED,
                nullableIntegerPayload("exitCode", exitCode));

        MavenProcessStatus status = failure != null
                ? failure.status()
                : forcedStatus == null ? MavenProcessStatus.COMPLETED : forcedStatus;
        if (logs.complete()) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("stdout", objectMapper.valueToTree(logs.stdout()));
            payload.set("stderr", objectMapper.valueToTree(logs.stderr()));
            publish(eventSink, RunEventType.LOG_CAPTURE_COMPLETED, payload);
        }

        String diagnostic = failure != null
                ? failure.cause().getMessage()
                : logs.diagnostic();
        return result(status, exitCode, startedAt, logs.stdout(), logs.stderr(), diagnostic);
    }

    /** 持续采集单个输出流并生成可校验的日志产物。 */
    private MavenLogArtifact capture(
            InputStream inputStream,
            String stream,
            RunEventType eventType,
            Path logFile,
            MavenProcessSpec spec,
            AtomicLong combinedBytes,
            AtomicReference<CaptureFailure> failure,
            MavenProcessEventSink eventSink
    ) {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long bytes = 0;
        long lines = 0;
        int lastByte = -1;
        OutputEventBuffer outputEvents = new OutputEventBuffer(stream, eventType, eventSink);

        try (inputStream;
             FileChannel channel = FileChannel.open(logFile,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {
            try {
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    reserveLogBytes(combinedBytes, read, spec.maxCombinedLogBytes());
                    writeFully(channel, buffer, read);
                    digest.update(buffer, 0, read);
                    for (int index = 0; index < read; index++) {
                        if (buffer[index] == '\n') {
                            lines++;
                        }
                    }
                    lastByte = buffer[read - 1] & 0xff;
                    bytes += read;
                    outputEvents.accept(buffer, read, bytes);
                }
                outputEvents.flush(bytes);
            } finally {
                channel.force(true);
            }
            if (bytes > 0 && lastByte != '\n') {
                lines++;
            }
            return new MavenLogArtifact(relativePath(spec.runtimeRoot(), logFile), bytes, lines,
                    HexFormat.of().formatHex(digest.digest()));
        } catch (LogLimitExceededException exception) {
            failure.compareAndSet(null,
                    new CaptureFailure(MavenProcessStatus.LOG_LIMIT_EXCEEDED, exception, false));
            throw exception;
        } catch (EventPublishException exception) {
            failure.compareAndSet(null,
                    new CaptureFailure(MavenProcessStatus.LOG_CAPTURE_FAILED, exception.getCause(), true));
            throw exception;
        } catch (IOException | RuntimeException exception) {
            failure.compareAndSet(null,
                    new CaptureFailure(MavenProcessStatus.LOG_CAPTURE_FAILED, exception, false));
            throw new LogCaptureException(exception);
        }
    }

    /** 等待 Maven 进程结束并识别超时、中断或日志采集失败。 */
    private MavenProcessStatus awaitLifecycle(
            Process process,
            MavenProcessSpec spec,
            AtomicReference<CaptureFailure> captureFailure
    ) {
        long deadlineNanos = System.nanoTime() + spec.timeout().toNanos();
        try {
            while (process.isAlive()) {
                if (captureFailure.get() != null) {
                    return captureFailure.get().status();
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return MavenProcessStatus.TIMED_OUT;
                }
                process.waitFor(
                        Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(LIFECYCLE_POLL_MILLIS)),
                        TimeUnit.NANOSECONDS
                );
            }
            return captureFailure.get() == null ? null : captureFailure.get().status();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return MavenProcessStatus.CANCELLED;
        }
    }

    /** 等待标准输出与标准错误的采集任务结束。 */
    private CapturedLogs awaitLogs(
            Future<MavenLogArtifact> stdoutFuture,
            Future<MavenLogArtifact> stderrFuture,
            AtomicReference<CaptureFailure> failure
    ) {
        MavenLogArtifact stdout = awaitLog(stdoutFuture, "stdout", failure);
        MavenLogArtifact stderr = awaitLog(stderrFuture, "stderr", failure);
        String diagnostic = failure.get() == null ? null : failure.get().cause().getMessage();
        return new CapturedLogs(stdout, stderr, diagnostic);
    }

    /** 等待单个日志采集任务并统一记录异步失败。 */
    private MavenLogArtifact awaitLog(
            Future<MavenLogArtifact> future,
            String stream,
            AtomicReference<CaptureFailure> failure
    ) {
        try {
            return future.get(STREAM_SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, new CaptureFailure(
                    MavenProcessStatus.CANCELLED,
                    new IllegalStateException("Interrupted while awaiting Maven " + stream + " log", exception),
                    false
            ));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            failure.compareAndSet(null, new CaptureFailure(
                    MavenProcessStatus.LOG_CAPTURE_FAILED,
                    cause == null ? exception : cause,
                    false
            ));
        } catch (TimeoutException exception) {
            failure.compareAndSet(null, new CaptureFailure(
                    MavenProcessStatus.LOG_CAPTURE_FAILED,
                    new IllegalStateException("Timed out while awaiting Maven " + stream + " log", exception),
                    false
            ));
        }
        return null;
    }

    /** 原子预留日志容量并阻止总日志超过安全上限。 */
    private void reserveLogBytes(AtomicLong combinedBytes, int requested, long limit) {
        while (true) {
            long current = combinedBytes.get();
            if (requested > limit - current) {
                throw new LogLimitExceededException(
                        "Maven stdout and stderr exceeded " + limit + " bytes"
                );
            }
            if (combinedBytes.compareAndSet(current, current + requested)) {
                return;
            }
        }
    }

    /** 在记录终止事件的同时清理 Maven 进程树。 */
    private void terminateWithEvents(
            Process process,
            Duration gracePeriod,
            String reason,
            MavenProcessEventSink eventSink
    ) {
        try {
            publish(eventSink, RunEventType.PROCESS_TERMINATION_REQUESTED,
                    objectMapper.createObjectNode().put("reason", reason));
        } catch (RuntimeException exception) {
            destroyProcessTree(process, gracePeriod);
            throw exception;
        }
        destroyProcessTree(process, gracePeriod);
        publish(eventSink, RunEventType.PROCESS_TERMINATED,
                objectMapper.createObjectNode().put("reason", reason));
    }

    /** 先终止最深层子进程，最后终止父进程；宽限期后强制清理仍存活的进程。 */
    private void destroyProcessTree(Process process, Duration gracePeriod) {
        ProcessHandle parent = process.toHandle();
        List<ProcessHandle> descendants = descendantsPostOrder(parent);
        descendants.forEach(this::destroyQuietly);
        destroyQuietly(parent);
        awaitTermination(descendants, parent, gracePeriod);

        descendants.stream().filter(ProcessHandle::isAlive).forEach(this::destroyForciblyQuietly);
        if (parent.isAlive()) {
            destroyForciblyQuietly(parent);
        }
        awaitTermination(descendants, parent, STREAM_SHUTDOWN_TIMEOUT);
    }

    /** 按从最深子进程到父进程的顺序收集后代进程。 */
    private List<ProcessHandle> descendantsPostOrder(ProcessHandle parent) {
        List<ProcessHandle> result = new ArrayList<>();
        collectDescendantsPostOrder(parent, result);
        return result;
    }

    /** 递归收集当前进程的后代进程。 */
    private void collectDescendantsPostOrder(ProcessHandle parent, List<ProcessHandle> result) {
        for (ProcessHandle child : parent.children().toList()) {
            collectDescendantsPostOrder(child, result);
            result.add(child);
        }
    }

    /** 在指定时限内等待整棵进程树终止。 */
    private void awaitTermination(List<ProcessHandle> descendants, ProcessHandle parent, Duration timeout) {
        long deadlineNanos = System.nanoTime() + Math.max(0, timeout.toNanos());
        while (parent.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            if (System.nanoTime() >= deadlineNanos) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 尝试温和终止进程并忽略清理阶段异常。 */
    private void destroyQuietly(ProcessHandle processHandle) {
        try {
            processHandle.destroy();
        } catch (RuntimeException ignored) {
            // 强制清理阶段会再次处理仍存活的进程。
        }
    }

    /** 尝试强制终止进程并忽略最终清理异常。 */
    private void destroyForciblyQuietly(ProcessHandle processHandle) {
        try {
            processHandle.destroyForcibly();
        } catch (RuntimeException ignored) {
            // 最终进程状态仍会通过结果诊断暴露给上层。
        }
    }

    /** 等待 Maven 进程退出并返回可用的退出码。 */
    private Integer waitForExit(Process process) {
        try {
            if (process.isAlive()) {
                process.waitFor(STREAM_SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process, Duration.ZERO);
        }
        return process.isAlive() ? null : process.exitValue();
    }

    /** 将本次读取的日志字节完整写入文件通道。 */
    private void writeFully(FileChannel channel, byte[] bytes, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** 将日志文件转换为运行时根目录内的相对路径。 */
    private String relativePath(Path runtimeRoot, Path file) {
        Path normalizedRoot = runtimeRoot.normalize();
        Path normalizedFile = file.normalize();
        if (!normalizedFile.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Maven artifact resolves outside runtime root");
        }
        return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
    }

    /** 创建用于校验日志完整性的 SHA-256 摘要器。 */
    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** 构造允许整数值为空的事件载荷。 */
    private ObjectNode nullableIntegerPayload(String field, Integer value) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (value == null) {
            payload.putNull(field);
        } else {
            payload.put(field, value);
        }
        return payload;
    }

    /** 将一条 Maven 进程事件发布给上层事件接收器。 */
    private void publish(MavenProcessEventSink sink, RunEventType type, ObjectNode payload) {
        sink.onEvent(type, payload);
    }

    /** 根据当前证据构造 Maven 进程结果。 */
    private MavenProcessResult result(
            MavenProcessStatus status,
            Integer exitCode,
            Instant startedAt,
            MavenLogArtifact stdout,
            MavenLogArtifact stderr,
            String diagnostic
    ) {
        return new MavenProcessResult(
                status, exitCode, startedAt, Instant.now(), stdout, stderr, diagnostic
        );
    }

    private final class OutputEventBuffer {
        private final String stream;
        private final RunEventType eventType;
        private final MavenProcessEventSink eventSink;
        private final ByteArrayOutputStream preview = new ByteArrayOutputStream(OUTPUT_PREVIEW_BYTES);
        private long pendingBytes;
        private long lastPublishedNanos;

        private OutputEventBuffer(
                String stream,
                RunEventType eventType,
                MavenProcessEventSink eventSink
        ) {
            this.stream = stream;
            this.eventType = eventType;
            this.eventSink = eventSink;
        }

        /** 接收日志片段并在达到阈值时发布输出事件。 */
        private void accept(byte[] bytes, int length, long streamBytes) {
            int previewRemaining = OUTPUT_PREVIEW_BYTES - preview.size();
            if (previewRemaining > 0) {
                preview.write(bytes, 0, Math.min(length, previewRemaining));
            }
            pendingBytes += length;
            long now = System.nanoTime();
            if (lastPublishedNanos == 0
                    || pendingBytes >= OUTPUT_EVENT_BYTES
                    || now - lastPublishedNanos >= OUTPUT_EVENT_INTERVAL_NANOS) {
                publish(streamBytes);
                lastPublishedNanos = now;
            }
        }

        /** 发布缓冲区中尚未上报的日志片段。 */
        private void flush(long streamBytes) {
            if (pendingBytes > 0) {
                publish(streamBytes);
            }
        }

        /** 生成包含字节区间和预览内容的日志输出事件。 */
        private void publish(long streamBytes) {
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("stream", stream)
                    .put("fromByte", streamBytes - pendingBytes)
                    .put("toByte", streamBytes)
                    .put("bytes", pendingBytes)
                    .put("preview", preview.toString(StandardCharsets.UTF_8))
                    .put("previewTruncated", pendingBytes > preview.size());
            try {
                MavenProcessRunner.this.publish(eventSink, eventType, payload);
            } catch (RuntimeException exception) {
                throw new EventPublishException(exception);
            }
            pendingBytes = 0;
            preview.reset();
        }
    }

    private record CapturedLogs(
            MavenLogArtifact stdout,
            MavenLogArtifact stderr,
            String diagnostic
    ) {
        /** 判断标准输出和标准错误是否都已形成日志产物。 */
        private boolean complete() {
            return stdout != null && stderr != null;
        }
    }

    private record CaptureFailure(
            MavenProcessStatus status,
            Throwable cause,
            boolean eventPublishFailure
    ) {
        /** 将采集失败原因统一转换为可抛出的运行时异常。 */
        private RuntimeException runtimeCause() {
            if (cause instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            return new IllegalStateException(cause);
        }
    }

    private static final class LogLimitExceededException extends RuntimeException {
        private LogLimitExceededException(String message) {
            super(message);
        }
    }

    private static final class EventPublishException extends RuntimeException {
        private EventPublishException(RuntimeException cause) {
            super(cause);
        }
    }

    private static final class LogCaptureException extends RuntimeException {
        private LogCaptureException(Throwable cause) {
            super(cause);
        }
    }
}
