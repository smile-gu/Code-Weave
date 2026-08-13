package com.codingreview.controlplane.adapter.claude;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Control Plane 发起一次 Claude Code 运行所需的业务输入。 */
public record ClaudeRunRequest(
        UUID sessionId,
        Path worktree,
        String taskPrompt,
        List<String> acceptanceCriteria,
        List<String> allowedPaths,
        List<String> deniedPaths
) {
    public ClaudeRunRequest {
        // 在进入进程启动层前完成边界校验，避免 Claude Code 运行在错误目录。
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(worktree, "worktree");
        if (!worktree.isAbsolute()) {
            throw new IllegalArgumentException("worktree must be absolute");
        }
        if (taskPrompt == null || taskPrompt.isBlank()) {
            throw new IllegalArgumentException("taskPrompt must not be blank");
        }
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        allowedPaths = List.copyOf(allowedPaths);
        deniedPaths = List.copyOf(deniedPaths);
    }
}
