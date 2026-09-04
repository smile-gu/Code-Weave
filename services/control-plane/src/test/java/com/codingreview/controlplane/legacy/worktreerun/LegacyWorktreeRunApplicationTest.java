package com.codingreview.controlplane.legacy.worktreerun;

import com.codingreview.controlplane.legacy.worktreerun.application.WorktreeRunApplicationService;
import com.codingreview.controlplane.configuration.CodeWeaveProperties;
import com.codingreview.controlplane.legacy.worktreerun.workflow.WorktreeRunOrchestrator;
import com.codingreview.controlplane.legacy.worktreerun.api.WorktreeRunController;
import com.codingreview.controlplane.legacy.worktreerun.api.RunEventStreamController;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证显式启用 legacy-worktree-run 后旧版回归链路仍可完整装配。 */
@SpringBootTest(properties = {
        "code-weave.runtime-root=D:/code-weave-context-test-legacy-worktree-run",
        "code-weave.claude.executable=C:/code-weave-test/claude.cmd",
        "code-weave.run.concurrency=1",
        "code-weave.run.queue-capacity=10"
})
@ActiveProfiles("legacy-worktree-run")
@Tag("legacy-worktree-run")
class LegacyWorktreeRunApplicationTest {

    @Autowired
    private WorktreeRunApplicationService applicationService;

    @Autowired
    private WorktreeRunOrchestrator orchestrator;

    @Autowired
    private CodeWeaveProperties properties;

    @Autowired
    private TaskExecutor legacyWorktreeRunExecutor;

    @Autowired
    private WorktreeRunController controller;

    @Autowired
    private RunEventStreamController streamController;

    @Test
    void loadsLegacyWorktreeRunContextWhenProfileIsExplicitlyEnabled() {
        assertThat(applicationService).isNotNull();
        assertThat(orchestrator).isNotNull();
        assertThat(legacyWorktreeRunExecutor).isNotNull();
        assertThat(controller).isNotNull();
        assertThat(streamController).isNotNull();
        assertThat(properties.requireRuntimeRoot().isAbsolute()).isTrue();
        assertThat(properties.requireClaudeExecutable().isAbsolute()).isTrue();
    }
}
