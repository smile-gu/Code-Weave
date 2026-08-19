package com.codingreview.controlplane.session.persistence;

import com.codingreview.controlplane.session.model.CodingTurn;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Coding Turn 的持久化边界。 */
public interface CodingTurnRepository {
    /** 保存一条 Turn 快照。 */
    void save(CodingTurn turn);
    /** 查询指定 Turn。 */
    Optional<CodingTurn> find(UUID sessionId, UUID turnId);
    /** 按创建顺序查询 Session 的全部 Turn。 */
    List<CodingTurn> findAll(UUID sessionId);
}
