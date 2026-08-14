package com.codingreview.controlplane.workspace.worktree;

import com.codingreview.controlplane.workspace.worktree.model.GitRepositoryStatus;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateRequest;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateResult;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;

import java.nio.file.Path;

/** 负责 Git 仓库校验、隔离 Worktree 创建和清理，不负责 Agent 或构建流程。 */
public interface GitWorktreeManager {

    GitRepositoryStatus inspect(Path repository);

    WorktreeCreateResult create(WorktreeCreateRequest request);

    void cleanup(WorktreeHandle handle);

    void forceCleanup(WorktreeHandle handle);
}
