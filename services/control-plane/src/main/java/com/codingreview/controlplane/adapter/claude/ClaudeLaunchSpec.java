package com.codingreview.controlplane.adapter.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record ClaudeLaunchSpec(
        List<String> command,
        Path workingDirectory,
        String standardInput,
        Duration startupTimeout,
        Duration taskTimeout,
        Duration cancellationGracePeriod
) {
    public ClaudeLaunchSpec {
        command = List.copyOf(command);
    }
}

