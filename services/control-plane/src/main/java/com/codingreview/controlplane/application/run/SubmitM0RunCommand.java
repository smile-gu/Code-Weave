package com.codingreview.controlplane.application.run;

import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** API 层提交给应用服务的 M0 业务参数，不包含平台运行配置。 */
public record SubmitM0RunCommand(
        Path repository,
        String baseRef,
        String taskPrompt,
        List<String> acceptanceCriteria,
        ChangePolicy changePolicy
) {
    public SubmitM0RunCommand {
        Objects.requireNonNull(repository, "repository");
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        Objects.requireNonNull(changePolicy, "changePolicy");
    }
}
