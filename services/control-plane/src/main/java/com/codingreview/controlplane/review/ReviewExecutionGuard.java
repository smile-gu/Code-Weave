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
}
