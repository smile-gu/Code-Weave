package com.codingreview.controlplane.adapter.claude;

import java.util.List;

public final class ClaudePromptRenderer {

    private ClaudePromptRenderer() {
    }

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

    private static String bullets(List<String> values) {
        if (values.isEmpty()) {
            return "- None specified";
        }
        return values.stream().map(value -> "- " + value).reduce((left, right) -> left + "\n" + right).orElseThrow();
    }
}

