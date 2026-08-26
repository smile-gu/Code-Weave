package com.codingreview.controlplane.configuration;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapterImpl;
import com.codingreview.controlplane.adapter.claude.invocation.CapabilityProfile;
import com.codingreview.controlplane.observability.run.FileRunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.stream.PublishingRunEventJournal;
import com.codingreview.controlplane.observability.run.stream.RunEventStreamHub;
import com.codingreview.controlplane.runtime.readiness.LocalRuntimeReadinessService;
import com.codingreview.controlplane.runtime.readiness.RuntimeReadinessService;
import com.codingreview.controlplane.runtime.readiness.probe.CommandProbe;
import com.codingreview.controlplane.runtime.readiness.probe.LocalCommandProbe;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.FileCodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.codingreview.controlplane.session.persistence.FileCodingTurnRepository;
import com.codingreview.controlplane.session.integration.persistence.CodingSessionIntegrationRepository;
import com.codingreview.controlplane.session.integration.persistence.FileCodingSessionIntegrationRepository;
import com.codingreview.controlplane.verification.maven.MavenVerifier;
import com.codingreview.controlplane.verification.maven.MavenVerifierImpl;
import com.codingreview.controlplane.workflow.m0.M0RunOrchestrator;
import com.codingreview.controlplane.workflow.m0.M0RunOrchestratorImpl;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.GitChangeCollectorImpl;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluatorImpl;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspector;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspectorImpl;
import com.codingreview.controlplane.workspace.lock.FileRepositoryWriteLockManager;
import com.codingreview.controlplane.workspace.lock.RepositoryWriteLockManager;
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

    /** 创建以运行目录 JSON 快照为事实源的 Coding Session 仓储。 */
    @Bean
    CodingSessionRepository codingSessionRepository(
            CodeWeaveProperties properties,
            ObjectMapper objectMapper
    ) {
        return new FileCodingSessionRepository(runtimeRoot(properties), objectMapper);
    }

    /** 创建按 Session 目录保存多轮 Turn 的文件仓储。 */
    @Bean
    CodingTurnRepository codingTurnRepository(CodeWeaveProperties properties, ObjectMapper objectMapper) {
        return new FileCodingTurnRepository(runtimeRoot(properties), objectMapper);
    }

    /** 创建记录 Session 写回原仓库全过程的文件仓储。 */
    @Bean
    CodingSessionIntegrationRepository codingSessionIntegrationRepository(
            CodeWeaveProperties properties,
            ObjectMapper objectMapper
    ) {
        return new FileCodingSessionIntegrationRepository(
                runtimeRoot(properties), objectMapper
        );
    }

    /** 创建带超时和输出上限的本机只读命令探测器。 */
    @Bean
    CommandProbe commandProbe() {
        return new LocalCommandProbe();
    }

    /** 创建实时检查 Coding Run 运行依赖的环境服务。 */
    @Bean
    RuntimeReadinessService runtimeReadinessService(
            CodeWeaveProperties properties,
            CommandProbe commandProbe
    ) {
        return new LocalRuntimeReadinessService(properties, commandProbe);
    }

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

    /** 创建只读检查用户真实 Git 工作区的基础设施。 */
    @Bean
    GitRepositoryInspector gitRepositoryInspector() {
        return new GitRepositoryInspectorImpl();
    }

    /** 创建基于运行目录锁文件的仓库级写锁管理器。 */
    @Bean(destroyMethod = "close")
    RepositoryWriteLockManager repositoryWriteLockManager(CodeWeaveProperties properties) {
        return new FileRepositoryWriteLockManager(runtimeRoot(properties));
    }

    /** 使用固定能力配置创建 Claude Code Adapter。 */
    @Bean
    ClaudeCodeAdapter claudeCodeAdapter(
            CodeWeaveProperties properties,
            ObjectMapper objectMapper
    ) {
        return new ClaudeCodeAdapterImpl(
                properties.requireClaudeExecutable(),
                CapabilityProfile.m6SessionRestricted(),
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

    /** 将现有 Claude、变更、Maven 基础设施组合为 M6 多轮编排器。 */
    @Bean
    com.codingreview.controlplane.workflow.m6.CodingTurnOrchestrator codingTurnOrchestrator(
            ClaudeCodeAdapter claudeCodeAdapter, GitChangeCollector changeCollector,
            ChangePolicyEvaluator changePolicyEvaluator, MavenVerifier mavenVerifier,
            RunEventJournal eventJournal, ObjectMapper objectMapper
    ) {
        return new com.codingreview.controlplane.workflow.m6.CodingTurnOrchestratorImpl(
                claudeCodeAdapter, changeCollector, changePolicyEvaluator, mavenVerifier,
                eventJournal, objectMapper
        );
    }

    /** 获取已经校验的运行数据根目录。 */
    private Path runtimeRoot(CodeWeaveProperties properties) {
        return properties.requireRuntimeRoot();
    }
}
