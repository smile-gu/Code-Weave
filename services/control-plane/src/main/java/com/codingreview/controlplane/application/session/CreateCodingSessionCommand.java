package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;

import java.nio.file.Path;
import java.util.Objects;

/** 创建持久 Coding Session 所需的应用层输入。 */
public record CreateCodingSessionCommand(
        String title,
        Path repository,
        String baseRef,
        ChangePolicy changePolicy,
        boolean includeExistingChanges
) {
    /** 校验并规范化 Session 创建参数。 */
    public CreateCodingSessionCommand {
        Objects.requireNonNull(repository, "repository");
        if (!repository.isAbsolute()) {
            throw new IllegalArgumentException("repository must be absolute");
        }
        title = title == null ? null : title.trim();
        if (title != null && title.length() > 120) {
            throw new IllegalArgumentException("title must not exceed 120 characters");
        }
        baseRef = baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef.trim();
        Objects.requireNonNull(changePolicy, "changePolicy");
        if (changePolicy.allowedPaths().isEmpty()) {
            throw new IllegalArgumentException("allowedPaths must not be empty");
        }
    }
}
