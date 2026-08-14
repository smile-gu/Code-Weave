package com.codingreview.controlplane.adapter.claude;

import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 使用 ProcessBuilder 执行 Claude Code，并将 stdout 事件实时转发给调用方。 */
public final class ClaudeProcessRunnerImpl implements ClaudeProcessRunner {

    private static final Duration STREAM_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final long LIFECYCLE_POLL_MILLIS = 25;

    private final ClaudeStreamEventMapper eventMapper;

    public ClaudeProcessRunnerImpl(ObjectMapper objectMapper) {
        this(new ClaudeStreamEventMapper(objectMapper));
    }

    public ClaudeProcessRunnerImpl(ClaudeStreamEventMapper eventMapper) {
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    }

    @Override
    public ClaudeRunResult run(ClaudeLaunchSpec launchSpec, AgentEventSink eventSink) {
        Objects.requireNonNull(launchSpec, "launchSpec");
        Objects.requireNonNull(eventSink, "eventSink");

        Instant startedAt = Instant.now();
        final Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(launchSpec.command())
                    .directory(launchSpec.workingDirectory().toFile());
            process = processBuilder.start();
        } catch (IOException | RuntimeException exception) {
            return result(ClaudeRunStatus.START_FAILED, null, startedAt, null, exception.getMessage());
        }

        RunState state = new RunState();
        ExecutorService streamExecutor = Executors.newFixedThreadPool(2);
        Future<?> stdoutFuture = streamExecutor.submit(() -> readStdout(process, state, eventSink));
        Future<String> stderrFuture = streamExecutor.submit(() -> readStderr(process.getErrorStream()));
        writePrompt(process, state, launchSpec.standardInput());
        ClaudeRunStatus forcedStatus = awaitLifecycle(process, state, launchSpec);
        Integer exitCode = waitForExit(process);
        String stderr = awaitStderr(stderrFuture);
        awaitStream(stdoutFuture);
        streamExecutor.shutdownNow();
        String diagnostic = joinDiagnostics(state.failureMessage, stderr);

