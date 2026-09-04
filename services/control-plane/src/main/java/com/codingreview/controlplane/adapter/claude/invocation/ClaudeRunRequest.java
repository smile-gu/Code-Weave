package com.codingreview.controlplane.adapter.claude.invocation;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Control Plane 发起一次 Claude Code 运行所需的业务输入。 */
public record ClaudeRunRequest(
        UUID sessionId,
        Path workspaceRoot,
        String taskPrompt,
        List<String> acceptanceCriteria,
        List<String> allowedPaths,
        List<String> deniedPaths,
        ClaudeConversationMode conversationMode,
        String nativeClaudeSessionId
) {
    /** 创建不保存 Claude 上下文的独立调用请求。 */
    public ClaudeRunRequest(
            UUID sessionId, Path workspaceRoot, String taskPrompt, List<String> acceptanceCriteria,
            List<String> allowedPaths, List<String> deniedPaths
    ) {
        this(sessionId, workspaceRoot, taskPrompt, acceptanceCriteria, allowedPaths, deniedPaths,
                ClaudeConversationMode.EPHEMERAL, null);
    }

    public ClaudeRunRequest {
        // 在进入进程启动层前完成边界校验，避免 Claude Code 运行在错误目录。
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        if (!workspaceRoot.isAbsolute()) {
            throw new IllegalArgumentException("workspaceRoot must be absolute");
        }
        if (taskPrompt == null || taskPrompt.isBlank()) {
            throw new IllegalArgumentException("taskPrompt must not be blank");
        }
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        allowedPaths = List.copyOf(allowedPaths);
        deniedPaths = List.copyOf(deniedPaths);
        conversationMode = Objects.requireNonNull(conversationMode, "conversationMode");
        if (conversationMode == ClaudeConversationMode.RESUME_PERSISTENT
                && (nativeClaudeSessionId == null || nativeClaudeSessionId.isBlank())) {
            throw new IllegalArgumentException("nativeClaudeSessionId is required when resuming Claude session");
        }
        if (conversationMode != ClaudeConversationMode.RESUME_PERSISTENT) {
            nativeClaudeSessionId = null;
        }
    }
}
