package com.codingreview.controlplane.review;

import com.codingreview.controlplane.application.session.*;
import com.codingreview.controlplane.application.session.error.*;
import com.codingreview.controlplane.review.snapshot.*;
import com.codingreview.controlplane.session.guard.SessionWorkspaceGuard;
import com.codingreview.controlplane.session.model.*;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.policy.ChangePolicyEvaluator;

import java.util.*;

/** Session 锁内预留/捕获；锁外提交同一身份的异步协调。 */
public final class ReviewApplicationService {
    private final CodingSessionRepository sessions;
    private final SessionOperationLockManager locks;
    private final CodingIdleSafety coding;
    private final SessionWorkspaceGuard workspace;
    private final ChangePolicyEvaluator policy;
    private final SnapshotCapture capture;
    private final SnapshotPublicationStore snapshots;
    private final ReviewExecutionStore store;
    private final ReviewExecutionGuard guard;
    private final ReviewHarnessClient client;
    private final ReviewCoordinator coordinator;

    public ReviewApplicationService(
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
        this.sessions = sessions;
        this.locks = locks;
        this.coding = coding;
        this.workspace = workspace;
        this.policy = policy;
        this.capture = capture;
        this.snapshots = snapshots;
        this.store = store;
        this.guard = guard;
        this.client = client;
        this.coordinator = coordinator;
    }

    public ScopePreview preview(UUID sessionId) {
        synchronized (locks.lock(sessionId)) {
            return scope(active(sessionId));
        }
    }

    private ScopePreview scope(CodingSession session) {
        coding.assertIdleAndSafe(session.sessionId());
        guard.assertAvailable(session.sessionId());
        workspace.assertReadable(session);
        ScopePreview preview =
                capture.preview(
                        session.sessionId(),
                        session.sourceRepository(),
                        session.activeReviewBaselineCommit());
        if (!policy.evaluate(
                        new GitChangeSet(
                                preview.activeBaselineCommit(),
                                session.sourceRepository(),
                                preview.changedFiles(),
                                "",
                                0),
                        session.changePolicy())
                .compliant())
            throw new CodingSessionConflictException(
                    "CHANGE_POLICY_VIOLATION", "当前累计变更违反 Session 路径权限，不能用范围排除绕过。");
        return preview;
    }

    public ReviewExecution start(UUID sessionId, ScopeConfirmation expected, String message) {
        ReviewJson.unicode(message);
        if (message.isBlank()
                || message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
            throw new ReviewException("REVIEW_REQUEST_TOO_LARGE");
        client.requireAvailable();
        ReviewExecution run;
        synchronized (locks.lock(sessionId)) {
            CodingSession session = active(sessionId);
            ScopePreview current = scope(session);
            if (!current.activeBaselineCommit().equals(expected.expectedActiveBaselineCommit())
                    || !current.headCommit().equals(expected.expectedHeadCommit())
                    || !current.branch().equals(expected.expectedBranch())
                    || !current.workspaceFingerprint()
                            .equals(expected.expectedWorkspaceFingerprint()))
                throw new SnapshotException("REVIEW_SCOPE_PREVIEW_EXPIRED");
            if (expected.targetPaths().isEmpty()
                    || !current.changedPaths().containsAll(expected.targetPaths()))
                throw new SnapshotException("REVIEW_SCOPE_EMPTY");
            run = ReviewExecution.preparing(sessionId);
            try {
                store.save(run);
            } catch (RuntimeException e) {
                guard.uncertain(sessionId);
                throw e;
            }
            try {
                SnapshotPublication publication =
                        capture.capture(
                                run.snapshotId(),
                                sessionId,
                                session.sourceRepository(),
                                session.activeReviewBaselineCommit(),
                                expected);
                snapshots.save(publication);
                run = run.submitting(publication, message);
                store.save(run);
            } catch (RuntimeException error) {
                boolean safe =
                        error instanceof SnapshotException snapshot
                                && snapshot.resourcesSafe()
                                && !snapshot.code().equals("SNAPSHOT_STATE_WRITE_FAILED");
                String code =
                        error instanceof SnapshotException snapshot
                                ? snapshot.code()
                                : "REVIEW_STATE_WRITE_UNCERTAIN";
                try {
                    store.save(run.state(safe ? "FAILED" : "EXECUTION_BLOCKED", code));
                } catch (RuntimeException ignored) {
                    guard.uncertain(sessionId);
                }
                throw error;
            }
        }
        if (!coordinator.ensureScheduled(run)) {
            synchronized (locks.lock(sessionId)) {
                run =
                        require(sessionId, run.runId())
                                .state("SUBMISSION_UNKNOWN", "REVIEW_SUBMISSION_UNKNOWN");
                store.save(run);
            }
        }
        return run;
    }

    public ReviewExecution get(UUID session, UUID run) {
        requireSession(session);
        ReviewExecution current = require(session, run);
        if (current.blocks() && current.requestBytes() != null)
            coordinator.ensureScheduled(current);
        return current;
    }

    public List<ReviewExecution> list(UUID session, int limit) {
        requireSession(session);
        return store.list(session, limit);
    }

    public ReviewExecution cancel(UUID session, UUID run) {
        requireSession(session);
        ReviewExecution current;
        synchronized (locks.lock(session)) {
            current = require(session, run);
            if (!current.blocks()) return current;
            current = current.cancelIntent();
            try {
                store.save(current);
            } catch (RuntimeException e) {
                guard.uncertain(session);
                throw e;
            }
        }
        coordinator.ensureScheduled(current);
        // Intent is durable before any request. A 404 remains submission uncertainty.
        if (current.pythonStatus() != null) {
            try {
                client.cancel(run);
            } catch (RuntimeException ignored) {
                /* Coordinator retries using the same identity. */
            }
        }
        return current;
    }

    private CodingSession active(UUID session) {
        CodingSession value = requireSession(session);
        if (value.status() != CodingSessionStatus.ACTIVE || !value.usesRealRepository())
            throw new CodingSessionConflictException("SESSION_NOT_ACTIVE", "Review 只支持活动的真实工作区会话。");
        return value;
    }

    private CodingSession requireSession(UUID id) {
        return sessions.find(id).orElseThrow(() -> new CodingSessionNotFoundException(id));
    }

    private ReviewExecution require(UUID session, UUID run) {
        return store.find(session, run)
                .orElseThrow(() -> new ReviewException("REVIEW_RUN_NOT_FOUND"));
    }
}