        ClaudeRunStatus status = state.failureStatus != null
                ? state.failureStatus
                : forcedStatus != null ? forcedStatus : determineStatus(state, exitCode);
        Instant endedAt = Instant.now();
        return new ClaudeRunResult(
                status,
                exitCode,
                state.initialized,
                state.terminalEvent != null,
                state.terminalEvent,
                startedAt,
                endedAt,
                diagnostic
        );
    }

    private void writePrompt(Process process, RunState state, String prompt) {
        try {
            process.getOutputStream().write(prompt.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            process.getOutputStream().close();
        } catch (IOException exception) {
            state.fail(ClaudeRunStatus.PROCESS_FAILED,
                    "Failed to write Claude prompt: " + exception.getMessage());
        }
    }

    private ClaudeRunStatus awaitLifecycle(Process process, RunState state, ClaudeLaunchSpec spec) {
        try {
            state.initializedLatch.await(
                    spec.startupTimeout().toNanos(),
                    TimeUnit.NANOSECONDS
            );

            if (state.failureStatus != null) {
                destroyProcessTree(process, spec.cancellationGracePeriod());
                return state.failureStatus;
            }
            if (!state.initialized && process.isAlive()) {
                destroyProcessTree(process, spec.cancellationGracePeriod());
                return ClaudeRunStatus.START_TIMEOUT;
            }
            if (!state.initialized) {
                return null;
            }

            long deadlineNanos = state.initializedAtNanos + spec.taskTimeout().toNanos();
            while (process.isAlive()) {
                if (state.failureStatus != null) {
                    destroyProcessTree(process, spec.cancellationGracePeriod());
                    return state.failureStatus;
                }

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    destroyProcessTree(process, spec.cancellationGracePeriod());
                    return ClaudeRunStatus.TIMED_OUT;
                }
                long pollNanos = Math.min(remainingNanos,
                        TimeUnit.MILLISECONDS.toNanos(LIFECYCLE_POLL_MILLIS));
                process.waitFor(pollNanos, TimeUnit.NANOSECONDS);
            }
            return state.failureStatus;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process, spec.cancellationGracePeriod());
            return ClaudeRunStatus.CANCELLED;
        }
    }

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

    private List<ProcessHandle> descendantsPostOrder(ProcessHandle parent) {
        List<ProcessHandle> result = new ArrayList<>();
        collectDescendantsPostOrder(parent, result);
        return result;
    }

    private void collectDescendantsPostOrder(ProcessHandle parent, List<ProcessHandle> result) {
        List<ProcessHandle> children = parent.children().toList();
        for (ProcessHandle child : children) {
            collectDescendantsPostOrder(child, result);
            result.add(child);
        }
    }

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

    private void destroyQuietly(ProcessHandle processHandle) {
        try {
            processHandle.destroy();
        } catch (RuntimeException ignored) {
            // 强制清理阶段会再次处理仍存活的进程。
        }
    }

    private void destroyForciblyQuietly(ProcessHandle processHandle) {
        try {
            processHandle.destroyForcibly();
        } catch (RuntimeException ignored) {
            // 最终状态仍会通过 exitCode 和 diagnostic 暴露给上层。
        }
    }

    private void readStdout(Process process, RunState state, AgentEventSink eventSink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (AgentEvent event : eventMapper.mapLine(line)) {
                    if (event.type() == AgentEventType.RUNTIME_INITIALIZED) {
                        state.initialized = true;
                        state.initializedAtNanos = System.nanoTime();
                        state.initializedLatch.countDown();
                    }
                    if (event.type() == AgentEventType.RUNTIME_COMPLETED
                            || event.type() == AgentEventType.RUNTIME_FAILED) {
                        state.terminalEvent = event;
                    }
                    try {
                        eventSink.onEvent(event);
                    } catch (RuntimeException exception) {
                        state.fail(ClaudeRunStatus.EVENT_PUBLISH_FAILED,
                                "Agent event sink failed: " + exception.getMessage());
                        return;
                    }
                }
            }
        } catch (ClaudeStreamProtocolException exception) {
            state.fail(ClaudeRunStatus.PROTOCOL_ERROR, exception.getMessage());
        } catch (IOException | RuntimeException exception) {
            state.fail(ClaudeRunStatus.PROCESS_FAILED,
                    "Failed to read Claude stdout: " + exception.getMessage());
        } finally {
            // 即使进程未产生 init 就退出，也要立即结束启动等待。
            state.initializedLatch.countDown();
        }
    }

    private String readStderr(InputStream errorStream) throws IOException {
        return new String(errorStream.readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    private String awaitStderr(Future<String> stderrFuture) {
        try {
            return stderrFuture.get(STREAM_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Interrupted while reading Claude stderr";
        } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
            return "Unable to read Claude stderr: " + exception.getMessage();
        }
    }

    private void awaitStream(Future<?> stdoutFuture) {
        try {
            stdoutFuture.get(STREAM_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // 进程已结束，读取线程的异常已记录在 RunState 中。
        }
    }

    private ClaudeRunStatus determineStatus(RunState state, Integer exitCode) {
        if (state.failureStatus != null) {
            return state.failureStatus;
        }
        if (state.terminalEvent != null) {
            if (state.terminalEvent.type() == AgentEventType.RUNTIME_FAILED) {
                return ClaudeRunStatus.AGENT_FAILED;
            }
            return Integer.valueOf(0).equals(exitCode)
                    ? ClaudeRunStatus.COMPLETED
                    : ClaudeRunStatus.PROCESS_FAILED;
        }
        return Integer.valueOf(0).equals(exitCode)
                ? ClaudeRunStatus.INCOMPLETE_STREAM
                : ClaudeRunStatus.PROCESS_FAILED;
    }

    private ClaudeRunResult result(ClaudeRunStatus status, Integer exitCode, Instant startedAt,
                                   AgentEvent terminalEvent, String diagnostic) {
        Instant endedAt = Instant.now();
        return new ClaudeRunResult(status, exitCode, false, terminalEvent != null, terminalEvent,
                startedAt, endedAt, diagnostic);
    }

    private String joinDiagnostics(String... values) {
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
    }

    private static final class RunState {
        private final CountDownLatch initializedLatch = new CountDownLatch(1);
        private volatile boolean initialized;
        private volatile long initializedAtNanos;
        private volatile AgentEvent terminalEvent;
        private volatile ClaudeRunStatus failureStatus;
        private volatile String failureMessage;

        private synchronized void fail(ClaudeRunStatus status, String message) {
            if (failureStatus == null) {
                failureStatus = status;
                failureMessage = message;
            }
            initializedLatch.countDown();
        }
    }
}
