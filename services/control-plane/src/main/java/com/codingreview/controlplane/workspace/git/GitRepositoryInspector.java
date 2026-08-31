package com.codingreview.controlplane.workspace.git;

import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;

import java.nio.file.Path;

/** 检查用户选择的真实 Git 工作区并读取创建基线。 */
public interface GitRepositoryInspector {

    /** 校验仓库并返回当前分支、HEAD 与脏状态。 */
    GitRepositorySnapshot inspect(Path repository);

    /** 判断 ancestorCommit 是否为 descendantCommit 的祖先提交。 */
    boolean isAncestor(Path repository, String ancestorCommit, String descendantCommit);
}
