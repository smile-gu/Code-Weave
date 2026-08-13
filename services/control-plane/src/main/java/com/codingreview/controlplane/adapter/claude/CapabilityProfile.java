package com.codingreview.controlplane.adapter.claude;

import java.time.Duration;
import java.util.List;

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
        tools = List.copyOf(tools);
    }

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
}

