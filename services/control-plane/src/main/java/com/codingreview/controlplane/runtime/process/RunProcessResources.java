package com.codingreview.controlplane.runtime.process;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** 为一次进程调用保留后代句柄，并在所有退出路径确认进程和读取线程已释放。 */
public final class RunProcessResources implements AutoCloseable {
    private final Process process;
    private final ProcessTreeCleaner cleaner;
    private final Duration grace;
    private final Duration timeout;
    private final AutoCloseable tracking;
    private ExecutorService readers;
    private boolean finished;

    /** 进程启动后立即开始追踪本轮后代。 */
    public RunProcessResources(Process process, ProcessTreeCleaner cleaner, Duration grace, Duration timeout) {
        this.process = process;
        this.cleaner = cleaner;
        this.grace = grace;
        this.timeout = timeout;
        this.tracking = cleaner.track(process);
    }

    /** 登记本轮输出消费线程池以参与最终资源确认。 */
    public void readers(ExecutorService executor) { readers = executor; }

    /** 有界清理全部已知进程与读取任务，返回可判断的释放结果。 */
    public boolean finish() {
        boolean interrupted = Thread.interrupted();
        boolean clean = true;
        try {
            try { clean = cleaner.clean(process, grace, timeout); }
            catch (RuntimeException failure) { clean = false; }
            interrupted |= Thread.interrupted();
            if (readers != null) {
                readers.shutdown();
                long deadline = System.nanoTime() + timeout.toNanos();
                while (!readers.isTerminated() && System.nanoTime() < deadline) {
                    try { readers.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (InterruptedException failure) { interrupted = true; }
                }
                if (!readers.isTerminated()) {
                    readers.shutdownNow();
                    clean = false;
                }
            }
            try { if (tracking != null) tracking.close(); }
            catch (Exception failure) { clean = false; }
            return clean;
        } finally {
            finished = true;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** 异常退出时也完成资源收敛，清理风险通过专用异常传给上层。 */
    @Override public void close() {
        if (!finished && !finish()) throw new ProcessCleanupException("本轮进程或读取任务未能确认释放", null);
    }
}
