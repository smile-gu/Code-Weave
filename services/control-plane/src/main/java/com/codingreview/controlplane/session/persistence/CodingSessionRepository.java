package com.codingreview.controlplane.session.persistence;

import com.codingreview.controlplane.session.model.CodingSession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 定义 Coding Session 持久化所需的最小仓储能力。 */
public interface CodingSessionRepository {

    /** 保存 Session 的完整最新快照。 */
    void save(CodingSession session);

    /** 按标识读取一个 Session。 */
    Optional<CodingSession> find(UUID sessionId);

    /** 按更新时间倒序读取全部有效 Session。 */
    List<CodingSession> findAll();

    /** 按更新时间倒序读取最近的 Session。 */
    default List<CodingSession> findRecent(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return findAll().stream().limit(limit).toList();
    }
}
