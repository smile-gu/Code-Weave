package com.codingreview.controlplane.session.integration.persistence;

import com.codingreview.controlplane.session.integration.model.CodingSessionIntegration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Session 合并事实的持久化边界。 */
public interface CodingSessionIntegrationRepository {
    /** 保存最新合并状态。 */
    void save(CodingSessionIntegration integration);
    /** 查询指定 Session 的合并状态。 */
    Optional<CodingSessionIntegration> find(UUID sessionId);
    /** 扫描所有已开始的合并。 */
    List<CodingSessionIntegration> findAll();
}
