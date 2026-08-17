package com.codingreview.controlplane.configuration;

import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapter;
import com.codingreview.controlplane.adapter.claude.ClaudeCodeAdapterImpl;
import com.codingreview.controlplane.adapter.claude.invocation.CapabilityProfile;
import com.codingreview.controlplane.observability.run.FileRunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventJournal;
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

/** 集中装配 M0 核心对象，业务类本身不依赖 Spring。 */
@Configuration(proxyBeanMethods = false)
public class ControlPlaneConfiguration {

    @Bean
    RunEventJournal runEventJournal(CodeWeaveProperties properties, ObjectMapper objectMapper) {
        return new FileRunEventJournal(runtimeRoot(properties), objectMapper);
    }

    @Bean
    GitWorktreeManager gitWorktreeManager(CodeWeaveProperties properties) {
        return new GitWorktreeManagerImpl(runtimeRoot(properties));
    }

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

    @Bean
    GitChangeCollector gitChangeCollector() {
        return new GitChangeCollectorImpl();
    }

    @Bean
    ChangePolicyEvaluator changePolicyEvaluator() {
        return new ChangePolicyEvaluatorImpl();
    }

    @Bean
    MavenVerifier mavenVerifier(
            CodeWeaveProperties properties,
            RunEventJournal eventJournal
    ) {
        return new MavenVerifierImpl(runtimeRoot(properties), eventJournal);
    }

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

    private Path runtimeRoot(CodeWeaveProperties properties) {
        return properties.requireRuntimeRoot();
    }
}
