package com.codingreview.controlplane;

import com.codingreview.controlplane.application.run.M0RunApplicationService;
import com.codingreview.controlplane.configuration.CodeWeaveProperties;
import com.codingreview.controlplane.workflow.m0.M0RunOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 M1 的 Spring Bean 能够按部署配置完整装配。 */
@SpringBootTest(properties = {
        "code-weave.runtime-root=D:/code-weave-context-test",
        "code-weave.claude.executable=C:/code-weave-test/claude.cmd",
        "code-weave.run.concurrency=1",
        "code-weave.run.queue-capacity=10"
})
class ControlPlaneApplicationTest {

    @Autowired
    private M0RunApplicationService applicationService;

    @Autowired
    private M0RunOrchestrator orchestrator;

    @Autowired
    private CodeWeaveProperties properties;

    @Autowired
    private TaskExecutor m0RunExecutor;

    @Test
    void loadsConfiguredM1ApplicationContext() {
        assertThat(applicationService).isNotNull();
        assertThat(orchestrator).isNotNull();
        assertThat(m0RunExecutor).isNotNull();
        assertThat(properties.requireRuntimeRoot().isAbsolute()).isTrue();
        assertThat(properties.requireClaudeExecutable().isAbsolute()).isTrue();
    }
}
