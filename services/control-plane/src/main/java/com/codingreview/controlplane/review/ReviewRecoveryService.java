package com.codingreview.controlplane.review;

import com.codingreview.controlplane.application.session.SessionOperationLockManager;
import com.codingreview.controlplane.review.snapshot.SnapshotCapture;
import com.codingreview.controlplane.review.snapshot.SnapshotPublicationStore;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;

/** 先建立 Review 保护，再由 CodingSessionRecovery 恢复仓库所有权。 */
@Order(-10)
public final class ReviewRecoveryService implements ApplicationRunner {
    private final ReviewExecutionStore store;
    private final ReviewExecutionGuard guard;
    private final SnapshotCapture capture;
    private final SessionOperationLockManager locks;
    private final SnapshotPublicationStore snapshots;

    public ReviewRecoveryService(
            ReviewExecutionStore store,
            ReviewExecutionGuard guard,
            SnapshotCapture capture,
            SessionOperationLockManager locks,
            SnapshotPublicationStore snapshots) {
        this.store = store;
        this.guard = guard;
        this.capture = capture;
        this.locks = locks;
        this.snapshots = snapshots;
    }

    @Override
    public void run(ApplicationArguments args) {
        java.util.List<java.util.UUID> sessions;
        try {
            sessions = store.sessions();
        } catch (RuntimeException e) {
            guard.globalUnknown();
            return;
        }
        for (var session : sessions) {
            try {
                store.scan(
                        session,
                        current -> {
                            synchronized (locks.lock(session)) {
                                if (current.requestBytes() == null && current.blocks()) {
                                    try {
                                        capture.cleanupInterrupted(current.snapshotId(), session);
                                        capture.publishedIdentity(current.snapshotId(), session)
                                                .ifPresent(snapshots::save);
                                        store.save(
                                                current.state(
                                                        "FAILED",
                                                        "REVIEW_PREPARATION_INTERRUPTED"));
                                    } catch (RuntimeException e) {
                                        guard.uncertain(session);
                                    }
                                } else {
                                    try {
                                        ReviewProtocol.persisted(store, current);
                                    } catch (RuntimeException e) {
                                        guard.uncertain(session);
                                    }
                                }
                            }
                        });
            } catch (RuntimeException e) {
                guard.uncertain(session);
            }
        }
    }
}
