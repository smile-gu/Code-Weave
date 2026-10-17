package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.review.ReviewExecutionGuard;
import com.codingreview.controlplane.review.ReviewExecutionStore;
import java.nio.file.Path;
import java.util.UUID;

/** 旧 Coding 专项使用不存在的独立目录，真实 Guard 验证无 Review 历史。 */
final class ReviewTestSupport {
    static ReviewExecutionGuard emptyGuard() {
        return new ReviewExecutionGuard(new ReviewExecutionStore(Path.of(System.getProperty("java.io.tmpdir"), "code-weave-empty-review-" + UUID.randomUUID())));
    }
}
