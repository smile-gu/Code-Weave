package com.codingreview.controlplane.adapter.claude.invocation;

import java.util.List;

/** 将平台任务约束渲染为发送给 Claude Code 的英文 Prompt。 */
public final class ClaudePromptRenderer {

    private ClaudePromptRenderer() {
    }

    /** 生成只通过标准输入传递的任务 Prompt，避免用户内容进入命令行。 */
    public static String render(ClaudeRunRequest request) {
        return """
                You are performing a coding task inside an isolated Git Worktree created by the platform.

                Task:
                %s

                Acceptance criteria:
                %s

                Allowed paths:
                %s

                Denied paths:
                %s

                Platform constraints:
                1. Operate only inside the current working directory.
                2. Do not create or manage Git Worktrees.
                3. Do not commit, merge, switch branches, push, pull, or access remotes.
                4. Do not access the network.
                5. Maven compilation and tests are executed later by the platform.
                6. Do not claim that a file was read or changed unless the corresponding tool event occurred.
                7. After editing, briefly summarize the changes.
                """.formatted(
                request.taskPrompt(),
                bullets(request.acceptanceCriteria()),
                bullets(request.allowedPaths()),
                bullets(request.deniedPaths())
        );
    }

    /** 将约束条目渲染为 Prompt 中的项目列表。 */
    private static String bullets(List<String> values) {
        if (values.isEmpty()) {
            return "- None specified";
        }
        return values.stream().map(value -> "- " + value).reduce((left, right) -> left + "\n" + right).orElseThrow();
    }
}
