package com.codingreview.controlplane.application.session;

import java.util.List;
import java.util.UUID;

/** 描述当前 Git 提交是否可以成为下一审查周期的活动基线。 */
public record ReviewBaselineReadiness(
        UUID sessionId,
        boolean ready,
        String activeBaselineCommit,
        String currentHead,
        String branch,
        int changedFiles,
        String workspaceFingerprint,
        List<String> blockers
) {
    /** 固化阻断原因，避免调用方在预览生成后修改集合。 */
    public ReviewBaselineReadiness {
        blockers = List.copyOf(blockers);
    }
}
