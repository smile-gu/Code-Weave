package com.codingreview.controlplane.application.session;

import java.util.List;
import java.util.UUID;

/** 用户确认合并前看到的只读检查结果。 */
public record MergeReadiness(UUID sessionId, boolean ready, String fingerprint, String sourceHead,
                             String targetBranch, int changedFiles, List<String> blockers) {
    /** 创建不可变阻塞原因列表。 */
    public MergeReadiness { blockers = List.copyOf(blockers); }
}
