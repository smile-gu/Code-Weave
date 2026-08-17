package com.codingreview.controlplane.workspace.change.policy;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyViolation;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyViolationType;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 使用跨平台、大小写不敏感的 Glob 风格规则执行路径门禁。 */
public final class ChangePolicyEvaluatorImpl implements ChangePolicyEvaluator {

    private final AntPathMatcher pathMatcher;

    public ChangePolicyEvaluatorImpl() {
        pathMatcher = new AntPathMatcher("/");
        pathMatcher.setCaseSensitive(false);
    }

    /** 逐文件检查旧路径和新路径是否符合修改范围。 */
    @Override
    public ChangePolicyResult evaluate(GitChangeSet changeSet, ChangePolicy policy) {
        Objects.requireNonNull(changeSet, "changeSet");
        Objects.requireNonNull(policy, "policy");
        List<ChangePolicyViolation> violations = new ArrayList<>();
        for (GitChangedFile file : changeSet.files()) {
            if (file.oldPath() != null) {
                evaluatePath(file.oldPath(), file, policy, violations);
            }
            evaluatePath(file.path(), file, policy, violations);
        }
        return new ChangePolicyResult(violations);
    }

    /** 对单个仓库相对路径执行禁止优先的规则检查。 */
    private void evaluatePath(String path, GitChangedFile file, ChangePolicy policy,
                              List<ChangePolicyViolation> violations) {
        String deniedRule = firstMatchingRule(policy.deniedPaths(), path);
        if (deniedRule != null) {
            violations.add(new ChangePolicyViolation(
                    path,
                    file.changeType(),
                    ChangePolicyViolationType.DENIED_PATH,
                    deniedRule,
                    "文件 " + path + " 命中禁止修改规则 " + deniedRule + "。"
            ));
            return;
        }
        if (!policy.allowedPaths().isEmpty()
                && firstMatchingRule(policy.allowedPaths(), path) == null) {
            violations.add(new ChangePolicyViolation(
                    path,
                    file.changeType(),
                    ChangePolicyViolationType.OUTSIDE_ALLOWED_PATHS,
                    null,
                    "文件 " + path + " 不在本次任务允许修改的路径范围内。"
            ));
        }
    }

    /** 返回第一条能够匹配指定路径的规则。 */
    private String firstMatchingRule(List<String> rules, String path) {
        return rules.stream()
                .filter(rule -> pathMatcher.match(rule, path))
                .findFirst()
                .orElse(null);
    }
}
