package com.codingreview.controlplane.review;

import com.codingreview.controlplane.application.session.error.CodingSessionConflictException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只读持久产品事实和本地未知证据；门禁内不发网络请求。 */
public final class ReviewExecutionGuard {
    private final ReviewExecutionStore store;
    private final Set<UUID> uncertain = ConcurrentHashMap.newKeySet();
    private volatile boolean globalUnknown;

    public ReviewExecutionGuard(ReviewExecutionStore store) {
        this.store = store;
    }

    public void uncertain(UUID sessionId) {
        uncertain.add(sessionId);
    }

    public void confirmed(UUID sessionId) {
        uncertain.remove(sessionId);
    }

    public void globalUnknown() {
        globalUnknown = true;
    }

    public boolean blocks(UUID sessionId) {
        if (globalUnknown || uncertain.contains(sessionId)) return true;
        try {
            AtomicBoolean blocked = new AtomicBoolean();
            store.scan(
                    sessionId,
                    run -> {
                        ReviewProtocol.persisted(store, run);
                        if (run.blocks()) blocked.set(true);
                        else if ("COMPLETED".equals(run.state())) {
                            byte[] result = store.result(run);
                            if (run.result() == null
                                    || !ReviewJson.sha256(result)
                                            .equals(run.result().path("resultSha256").asText()))
                                blocked.set(true);
                        }
                    });
            return blocked.get();
        } catch (RuntimeException e) {
            uncertain.add(sessionId);
            return true;
        }
    }

    public void assertAvailable(UUID sessionId) {
        if (blocks(sessionId))
            throw new CodingSessionConflictException(
                    "REVIEW_EXECUTION_BLOCKED", "Review 提交、资源或事实导入尚未确认，当前会话保持执行保护。");
    }

    /** Report interaction is independent of Python resource protection. */
    public void assertCodingAllowed(UUID sessionId, UUID sourceHandoff) {
        assertCodingAllowed(sessionId, sourceHandoff, null);
    }

    /** The caller holds the Session lock and has validated this exact prepared Handoff. */
    public void assertHandoffInteractionAllowed(UUID sessionId, UUID runId, UUID handoffId) {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(handoffId);
        assertCodingAllowed(sessionId, handoffId, runId);
    }

    private void assertCodingAllowed(UUID sessionId, UUID sourceHandoff, UUID preparingRun) {
        store.scan(
                sessionId,
                run -> {
                    var saved = store.reportHead(run);
                    if (saved.isEmpty()) return;
                    var head = saved.get();
                    if (head.interaction() != ReviewReport.Interaction.OPEN) return;
                    var entries =
                            head.handoffs() == null
                                    ? List.<ReviewHandoff.Lifecycle>of()
                                    : head.handoffs().entries();
                    boolean authorized = false;
                    for (var entry : entries) {
                        if (!entry.handoffId().equals(sourceHandoff)) continue;
                        if (preparingRun == null) {
                            authorized = entry.status() == ReviewHandoff.Status.STARTING;
                        } else if (run.runId().equals(preparingRun)) {
                            authorized =
                                    (entry.status() == ReviewHandoff.Status.PREPARED
                                                    || entry.status()
                                                            == ReviewHandoff.Status.STARTING)
                                            && currentApproval(run, head, entry);
                        }
                    }
                    boolean handedOff =
                            !entries.isEmpty()
                                    && entries.getLast().status() == ReviewHandoff.Status.STARTED
                                    && currentApproval(run, head, entries.getLast());
                    if (!authorized && !handedOff)
                        throw new CodingSessionConflictException(
                                "REVIEW_INTERACTION_BLOCKED", "请显式关闭人工审阅，或从已确认交接启动 Coding。");
                });
    }

    private boolean currentApproval(
            ReviewExecution run, ReviewReport.Head head, ReviewHandoff.Lifecycle entry) {
        if (head.approval() == null) return false;
        var content =
                store.handoff(run, entry.handoffId())
                        .orElseThrow(() -> new ReviewException("HANDOFF_INTEGRITY_ERROR"));
        return content.approvalRequestId().equals(head.approval().requestId());
    }

    public void assertNoUnconfirmedStart(UUID sessionId) {
        store.scan(
                sessionId,
                run -> store.reportHead(run).ifPresent(ReviewHandoffService::assertNoStarting));
    }

    public void assertHandoffIntent(UUID sessionId, UUID turnId, UUID handoffId, String prompt) {
        AtomicBoolean found = new AtomicBoolean();
        store.scan(
                sessionId,
                run ->
                        store.reportHead(run)
                                .ifPresent(
                                        head -> {
                                            if (head.handoffs() == null) return;
                                            head.handoffs().entries().stream()
                                                    .filter(e -> e.handoffId().equals(handoffId))
                                                    .forEach(
                                                            e -> {
                                                                var content =
                                                                        store.handoff(
                                                                                        run,
                                                                                        handoffId)
                                                                                .orElseThrow(
                                                                                        () ->
                                                                                                new ReviewException(
                                                                                                        "HANDOFF_INTEGRITY_ERROR"));
                                                                if (e.status()
                                                                                != ReviewHandoff
                                                                                        .Status
                                                                                        .STARTING
                                                                        || !turnId.equals(
                                                                                e.turnId())
                                                                        || !content.contentDigest()
                                                                                .equals(
                                                                                        e
                                                                                                .contentDigest())
                                                                        || !ReviewHandoffService
                                                                                .codingPrompt(
                                                                                        content)
                                                                                .equals(prompt))
                                                                    throw new ReviewException(
                                                                            "HANDOFF_INTEGRITY_ERROR");
                                                                found.set(true);
                                                            });
                                        }));
        if (!found.get()) throw new ReviewException("HANDOFF_INTEGRITY_ERROR");
    }
}
