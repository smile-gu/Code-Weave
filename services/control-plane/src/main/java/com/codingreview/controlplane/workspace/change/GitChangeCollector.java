package com.codingreview.controlplane.workspace.change;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;

/** 只读采集工作区相对于任务基准提交的实际文件变化。 */
public interface GitChangeCollector {

    /** 采集工作区相对基准提交的完整变更快照。 */
    GitChangeSet collect(WorkspaceHandle handle);
}
