package com.codingreview.controlplane.application.session;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 为同一 Coding Session 的 Turn 与基线操作提供进程内互斥。 */
@Component
public final class SessionOperationLockManager {
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    /** 返回指定 Session 在当前进程内的稳定互斥锁。 */
    public Object lock(UUID sessionId) {
        return locks.computeIfAbsent(sessionId, ignored -> new Object());
    }
}
