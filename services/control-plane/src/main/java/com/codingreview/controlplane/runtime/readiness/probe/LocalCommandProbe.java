package com.codingreview.controlplane.runtime.readiness.probe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 使用 ProcessBuilder 执行带超时和输出上限的本机只读命令。 */
public final class LocalCommandProbe implements CommandProbe {

    static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(1);

    /** 启动固定命令并收集标准输出与错误输出。 */
    @Override
    public CommandProbeResult execute(List<String> command, Duration timeout) {
        validate(command, timeout);
        final Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException | RuntimeException exception) {
            return CommandProbeResult.startFailed(exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BoundedOutput> outputFuture = executor.submit(
                    () -> readBounded(process.getInputStream())
            );
            boolean exited = process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!exited) {
                destroyProcessTree(process);
                BoundedOutput output = awaitOutput(outputFuture);
                return new CommandProbeResult(
                        true, true, null, output.text(), output.truncated(), "command timed out"
                );
            }
            BoundedOutput output = awaitOutput(outputFuture);
            return new CommandProbeResult(
                    true, false, process.exitValue(), output.text(), output.truncated(), null
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            return new CommandProbeResult(
                    true, true, null, "", false, "probe thread interrupted"
            );
        }
    }

    /** 校验命令和超时边界。 */
    private void validate(List<String> command, Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    /** 持续排空进程输出但只保留允许展示的前 64 KB。 */
    private BoundedOutput readBounded(InputStream stream) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(MAX_OUTPUT_BYTES);
        byte[] buffer = new byte[4096];
        boolean truncated = false;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            int remaining = MAX_OUTPUT_BYTES - captured.size();
            if (remaining > 0) {
                captured.write(buffer, 0, Math.min(remaining, read));
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        return new BoundedOutput(captured.toString(StandardCharsets.UTF_8), truncated);
    }

    /** 在进程结束后有界等待输出读取线程完成。 */
    private BoundedOutput awaitOutput(Future<BoundedOutput> future) {
        try {
            return future.get(TERMINATION_GRACE.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new BoundedOutput("", false);
        } catch (ExecutionException | TimeoutException exception) {
            future.cancel(true);
            return new BoundedOutput("", false);
        }
    }

    /** 按子进程优先顺序终止探测进程树。 */
    private void destroyProcessTree(Process process) {
        ProcessHandle parent = process.toHandle();
        List<ProcessHandle> descendants = descendantsPostOrder(parent);
        descendants.forEach(ProcessHandle::destroy);
        parent.destroy();
        awaitTermination(descendants, parent);

        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (parent.isAlive()) {
            parent.destroyForcibly();
        }
    }

    /** 按从最深子进程到直接子进程的顺序收集进程树。 */
    private List<ProcessHandle> descendantsPostOrder(ProcessHandle parent) {
        List<ProcessHandle> result = new ArrayList<>();
        collectDescendantsPostOrder(parent, result);
        return result;
    }

    /** 递归收集当前进程的所有后代进程。 */
    private void collectDescendantsPostOrder(ProcessHandle parent, List<ProcessHandle> result) {
        for (ProcessHandle child : parent.children().toList()) {
            collectDescendantsPostOrder(child, result);
            result.add(child);
        }
    }

    /** 在宽限期内等待整棵探测进程树退出。 */
    private void awaitTermination(List<ProcessHandle> descendants, ProcessHandle parent) {
        long deadlineNanos = System.nanoTime() + TERMINATION_GRACE.toNanos();
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

    /** 保存有界输出文本和截断标记。 */
    private record BoundedOutput(String text, boolean truncated) {
    }
}
