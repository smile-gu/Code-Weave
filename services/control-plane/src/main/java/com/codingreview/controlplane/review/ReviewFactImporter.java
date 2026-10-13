package com.codingreview.controlplane.review;

import com.codingreview.controlplane.application.session.SessionOperationLockManager;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/** 网络数据在锁外校验；锁内重读最新记录，先持久事件再推进 Cursor。 */
public final class ReviewFactImporter {
    private final ReviewExecutionStore store;
    private final ReviewExecutionGuard guard;
    private final SessionOperationLockManager locks;

    public ReviewFactImporter(
            ReviewExecutionStore store,
            ReviewExecutionGuard guard,
            SessionOperationLockManager locks) {
        this.store = store;
        this.guard = guard;
        this.locks = locks;
    }

    public ReviewExecution status(UUID session, UUID run, byte[] raw) {
        JsonNode facts =
                ReviewProtocol.status(
                        raw, run, ReviewProtocol.protocolVersion(require(session, run)));
        synchronized (locks.lock(session)) {
            ReviewExecution current = require(session, run);
            long last = facts.path("lastEventSequence").asLong();
            if (last < current.observedLastEventSequence()
                    || last < current.eventCursor()
                    || !current.blocks()) return current;
            if (current.pythonStatus() != null
                    && ReviewProtocol.TERMINAL.contains(current.pythonStatus())
                    && !current.pythonStatus().equals(facts.path("status").asText()))
                throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
            ReviewExecution next =
                    current.project(
                            facts,
                            current.eventCursor(),
                            last,
                            ReviewProtocol.TERMINAL.contains(facts.path("status").asText())
                                    ? "FINALIZING"
                                    : "ACTIVE");
            save(next);
            return next;
        }
    }

    public void event(UUID session, UUID run, byte[] raw) {
        JsonNode event =
                ReviewProtocol.event(
                        raw, run, ReviewProtocol.protocolVersion(require(session, run)));
        synchronized (locks.lock(session)) {
            ReviewExecution current = require(session, run);
            long sequence = event.path("sequence").asLong();
            if (sequence <= current.eventCursor()) {
                if (!Arrays.equals(store.event(current, sequence), raw))
                    throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
                return;
            }
            if (sequence != current.eventCursor() + 1)
                throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
            JsonNode previous =
                    sequence == 1
                            ? null
                            : ReviewProtocol.event(
                                    store.event(current, sequence - 1),
                                    run,
                                    ReviewProtocol.protocolVersion(current));
            ReviewProtocol.follows(previous, event);
            if (event.path("eventType").asText().equals("REVIEW_PASS_UPDATED")) {
                JsonNode priorPass = null;
                for (long oldSequence = sequence - 1; oldSequence > 0; oldSequence--) {
                    var prior =
                            ReviewProtocol.event(
                                    store.event(current, oldSequence),
                                    run,
                                    ReviewProtocol.protocolVersion(current));
                    if (prior.path("eventType").asText().equals("REVIEW_PASS_UPDATED")) {
                        priorPass = prior.path("payload").path("passes");
                        break;
                    }
                }
                ReviewPassProgress.transition(
                        priorPass,
                        event.path("payload").path("passes"),
                        ReviewJson.parse(current.requestBytes()).path("reviewPlan"));
            }
            store.saveEvent(current, sequence, raw);
            ReviewExecution next;
            if (sequence < current.observedLastEventSequence()) {
                var facts =
                        ReviewJson.parse(
                                ReviewJson.canonical(
                                        Map.of(
                                                "status",
                                                current.pythonStatus(),
                                                "resourceState",
                                                current.resourceState())));
                next =
                        current.project(
                                facts,
                                sequence,
                                current.observedLastEventSequence(),
                                current.state());
            } else {
                JsonNode facts = event.get("payload");
                next =
                        current.project(
                                facts,
                                sequence,
                                sequence,
                                ReviewProtocol.TERMINAL.contains(facts.path("status").asText())
                                        ? "FINALIZING"
                                        : "ACTIVE");
            }
            save(next);
        }
    }

    public ReviewExecution finish(
            UUID session, UUID run, byte[] rawStatus, ReviewHarnessClient.Result response) {
        JsonNode facts =
                ReviewProtocol.status(
                        rawStatus, run, ReviewProtocol.protocolVersion(require(session, run)));
        synchronized (locks.lock(session)) {
            ReviewExecution current = require(session, run);
            if (!current.blocks()) return current;
            long last = facts.path("lastEventSequence").asLong();
            if (current.eventCursor() != last || current.observedLastEventSequence() != last)
                return current;
            if (!ReviewProtocol.TERMINAL.contains(facts.path("status").asText())) return current;
            if (!Set.of("NOT_ACQUIRED", "RELEASED")
                    .contains(facts.path("resourceState").asText())) {
                ReviewExecution next =
                        current.state("EXECUTION_BLOCKED", "REVIEW_RESOURCE_CLEANUP_FAILED");
                save(next);
                return next;
            }
            JsonNode lastEvent =
                    ReviewProtocol.event(
                            store.event(current, last),
                            run,
                            ReviewProtocol.protocolVersion(current));
            if (!lastEvent
                            .path("payload")
                            .path("status")
                            .asText()
                            .equals(facts.path("status").asText())
                    || !lastEvent
                            .path("payload")
                            .path("resourceState")
                            .asText()
                            .equals(facts.path("resourceState").asText()))
                throw new ReviewException("REVIEW_EVENT_INTEGRITY_ERROR");
            String target =
                    switch (facts.path("status").asText()) {
                        case "SUCCEEDED" -> "COMPLETED";
                        case "FAILED" -> "FAILED";
                        default -> "CANCELLED";
                    };
            if (target.equals("COMPLETED")) {
                if (response == null) return current;
                ReviewProtocol.result(
                        response.bytes(), response.sha256(), response.etag(), current, facts);
                store.saveResult(current, response.bytes());
            }
            ReviewExecution next = current.project(facts, last, last, target);
            ReviewProtocol.persisted(store, next);
            save(next);
            guard.confirmed(session);
            return next;
        }
    }

    private ReviewExecution require(UUID session, UUID run) {
        return store.find(session, run)
                .orElseThrow(() -> new ReviewException("REVIEW_HISTORY_CORRUPTED"));
    }

    private void save(ReviewExecution next) {
        try {
            store.save(next);
        } catch (RuntimeException error) {
            guard.uncertain(next.sessionId());
            throw error;
        }
    }
}
