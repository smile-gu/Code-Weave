package com.codingreview.controlplane.runtime.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 以先子后父顺序清理进程树，并返回全部已知进程是否确实退出。 */
public class ProcessTreeCleaner {
    private final java.util.concurrent.ConcurrentHashMap<Process, java.util.Set<ProcessHandle>> tracked =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Process> uncertain = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 执行期间持续保留已知后代句柄，父进程退出后仍可确认它们的状态。 */
    public AutoCloseable track(Process process) {
        var known = java.util.concurrent.ConcurrentHashMap.<ProcessHandle>newKeySet();
        tracked.put(process, known);
        var observer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "process-descendant-observer");
            thread.setDaemon(true);
            return thread;
        });
        observer.scheduleWithFixedDelay(() -> {
            try {
                observe(process);
            } catch (RuntimeException ignored) {
                uncertain.add(process);
            }
        }, 0, 5, java.util.concurrent.TimeUnit.MILLISECONDS);
        return () -> {
            observer.shutdownNow();
            try {
                if (!observer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new ProcessCleanupException("进程追踪任务未退出", null);
                }
            } finally { tracked.remove(process); uncertain.remove(process); }
        };
    }

    /** 补充当前可见后代，保留父进程退出前已经观察到的身份。 */
    public void observe(Process process) {
        var known = tracked.get(process);
        if (known == null) return;
        process.toHandle().descendants().forEach(known::add);
        List.copyOf(known).forEach(handle -> handle.descendants().forEach(known::add));
    }

    /** 先温和终止、再强制终止，并在清理完成后恢复调用线程的中断标志。 */
    public boolean clean(Process process, Duration gracePeriod, Duration forcedTimeout) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            ProcessHandle parent = process.toHandle();
            List<ProcessHandle> descendants = descendantsPostOrder(parent);
            for (ProcessHandle known : tracked.getOrDefault(process, java.util.Set.of())) {
                if (!descendants.contains(known)) descendants.add(0, known);
            }
            descendants.sort(java.util.Comparator.comparingInt(this::depth).reversed());
            descendants.forEach(this::destroyQuietly);
            destroyQuietly(parent);
            restoreInterrupt |= awaitTermination(descendants, parent, gracePeriod);

            descendants.stream().filter(ProcessHandle::isAlive).forEach(this::destroyForciblyQuietly);
            if (parent.isAlive()) destroyForciblyQuietly(parent);
            restoreInterrupt |= awaitTermination(descendants, parent, forcedTimeout);
            return !uncertain.contains(process) && !parent.isAlive()
                    && descendants.stream().noneMatch(ProcessHandle::isAlive)
                    && tracked.getOrDefault(process, java.util.Set.of()).stream().noneMatch(ProcessHandle::isAlive);
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    /** 按当前可见父链计算层级，优先终止更深层的已知进程。 */
    private int depth(ProcessHandle handle) {
        int depth = 0;
        var parent = handle.parent();
        while (parent.isPresent()) {
            depth++;
            parent = parent.get().parent();
        }
        return depth;
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

    /** 在指定时限内等待整棵进程树终止，并报告是否消费过中断。 */
    private boolean awaitTermination(
            List<ProcessHandle> descendants,
            ProcessHandle parent,
            Duration timeout
    ) {
        boolean interrupted = false;
        long deadlineNanos = System.nanoTime() + Math.max(0, timeout.toNanos());
        while (parent.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            if (System.nanoTime() >= deadlineNanos) return interrupted;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    /** 尝试温和终止一个进程。 */
    private void destroyQuietly(ProcessHandle processHandle) {
        try {
            processHandle.destroy();
        } catch (RuntimeException ignored) {
            // 强制清理阶段会再次处理仍存活的进程。
        }
    }

    /** 尝试强制终止一个进程，最终结果仍由存活状态判断。 */
    private void destroyForciblyQuietly(ProcessHandle processHandle) {
        try {
            processHandle.destroyForcibly();
        } catch (RuntimeException ignored) {
            // 调用失败后保持存活状态，由 clean 返回 false。
        }
    }
}
