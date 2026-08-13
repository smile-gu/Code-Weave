package com.codingreview.controlplane.adapter.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 进程启动层可以直接消费的命令、工作目录、标准输入和超时配置。 */
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
