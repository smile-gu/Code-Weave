package com.codingreview.controlplane.review;

import com.codingreview.controlplane.application.session.*;
import com.codingreview.controlplane.configuration.CodeWeaveProperties;
import com.codingreview.controlplane.review.snapshot.*;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;

import java.nio.file.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReviewProperties.class)
public class ReviewConfiguration {
    @Bean
    ReviewExecutionStore reviewExecutionStore(CodeWeaveProperties properties) {
        return new ReviewExecutionStore(properties.requireRuntimeRoot());
    }

    @Bean
    ReviewExecutionGuard reviewExecutionGuard(ReviewExecutionStore store) {
        return new ReviewExecutionGuard(store);
    }

    @Bean
    SnapshotPublicationStore snapshotPublicationStore(CodeWeaveProperties properties) {
        return new SnapshotPublicationStore(properties.requireRuntimeRoot());
    }

    @Bean
    SnapshotCapture snapshotCapture(CodeWeaveProperties properties, ReviewProperties review)
            throws java.io.IOException {
        Path snapshot = review.snapshots(properties.requireRuntimeRoot()),
                execution = review.executions(properties.requireRuntimeRoot());
        ReviewPaths.separate(snapshot, execution);
        ReviewPaths.directory(snapshot);
        ReviewPaths.directory(execution);
        Files.createDirectories(snapshot);
        Files.createDirectories(execution);
        return new SnapshotCapture(
                snapshot,
                execution,
                new SnapshotLimits(
                        review.maxFiles(),
                        review.maxFileBytes(),
                        review.maxTotalBytes(),
                        review.maxManifestBytes(),
                        review.maxDiffBytes(),
                        review.captureTimeout()));
    }

    @Bean(destroyMethod = "close")
    ReviewHarnessClient reviewHarnessClient(ReviewProperties properties) {
        return new ReviewHarnessClient(
                properties.harnessBaseUrl(),
                properties.internalBearerToken(),
                properties.connectionTimeout(),
                properties.requestTimeout(),
                properties.sseIdleTimeout());
    }

    @Bean
    ReviewFactImporter reviewFactImporter(
            ReviewExecutionStore store,
            ReviewExecutionGuard guard,
            SessionOperationLockManager locks) {
        return new ReviewFactImporter(store, guard, locks);
    }

    @Bean(destroyMethod = "close")
    ReviewCoordinator reviewCoordinator(
            ReviewExecutionStore store,
            ReviewExecutionGuard guard,
            ReviewHarnessClient client,
            ReviewFactImporter importer,
            SnapshotPublicationStore snapshots,
            SessionOperationLockManager locks,
            ReviewProperties properties) {
        return new ReviewCoordinator(
                store,
                guard,
                client,
                importer,
                snapshots,
                locks,
                properties.queueCapacity(),
                properties.reconcileInterval());
    }

    @Bean
    ReviewRecoveryService reviewRecoveryService(
            ReviewExecutionStore store,
            ReviewExecutionGuard guard,
            SnapshotCapture capture,
            SessionOperationLockManager locks,
            SnapshotPublicationStore snapshots) {
        return new ReviewRecoveryService(store, guard, capture, locks, snapshots);
    }

    @Bean
    ReviewApplicationService reviewApplicationService(
            CodingSessionRepository sessions,
            SessionOperationLockManager locks,
            CodingIdleSafety coding,
            SessionWorkspaceGuard workspace,
            ChangePolicyEvaluator policy,
            SnapshotCapture capture,
            SnapshotPublicationStore snapshots,
            ReviewExecutionStore store,
            ReviewExecutionGuard guard,
            ReviewHarnessClient client,
            ReviewCoordinator coordinator) {
        return new ReviewApplicationService(
                sessions,
                locks,
                coding,
                workspace,
                policy,
                capture,
                snapshots,
                store,
                guard,
                client,
                coordinator);
    }

    @Bean
    ReadyReviewDiscovery readyReviewDiscovery(ReviewCoordinator coordinator) {
        return new ReadyReviewDiscovery(coordinator);
    }

    public static final class ReadyReviewDiscovery {
        private final ReviewCoordinator coordinator;

        ReadyReviewDiscovery(ReviewCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void ready() {
            coordinator.discover();
        }
    }
}
