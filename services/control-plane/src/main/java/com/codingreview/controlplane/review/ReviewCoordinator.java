package com.codingreview.controlplane.review;

import com.codingreview.controlplane.application.session.SessionOperationLockManager;
import com.codingreview.controlplane.review.snapshot.SnapshotPublicationStore;
import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 有界、退避的单轮协调；网络调用与 SSE 等待均在 Session 锁外。 */
public final class ReviewCoordinator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReviewCoordinator.class);
    private final ReviewExecutionStore store;
    private final ReviewExecutionGuard guard;
    private final ReviewHarnessClient client;
    private final ReviewFactImporter importer;
    private final SnapshotPublicationStore snapshots;
    private final SessionOperationLockManager locks;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private final Map<UUID, Integer> scheduled = new ConcurrentHashMap<>();
    private final int capacity;
    private final Duration interval;
    private volatile boolean accepting = true;

    public ReviewCoordinator(
            ReviewExecutionStore store,
            ReviewExecutionGuard guard,
            ReviewHarnessClient client,
            ReviewFactImporter importer,
            SnapshotPublicationStore snapshots,
            SessionOperationLockManager locks,
            int capacity,
            Duration interval) {
        this.store = store;
        this.guard = guard;
        this.client = client;
        this.importer = importer;
        this.snapshots = snapshots;
        this.locks = locks;
        this.capacity = capacity;
        this.interval = interval;
        executor.setRemoveOnCancelPolicy(true);
    }

    public synchronized boolean ensureScheduled(ReviewExecution run) {
        if (!accepting || run.requestBytes() == null || !run.blocks()) return false;
        if (scheduled.containsKey(run.runId())) return true;
        if (scheduled.size() >= capacity) return false;
        scheduled.put(run.runId(), 0);
        executor.execute(() -> tick(run.sessionId(), run.runId()));
        return true;
    }

    private void tick(UUID session, UUID run) {
        if (!accepting) return;
        int failures = scheduled.getOrDefault(run, 0);
        try {
            ReviewExecution current = reconcile(session, run);
            if (!current.blocks()) {
                scheduled.remove(run);
                discover();
                return;
            }
            failures = 0;
        } catch (RuntimeException error) {
            String code =
                    error instanceof ReviewException review
                            ? review.code()
                            : "REVIEW_SUBMISSION_UNKNOWN";
            LOG.warn("Review 对账未收敛，runId={}，code={}", run, code);
            failures = Math.min(failures + 1, 4);
            synchronized (locks.lock(session)) {
                try {
                    ReviewExecution current = require(session, run);
                    if (current.blocks())
                        store.save(
                                current.state(
                                        current.pythonStatus() == null
                                                ? "SUBMISSION_UNKNOWN"
                                                : "EXECUTION_BLOCKED",
                                        code));
                } catch (RuntimeException ignored) {
                    guard.uncertain(session);
                }
            }
        }
        scheduled.put(run, failures);
        if (accepting)
            executor.schedule(
                    () -> tick(session, run),
                    Math.min(30_000, interval.toMillis() * (1L << failures)),
                    TimeUnit.MILLISECONDS);
    }

    /** 单轮公开用例供故障/恢复验收；不重新捕获，不生成新身份。 */
    public ReviewExecution reconcile(UUID session, UUID run) {
        ReviewExecution current = require(session, run);
        if (!current.blocks()) {
            ReviewProtocol.persisted(store, current);
            guard.confirmed(session);
            return current;
        }
        if (current.requestBytes() == null) return current;
        ReviewHarnessClient.Response response = client.status(run);
        if (response.status() == 404) {
            var snapshot =
                    snapshots
                            .find(current.snapshotId())
                            .orElseThrow(() -> new ReviewException("REVIEW_STATE_WRITE_UNCERTAIN"));
            if (!snapshot.sessionId().equals(session)
                    || !snapshot.manifestSha256().equals(current.manifestSha256()))
                throw new ReviewException("REVIEW_HISTORY_CORRUPTED");
            response = client.submit(current);
            if (response.status() != 200 && response.status() != 202)
                throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
        } else if (response.status() != 200) throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
        current = importer.status(session, run, response.bytes());
        if (current.cancellationRequested()
                && !ReviewProtocol.TERMINAL.contains(current.pythonStatus())) {
            var cancelled = client.cancel(run);
            if (cancelled.status() != 200 && cancelled.status() != 202)
                throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
            current = importer.status(session, run, cancelled.bytes());
        }
        if (current.eventCursor() < current.observedLastEventSequence()
                || !ReviewProtocol.TERMINAL.contains(current.pythonStatus())) {
            client.events(current, raw -> importer.event(session, run, raw));
        }
        var finalStatus = client.status(run);
        if (finalStatus.status() != 200) throw new ReviewException("REVIEW_SUBMISSION_UNKNOWN");
        current = importer.status(session, run, finalStatus.bytes());
        JsonNode facts = ReviewProtocol.status(finalStatus.bytes(), run);
        if (current.eventCursor() < current.observedLastEventSequence()) return current;
        var result = facts.path("status").asText().equals("SUCCEEDED") ? client.result(run) : null;
        return importer.finish(session, run, finalStatus.bytes(), result);
    }

    public void discover() {
        for (UUID session : store.sessions()) {
            try {
                store.scan(session, this::ensureScheduled);
            } catch (RuntimeException error) {
                guard.uncertain(session);
            }
        }
    }

    private ReviewExecution require(UUID session, UUID run) {
        return store.find(session, run)
                .orElseThrow(() -> new ReviewException("REVIEW_HISTORY_CORRUPTED"));
    }

    @Override
    public void close() {
        accepting = false;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
