package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/** 服务启动时将遗留的排队或运行中 Turn 收敛为可追溯中断状态。 */
@Component
@Order(1)
public final class CodingTurnRecoveryService implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(CodingTurnRecoveryService.class);

    private final CodingSessionRepository sessions;
    private final CodingTurnRepository turns;
    private final RunEventJournal journal;
    private final ObjectMapper mapper;

    /** 装配恢复所需的 Session、Turn 和事件事实源。 */
    public CodingTurnRecoveryService(
            CodingSessionRepository sessions,
            CodingTurnRepository turns,
            RunEventJournal journal,
            ObjectMapper mapper
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    }

    /** 扫描全部 Session 并终结服务重启前未完成的 Turn。 */
    @Override
    public void run(ApplicationArguments args) {
        int interrupted = 0;
        for (var session : sessions.findAll()) {
            for (CodingTurn turn : turns.findAll(session.sessionId())) {
                if (turn.status() != TurnStatus.QUEUED
                        && turn.status() != TurnStatus.RUNNING) {
                    continue;
                }
                CodingTurn recovered = turn.interrupted(Instant.now());
                turns.save(recovered);
                journal.append(
                        turn.turnId().toString(), "coding-turn", 1,
                        RunEventType.RUN_FAILED,
                        mapper.createObjectNode()
                                .put("turnId", turn.turnId().toString())
                                .put("failureCode", recovered.failureCode())
                                .put("message", recovered.userMessage())
                );
                interrupted++;
            }
        }
        LOGGER.info("Coding Turn 恢复完成，中断遗留 Turn={}", interrupted);
    }
}
