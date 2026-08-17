package com.codingreview.controlplane.application.run;

import com.codingreview.controlplane.workflow.m0.model.M0RunResult;
import com.codingreview.controlplane.workflow.m0.model.M0RunStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 保存当前进程内的 Run 状态投影；持久事实仍由 RunEventJournal 负责。 */
@Component
public class RunExecutionRegistry {

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    /** 注册一个刚进入执行队列的新 Run。 */
    public RunExecutionSnapshot registerQueued(UUID runId, Instant createdAt) {
        Entry entry = new Entry(runId, createdAt);
        if (entries.putIfAbsent(runId, entry) != null) {
            throw new IllegalStateException("runId already exists: " + runId);
        }
        return entry.snapshot();
    }

    /** 将指定 Run 从排队状态推进为运行状态。 */
    public RunExecutionSnapshot markRunning(UUID runId, Instant startedAt) {
        return require(runId).markRunning(startedAt);
    }

    /** 使用业务结果完成指定 Run 的终态投影。 */
    public RunExecutionSnapshot complete(UUID runId, M0RunResult result) {
        return require(runId).complete(result);
    }

    /** 将指定 Run 标记为基础设施失败。 */
    public RunExecutionSnapshot markInfrastructureFailed(
            UUID runId,
            Instant endedAt,
            String message
    ) {
        return require(runId).markInfrastructureFailed(endedAt, message);
    }

    /** 返回指定 Run 的最新只读快照。 */
    public RunExecutionSnapshot find(UUID runId) {
        Entry entry = entries.get(runId);
        return entry == null ? null : entry.snapshot();
    }

    /** 获取必须存在的内部状态条目。 */
    private Entry require(UUID runId) {
        Entry entry = entries.get(runId);
        if (entry == null) {
            throw new IllegalArgumentException("unknown runId: " + runId);
        }
        return entry;
    }

    private static final class Entry {
        private final UUID runId;
        private final Instant createdAt;
        private RunExecutionStatus status = RunExecutionStatus.QUEUED;
        private Instant startedAt;
        private Instant endedAt;
        private String message = "任务已进入执行队列。";
        private M0RunResult result;

        private Entry(UUID runId, Instant createdAt) {
            this.runId = runId;
            this.createdAt = createdAt;
        }

        /** 将当前条目转换为运行中状态。 */
        private synchronized RunExecutionSnapshot markRunning(Instant value) {
            if (status != RunExecutionStatus.QUEUED) {
                throw invalidTransition(RunExecutionStatus.RUNNING);
            }
            status = RunExecutionStatus.RUNNING;
            startedAt = value;
            message = "任务正在执行。";
            return snapshot();
        }

        /** 根据 M0 业务结果设置当前条目的终态。 */
        private synchronized RunExecutionSnapshot complete(M0RunResult value) {
            if (status != RunExecutionStatus.RUNNING) {
                throw invalidTransition(statusFor(value.status()));
            }
            status = statusFor(value.status());
            endedAt = value.endedAt();
            message = value.userMessage();
            result = value;
            return snapshot();
        }

        /** 在非终态时记录基础设施失败。 */
        private synchronized RunExecutionSnapshot markInfrastructureFailed(
                Instant value,
                String failureMessage
        ) {
            if (terminal()) {
                return snapshot();
            }
            status = RunExecutionStatus.INFRASTRUCTURE_FAILED;
            endedAt = value;
            message = failureMessage;
            return snapshot();
        }

        /** 创建当前内部状态的不可变快照。 */
        private synchronized RunExecutionSnapshot snapshot() {
            return new RunExecutionSnapshot(
                    runId, status, createdAt, startedAt, endedAt, message, result
            );
        }

        /** 判断当前条目是否已经进入终态。 */
        private boolean terminal() {
            return switch (status) {
                case COMPLETED, FAILED, CANCELLED, INFRASTRUCTURE_FAILED -> true;
                case QUEUED, RUNNING -> false;
            };
        }

        /** 创建包含非法状态转换信息的异常。 */
        private IllegalStateException invalidTransition(RunExecutionStatus target) {
            return new IllegalStateException(
                    "invalid run state transition: " + status + " -> " + target
            );
        }

        /** 将工作流终态映射为应用层执行状态。 */
        private static RunExecutionStatus statusFor(M0RunStatus status) {
            return switch (status) {
                case COMPLETED -> RunExecutionStatus.COMPLETED;
                case FAILED -> RunExecutionStatus.FAILED;
                case CANCELLED -> RunExecutionStatus.CANCELLED;
            };
        }
    }
}
