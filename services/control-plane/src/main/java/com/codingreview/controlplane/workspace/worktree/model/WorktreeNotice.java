package com.codingreview.controlplane.workspace.worktree.model;

import java.util.Objects;

/** 面向用户的非阻断提醒，不承担技术诊断职责。 */
public record WorktreeNotice(
        WorktreeNoticeType type,
        String message
) {
    public WorktreeNotice {
        Objects.requireNonNull(type, "type");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
