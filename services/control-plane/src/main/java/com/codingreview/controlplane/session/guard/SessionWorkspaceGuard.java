package com.codingreview.controlplane.session.guard;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.workspace.git.GitRepositoryException;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspector;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;

import java.util.Objects;

/** 确认真实仓库仍处于 Coding Session 记录的分支和活动基线。 */
public final class SessionWorkspaceGuard {
    private final GitRepositoryInspector inspector;

    /** 注入用于读取真实仓库状态的检查器。 */
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
        if (!current.headCommit().equals(session.activeReviewBaselineCommit())) {
            throw new SessionWorkspaceDriftException(
                    "SOURCE_HEAD_CHANGED",
                    "真实仓库 HEAD 已从当前活动基线 "
                            + shortCommit(session.activeReviewBaselineCommit())
                            + " 变为 " + shortCommit(current.headCommit())
                            + "；如果这是用户已确认的提交，请先推进活动审查基线。"
            );
        }
    }

    /** 允许读取当前累计变更，但拒绝分支切换和非线性基线变化。 */
    public void assertReadable(CodingSession session) {
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
                            + " 变为 " + current.branch() + "，暂时无法读取累计变更。"
            );
        }
        if (current.headCommit().equals(session.activeReviewBaselineCommit())) {
            return;
        }
        try {
            if (!inspector.isAncestor(
                    session.sourceRepository(),
                    session.activeReviewBaselineCommit(),
                    current.headCommit()
            )) {
                throw new SessionWorkspaceDriftException(
                        "HEAD_NOT_DESCENDANT",
                        "当前 HEAD 不是活动审查基线的后继提交，无法可靠读取累计变更。"
                );
            }
        } catch (GitRepositoryException exception) {
            throw new SessionWorkspaceDriftException(exception.code(), exception.getMessage());
        }
    }

    /** 将提交标识缩短为便于用户核对的长度。 */
    private String shortCommit(String commit) {
        return commit.length() <= 8 ? commit : commit.substring(0, 8);
    }
}
