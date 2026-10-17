package com.codingreview.controlplane.application.session;

import java.util.UUID;

/** Coding 的共享安全边界；Review 编排依赖此边界而非 Turn 应用服务。 */
public interface CodingIdleSafety {
    void assertIdleAndSafe(UUID sessionId);
}
