package com.codingreview.controlplane.workspace.change.policy;

import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyResult;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicyViolationType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证代码变更路径门禁的优先级和边界语义。 */
class ChangePolicyEvaluatorImplTest {

    private final ChangePolicyEvaluator evaluator = new ChangePolicyEvaluatorImpl();

    @Test
    void acceptsFilesInsideAllowedPathsWithCaseInsensitiveMatching() {
        GitChangeSet changeSet = changeSet(
                file("SRC/main/java/Demo.java", GitChangeType.MODIFIED),
                file("pom.xml", GitChangeType.MODIFIED)
        );
        ChangePolicy policy = new ChangePolicy(List.of("src/**", "POM.XML"), List.of());

        ChangePolicyResult result = evaluator.evaluate(changeSet, policy);

        assertThat(result.compliant()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void deniedPathWinsEvenWhenItIsAlsoAllowed() {
        GitChangeSet changeSet = changeSet(file("src/main/resources/secret.yml", GitChangeType.MODIFIED));
        ChangePolicy policy = new ChangePolicy(
                List.of("src/**"),
                List.of("src/main/resources/*.yml")
        );

        ChangePolicyResult result = evaluator.evaluate(changeSet, policy);

        assertThat(result.compliant()).isFalse();
        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.violationType()).isEqualTo(ChangePolicyViolationType.DENIED_PATH);
            assertThat(violation.matchedRule()).isEqualTo("src/main/resources/*.yml");
            assertThat(violation.message()).contains("secret.yml", "禁止修改规则");
        });
    }

    @Test
    void emptyAllowedPathsAllowsEverythingExceptDeniedPaths() {
        GitChangeSet changeSet = changeSet(
                file("src/main/java/Demo.java", GitChangeType.MODIFIED),
                file("README.md", GitChangeType.MODIFIED)
        );
        ChangePolicy policy = new ChangePolicy(List.of(), List.of("docs/private/**"));

        assertThat(evaluator.evaluate(changeSet, policy).compliant()).isTrue();
    }

    @Test
    void reportsFileOutsideAllowedPathsAsBusinessViolation() {
        GitChangeSet changeSet = changeSet(file("README.md", GitChangeType.MODIFIED));
        ChangePolicy policy = new ChangePolicy(List.of("src/**", "pom.xml"), List.of());

        ChangePolicyResult result = evaluator.evaluate(changeSet, policy);

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.path()).isEqualTo("README.md");
            assertThat(violation.violationType()).isEqualTo(ChangePolicyViolationType.OUTSIDE_ALLOWED_PATHS);
            assertThat(violation.matchedRule()).isNull();
            assertThat(violation.message()).contains("不在本次任务允许修改的路径范围内");
        });
    }

    @Test
    void evaluatesBothOldAndNewPathsForRename() {
        GitChangedFile renamed = new GitChangedFile(
                "src/new/Config.java",
                "config/Config.java",
                GitChangeType.RENAMED,
                false
        );
        ChangePolicy policy = new ChangePolicy(List.of("src/**"), List.of());

        ChangePolicyResult result = evaluator.evaluate(changeSet(renamed), policy);

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.path()).isEqualTo("config/Config.java");
            assertThat(violation.violationType()).isEqualTo(ChangePolicyViolationType.OUTSIDE_ALLOWED_PATHS);
        });
    }

    @Test
    void rejectsAbsoluteOrParentTraversalPolicyRules() {
        assertThatThrownBy(() -> new ChangePolicy(List.of("../outside/**"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChangePolicy(List.of(), List.of("D:/outside/**")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GitChangedFile file(String path, GitChangeType changeType) {
        return new GitChangedFile(path, null, changeType, false);
    }

    private GitChangeSet changeSet(GitChangedFile... files) {
        return new GitChangeSet(
                "0123456789012345678901234567890123456789",
                Path.of("D:/runtime/worktrees/policy-test"),
                List.of(files),
                "",
                0
        );
    }
}
