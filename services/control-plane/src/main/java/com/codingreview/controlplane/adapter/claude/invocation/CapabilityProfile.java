package com.codingreview.controlplane.adapter.claude.invocation;

import java.time.Duration;
import java.util.List;

/** 定义 Claude Code 在某个运行阶段可使用的能力及资源限制。 */
public record CapabilityProfile(
        String id,
        boolean safeMode,
        boolean sessionPersistence,
        String permissionMode,
        List<String> tools,
        Duration startupTimeout,
        Duration taskTimeout,
        Duration cancellationGracePeriod,
        double maxBudgetUsd
) {
    public CapabilityProfile {
        // 保存不可变副本，避免外部代码在运行期间改变工具白名单。
        tools = List.copyOf(tools);
    }

    /** M0 的最小权限配置，仅开放文件读取、检索和编辑能力。 */
    public static CapabilityProfile m0Restricted() {
        return new CapabilityProfile(
                "M0_RESTRICTED",
                true,
                false,
                "dontAsk",
                List.of("Read", "Glob", "Grep", "Edit", "Write"),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofSeconds(5),
                1.00
        );
    }

    /** M6 多轮 Coding 复用受限工具集，但允许 Claude 原生会话持久化。 */
    public static CapabilityProfile m6SessionRestricted() {
        CapabilityProfile m0 = m0Restricted();
        return new CapabilityProfile(
                "M6_SESSION_RESTRICTED", m0.safeMode(), true, m0.permissionMode(), m0.tools(),
                m0.startupTimeout(), m0.taskTimeout(), m0.cancellationGracePeriod(), m0.maxBudgetUsd()
        );
    }
}
