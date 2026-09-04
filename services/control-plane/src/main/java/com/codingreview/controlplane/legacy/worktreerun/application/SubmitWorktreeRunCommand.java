package com.codingreview.controlplane.legacy.worktreerun.application;

import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** API 层提交给应用服务的 旧版 Worktree Run 业务参数，不包含平台运行配置。 */
public record SubmitWorktreeRunCommand(
        Path repository,
        String baseRef,
        String taskPrompt,
        List<String> acceptanceCriteria,
        ChangePolicy changePolicy
) {
    public SubmitWorktreeRunCommand {
        Objects.requireNonNull(repository, "repository");
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        Objects.requireNonNull(changePolicy, "changePolicy");
    }
}
