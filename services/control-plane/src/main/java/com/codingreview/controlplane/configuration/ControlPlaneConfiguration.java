package com.codingreview.controlplane.configuration;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapterImpl;
import com.codingreview.controlplane.adapter.claude.invocation.CapabilityProfile;
import com.codingreview.controlplane.observability.run.FileRunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.stream.PublishingRunEventJournal;
import com.codingreview.controlplane.observability.run.stream.RunEventStreamHub;
import com.codingreview.controlplane.verification.maven.MavenVerifier;
import com.codingreview.controlplane.verification.maven.MavenVerifierImpl;
import com.codingreview.controlplane.workflow.m0.M0RunOrchestrator;
import com.codingreview.controlplane.workflow.m0.M0RunOrchestratorImpl;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.GitChangeCollectorImpl;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluatorImpl;
import com.codingreview.controlplane.workspace.worktree.GitWorktreeManager;
import com.codingreview.controlplane.workspace.worktree.GitWorktreeManagerImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 集中装配 M0 核心对象，业务类本身不依赖 Spring。 */
@Configuration(proxyBeanMethods = false)
public class ControlPlaneConfiguration {

    /** 创建负责管理实时事件订阅的内存 Hub。 */
    @Bean
    RunEventStreamHub runEventStreamHub() {
        return new RunEventStreamHub();
    }

    /** 创建承载 SSE 长连接发送任务的虚拟线程执行器。 */
    @Bean(destroyMethod = "close")
    ExecutorService runEventStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 创建持久化后通知实时订阅者的 Run 事件日志。 */
    @Bean
    RunEventJournal runEventJournal(
            CodeWeaveProperties properties,
            ObjectMapper objectMapper,
            RunEventStreamHub streamHub
    ) {
        return new PublishingRunEventJournal(
                new FileRunEventJournal(runtimeRoot(properties), objectMapper),
                streamHub
        );
    }

    /** 创建负责隔离工作目录的 Git Worktree 管理器。 */
    @Bean
    GitWorktreeManager gitWorktreeManager(CodeWeaveProperties properties) {
        return new GitWorktreeManagerImpl(runtimeRoot(properties));
    }

    /** 使用固定能力配置创建 Claude Code Adapter。 */
    @Bean
    ClaudeCodeAdapter claudeCodeAdapter(
            CodeWeaveProperties properties,
            ObjectMapper objectMapper
    ) {
        return new ClaudeCodeAdapterImpl(
                properties.requireClaudeExecutable(),
                CapabilityProfile.m0Restricted(),
                objectMapper
        );
    }

    /** 创建 Git 代码变更采集器。 */
    @Bean
    GitChangeCollector gitChangeCollector() {
        return new GitChangeCollectorImpl();
    }

    /** 创建代码修改路径策略检查器。 */
    @Bean
    ChangePolicyEvaluator changePolicyEvaluator() {
        return new ChangePolicyEvaluatorImpl();
    }

    /** 创建独立执行 Maven 编译与测试的验证器。 */
    @Bean
    MavenVerifier mavenVerifier(
            CodeWeaveProperties properties,
            RunEventJournal eventJournal
    ) {
        return new MavenVerifierImpl(runtimeRoot(properties), eventJournal);
    }

    /** 将五个核心阶段装配为 M0 主工作流。 */
    @Bean
    M0RunOrchestrator m0RunOrchestrator(
            GitWorktreeManager worktreeManager,
            ClaudeCodeAdapter claudeCodeAdapter,
            GitChangeCollector changeCollector,
            ChangePolicyEvaluator changePolicyEvaluator,
            MavenVerifier mavenVerifier,
            RunEventJournal eventJournal,
            ObjectMapper objectMapper
    ) {
        return new M0RunOrchestratorImpl(
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
