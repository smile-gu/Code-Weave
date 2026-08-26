package com.codingreview.controlplane.session.guard;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.workspace.git.GitRepositoryException;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspector;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;

import java.util.Objects;

/** 确认真实仓库仍处于 Coding Session 记录的分支和 HEAD。 */
public final class SessionWorkspaceGuard {
    private final GitRepositoryInspector inspector;

    public SessionWorkspaceGuard(GitRepositoryInspector inspector) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    /** 阻止分支切换、HEAD 漂移和仓库失效后的 Agent 执行。 */
    public void assertStable(CodingSession session) {
        Objects.requireNonNull(session, "session");
        if (!session.usesRealRepository()) return;
        GitRepositorySnapshot current;
        try {
            current = inspector.inspect(session.sourceRepository());
        } catch (GitRepositoryException exception) {
            throw new SessionWorkspaceDriftException(exception.code(), exception.getMessage());
        }
        if (!current.branch().equals(session.baseRef())) {
            throw new SessionWorkspaceDriftException(
                    "SOURCE_BRANCH_CHANGED",
                    "Session 创建后，真实仓库分支已从 " + session.baseRef()
                            + " 变为 " + current.branch() + "，已停止启动 Coding Agent。"
            );
        }
        if (!current.headCommit().equals(session.resolvedCommit())) {
            throw new SessionWorkspaceDriftException(
                    "SOURCE_HEAD_CHANGED",
                    "Session 创建后，真实仓库 HEAD 已从 " + shortCommit(session.resolvedCommit())
                            + " 变为 " + shortCommit(current.headCommit())
                            + "，已停止启动 Coding Agent。"
            );
        }
    }

    /** 将提交标识缩短为便于用户核对的长度。 */
    private String shortCommit(String commit) {
        return commit.length() <= 8 ? commit : commit.substring(0, 8);
    }
}
