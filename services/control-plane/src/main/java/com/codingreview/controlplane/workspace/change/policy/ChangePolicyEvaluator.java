package com.codingreview.controlplane.workspace.change.policy;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;

/** 根据任务路径策略评估 Claude Code 的实际文件变更。 */
public interface ChangePolicyEvaluator {

    /** 根据允许和禁止路径规则评估实际代码变更。 */
    ChangePolicyResult evaluate(GitChangeSet changeSet, ChangePolicy policy);
}
