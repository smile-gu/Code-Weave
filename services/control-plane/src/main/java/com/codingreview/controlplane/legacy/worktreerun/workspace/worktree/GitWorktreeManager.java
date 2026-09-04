package com.codingreview.controlplane.legacy.worktreerun.workspace.worktree;

import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.GitRepositoryStatus;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.WorktreeCreateRequest;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.WorktreeCreateResult;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.model.WorktreeHandle;

import java.nio.file.Path;

/** 负责 Git 仓库校验、隔离 Worktree 创建和清理，不负责 Agent 或构建流程。 */
public interface GitWorktreeManager {

    /** 检查指定目录是否为可用 Git 仓库并返回当前状态。 */
    GitRepositoryStatus inspect(Path repository);

    /** 基于指定提交创建隔离 Worktree。 */
    WorktreeCreateResult create(WorktreeCreateRequest request);

    /** 在没有未保存修改时安全清理 Worktree。 */
    void cleanup(WorktreeHandle handle);

    /** 明确忽略未保存修改并强制清理 Worktree。 */
    void forceCleanup(WorktreeHandle handle);
}
