package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle;

/** 只读采集 Worktree 相对于任务基准提交的实际文件变化。 */
public interface GitChangeCollector {

    GitChangeSet collect(WorktreeHandle handle);
}
