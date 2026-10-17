package com.codingreview.controlplane;

import com.codingreview.controlplane.configuration.CodeWeaveProperties;
import com.codingreview.controlplane.workflow.coding.CodingTurnOrchestrator;
import org.springframework.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证默认 Spring 上下文只装配正式 Session/Turn 链路。 */
@SpringBootTest(properties = {
        "code-weave.claude.executable=C:/code-weave-test/claude.cmd",
        "code-weave.run.concurrency=1",
        "code-weave.run.queue-capacity=10"
})
class ControlPlaneApplicationTest {
    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path runtimeRoot;

    @org.springframework.test.context.DynamicPropertySource
    static void runtime(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("code-weave.runtime-root", () -> runtimeRoot.toString());
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CodingTurnOrchestrator codingTurnOrchestrator;

    @Autowired
    private CodeWeaveProperties properties;

    @Autowired
    private TaskExecutor codingTurnExecutor;

    @Test
    void loadsDefaultContextWithoutLegacyWorktreeRun() {
        assertThat(codingTurnOrchestrator).isNotNull();
        assertThat(codingTurnExecutor).isNotNull();
        assertThat(context.getBeansOfType(com.codingreview.controlplane.legacy.worktreerun.application.WorktreeRunApplicationService.class))
                .isEmpty();
        assertThat(context.getBeansOfType(com.codingreview.controlplane.legacy.worktreerun.workflow.WorktreeRunOrchestrator.class))
                .isEmpty();
        assertThat(context.getBeansOfType(com.codingreview.controlplane.legacy.worktreerun.api.WorktreeRunController.class))
                .isEmpty();
        assertThat(context.getBeansOfType(com.codingreview.controlplane.legacy.worktreerun.api.RunEventStreamController.class))
                .isEmpty();
        assertThat(context.containsBean("legacyWorktreeRunExecutor")).isFalse();
        assertThat(properties.requireRuntimeRoot().isAbsolute()).isTrue();
        assertThat(properties.requireClaudeExecutable().isAbsolute()).isTrue();
    }
}
