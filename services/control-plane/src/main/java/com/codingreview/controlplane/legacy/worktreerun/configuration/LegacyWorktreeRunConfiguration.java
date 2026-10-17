package com.codingreview.controlplane.legacy.worktreerun.configuration;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.configuration.CodeWeaveProperties;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.legacy.worktreerun.workflow.WorktreeRunOrchestrator;
import com.codingreview.controlplane.legacy.worktreerun.workflow.WorktreeRunOrchestratorImpl;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;
import com.codingreview.controlplane.verification.maven.MavenVerifier;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.GitWorktreeManager;
import com.codingreview.controlplane.legacy.worktreerun.workspace.worktree.GitWorktreeManagerImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;

/** 仅在 legacy-worktree-run Profile 下装配旧版 Worktree 单轮回归链路。 */
@Configuration(proxyBeanMethods = false)
@Profile("legacy-worktree-run")
public class LegacyWorktreeRunConfiguration {

    /** 创建旧版 Worktree Run 使用的隔离 Worktree 管理器。 */
    @Bean
    GitWorktreeManager gitWorktreeManager(CodeWeaveProperties properties) {
        return new GitWorktreeManagerImpl(runtimeRoot(properties));
    }

    /** 创建只供旧版 Worktree Run 回归任务使用的有界线程池。 */
    @Bean
    TaskExecutor legacyWorktreeRunExecutor(CodeWeaveProperties properties) {
        CodeWeaveProperties.Run run = properties.getRun();
        run.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(run.getConcurrency());
        executor.setMaxPoolSize(run.getConcurrency());
        executor.setQueueCapacity(run.getQueueCapacity());
        executor.setThreadNamePrefix("legacy-worktree-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /** 将旧版 Worktree Run 五阶段编排器装配为回归 Bean。 */
    @Bean
    WorktreeRunOrchestrator worktreeRunOrchestrator(
            GitWorktreeManager worktreeManager,
            ClaudeCodeAdapter claudeCodeAdapter,
            GitChangeCollector changeCollector,
            ChangePolicyEvaluator changePolicyEvaluator,
            MavenVerifier mavenVerifier,
            RunEventJournal eventJournal,
            ObjectMapper objectMapper
    ) {
        return new WorktreeRunOrchestratorImpl(
                worktreeManager,
                claudeCodeAdapter,
                changeCollector,
                changePolicyEvaluator,
                mavenVerifier,
                eventJournal,
                objectMapper
        );
    }

    /** 获取已经校验的运行数据根目录。 */
    private Path runtimeRoot(CodeWeaveProperties properties) {
        return properties.requireRuntimeRoot();
    }
}
