package com.codingreview.controlplane.adapter.claude.runtime;

import com.codingreview.controlplane.adapter.claude.invocation.ClaudeLaunchSpec;
import com.codingreview.controlplane.adapter.claude.protocol.ClaudeStreamEventMapper;
import com.codingreview.controlplane.adapter.claude.protocol.ClaudeStreamProtocolException;
import com.codingreview.controlplane.event.AgentEvent;
import com.codingreview.controlplane.event.AgentEventType;
import com.codingreview.controlplane.runtime.process.ProcessTreeCleaner;
import com.codingreview.controlplane.runtime.process.RunProcessResources;
import com.codingreview.controlplane.runtime.process.ProcessCleanupException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
    private final ClaudeRuntimePolicyValidator policyValidator;
    private final ProcessTreeCleaner processTreeCleaner;

    public ClaudeProcessRunnerImpl(ObjectMapper objectMapper) {
        this(new ClaudeStreamEventMapper(objectMapper));
    }

    public ClaudeProcessRunnerImpl(ClaudeStreamEventMapper eventMapper) {
        this(eventMapper, new ProcessTreeCleaner());
    }

    /** 使用可替换的进程树清理器装配运行器，供可靠性测试注入失败。 */
    ClaudeProcessRunnerImpl(
            ClaudeStreamEventMapper eventMapper,
            ProcessTreeCleaner processTreeCleaner
    ) {
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.policyValidator = new ClaudeRuntimePolicyValidator();
        this.processTreeCleaner = Objects.requireNonNull(processTreeCleaner, "processTreeCleaner");
    }

    /** 启动 Claude Code 进程并汇总流式事件与最终状态。 */
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

        try (var resources = new RunProcessResources(process, processTreeCleaner,
                launchSpec.cancellationGracePeriod(), STREAM_SHUTDOWN_TIMEOUT)) {
        RunState state = new RunState();
        ExecutorService streamExecutor = Executors.newFixedThreadPool(2);
        resources.readers(streamExecutor);
        Future<?> stdoutFuture = streamExecutor.submit(() -> readStdout(
                process, state, launchSpec, eventSink
        ));
        Future<String> stderrFuture = streamExecutor.submit(() -> readStderr(process.getErrorStream()));
        writePrompt(process, state, launchSpec.standardInput());
        ClaudeRunStatus forcedStatus = awaitLifecycle(process, state, launchSpec);
        boolean restoreInterrupt = Thread.interrupted();
        try {
            if (!resources.finish()) forcedStatus = ClaudeRunStatus.PROCESS_CLEANUP_FAILED;
            Integer exitCode = waitForExit(process);
            String stderr = awaitStderr(stderrFuture);
            awaitStream(stdoutFuture);
            streamExecutor.shutdownNow();
            String diagnostic = joinDiagnostics(state.failureMessage, stderr);

            ClaudeRunStatus status = state.failureStatus != null
                    && forcedStatus != ClaudeRunStatus.PROCESS_CLEANUP_FAILED
                    ? state.failureStatus
                    : forcedStatus != null ? forcedStatus : determineStatus(state, exitCode);
            Instant endedAt = Instant.now();
            return new ClaudeRunResult(
                    status,
                    exitCode,
                    state.initialized,
                    state.nativeSessionId,
                    state.terminalEvent != null,
                    state.terminalEvent,
                    startedAt,
                    endedAt,
                    diagnostic
            );
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
        } catch (RuntimeException failure) {
            for (Throwable suppressed : failure.getSuppressed()) {
                if (suppressed instanceof ProcessCleanupException) {
                    throw new ProcessCleanupException("Claude 异常退出且资源未释放", failure);
                }
            }
            throw failure;
        }
    }

    /** 将任务 Prompt 写入进程标准输入并关闭输入流。 */
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

    /** 等待初始化和任务执行，并处理超时、失败与中断。 */
    private ClaudeRunStatus awaitLifecycle(Process process, RunState state, ClaudeLaunchSpec spec) {
        try {
            state.initializedLatch.await(
                    spec.startupTimeout().toNanos(),
                    TimeUnit.NANOSECONDS
            );

            if (state.failureStatus != null) {
                return destroyProcessTree(process, spec.cancellationGracePeriod())
                        ? state.failureStatus : ClaudeRunStatus.PROCESS_CLEANUP_FAILED;
            }
            if (!state.initialized && process.isAlive()) {
                return destroyProcessTree(process, spec.cancellationGracePeriod())
                        ? ClaudeRunStatus.START_TIMEOUT : ClaudeRunStatus.PROCESS_CLEANUP_FAILED;
            }
            if (!state.initialized) {
                return null;
            }

            long deadlineNanos = state.initializedAtNanos + spec.taskTimeout().toNanos();
            while (process.isAlive()) {
                if (state.failureStatus != null) {
                    return destroyProcessTree(process, spec.cancellationGracePeriod())
                            ? state.failureStatus : ClaudeRunStatus.PROCESS_CLEANUP_FAILED;
                }

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return destroyProcessTree(process, spec.cancellationGracePeriod())
                            ? ClaudeRunStatus.TIMED_OUT : ClaudeRunStatus.PROCESS_CLEANUP_FAILED;
                }
                long pollNanos = Math.min(remainingNanos,
                        TimeUnit.MILLISECONDS.toNanos(LIFECYCLE_POLL_MILLIS));
                process.waitFor(pollNanos, TimeUnit.NANOSECONDS);
            }
            return state.failureStatus;
        } catch (InterruptedException exception) {
            boolean cleaned = destroyProcessTree(process, spec.cancellationGracePeriod());
            Thread.currentThread().interrupt();
            return cleaned ? ClaudeRunStatus.CANCELLED : ClaudeRunStatus.PROCESS_CLEANUP_FAILED;
        }
    }

    /** 等待进程退出并返回可用的退出码。 */
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
    private boolean destroyProcessTree(Process process, Duration gracePeriod) {
        return processTreeCleaner.clean(process, gracePeriod, STREAM_SHUTDOWN_TIMEOUT);
    }

    /** 持续解析标准输出并实时发布通过策略校验的 Agent 事件。 */
    private void readStdout(
            Process process,
            RunState state,
            ClaudeLaunchSpec launchSpec,
            AgentEventSink eventSink
    ) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (AgentEvent event : eventMapper.mapLine(line)) {
                    if (event.type() == AgentEventType.RUNTIME_INITIALIZED) {
                        state.initialized = true;
                        state.nativeSessionId = event.externalId();
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
                    var violation = policyValidator.validate(event, launchSpec);
                    if (violation.isPresent()) {
                        state.fail(violation.get().status(), violation.get().diagnostic());
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

    /** 读取并整理 Claude 进程的标准错误输出。 */
    private String readStderr(InputStream errorStream) throws IOException {
        return new String(errorStream.readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    /** 等待标准错误读取任务完成并返回诊断文本。 */
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

    /** 等待标准输出读取任务完成或达到关闭时限。 */
    private void awaitStream(Future<?> stdoutFuture) {
        try {
            stdoutFuture.get(STREAM_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // 进程已结束，读取线程的异常已记录在 RunState 中。
        }
    }

    /** 根据协议终态和退出码判定最终运行状态。 */
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

    /** 构造未进入正常流式阶段的运行结果。 */
    private ClaudeRunResult result(ClaudeRunStatus status, Integer exitCode, Instant startedAt,
                                   AgentEvent terminalEvent, String diagnostic) {
        Instant endedAt = Instant.now();
        return new ClaudeRunResult(status, exitCode, false, null, terminalEvent != null, terminalEvent,
                startedAt, endedAt, diagnostic);
    }

    /** 合并所有非空诊断信息。 */
    private String joinDiagnostics(String... values) {
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
    }

    private static final class RunState {
        private final CountDownLatch initializedLatch = new CountDownLatch(1);
        private volatile boolean initialized;
        private volatile String nativeSessionId;
        private volatile long initializedAtNanos;
        private volatile AgentEvent terminalEvent;
        private volatile ClaudeRunStatus failureStatus;
        private volatile String failureMessage;

        /** 原子记录首次运行失败并结束初始化等待。 */
        private synchronized void fail(ClaudeRunStatus status, String message) {
            if (failureStatus == null) {
                failureStatus = status;
                failureMessage = message;
            }
            initializedLatch.countDown();
        }
    }
}
