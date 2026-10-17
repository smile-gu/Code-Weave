package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnHistoryException;
import com.codingreview.controlplane.session.persistence.CodingTurnHistoryFailureKind;
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
            final java.util.List<CodingTurn> history;
            try {
                history = turns.findAll(session.sessionId());
            } catch (CodingTurnHistoryException exception) {
                isolateDamagedHistory(session, exception);
                continue;
            } catch (RuntimeException exception) {
                LOGGER.error("读取 Turn 历史失败，本次启动保留 Session 现状，sessionId={}",
                        session.sessionId(), exception);
                continue;
            }
            for (CodingTurn turn : history) {
                if ("PROCESS_CLEANUP_FAILED".equals(turn.failureCode())) {
                    try {
                        sessions.save(session.fail("PROCESS_CLEANUP_FAILED",
                                "历史任务资源清理未确认，仓库继续保持保护。", Instant.now()));
                    } catch (RuntimeException exception) {
                        LOGGER.error("清理风险无法投影到 Session，Turn 证据仍阻止提交，sessionId={}",
                                session.sessionId(), exception);
                    }
                }
                if (turn.status() != TurnStatus.QUEUED
                        && turn.status() != TurnStatus.RUNNING) {
                    continue;
                }
                CodingTurn recovered = turn.interrupted(Instant.now());
                try {
                    turns.save(recovered);
                    interrupted++;
                } catch (RuntimeException exception) {
                    LOGGER.error("遗留 Turn 终态保存失败，保留 Session 执行保护，sessionId={}, turnId={}",
                            session.sessionId(), turn.turnId(), exception);
                    continue;
                }
                try {
                    journal.append(
                            turn.turnId().toString(), "coding-turn", 1,
                            RunEventType.RUN_FAILED,
                            mapper.createObjectNode()
                                    .put("turnId", turn.turnId().toString())
                                    .put("failureCode", recovered.failureCode())
                                    .put("message", recovered.userMessage())
                    );
                } catch (RuntimeException exception) {
                    LOGGER.error("遗留 Turn 已保存为中断，但恢复通知追加失败，sessionId={}, turnId={}",
                            session.sessionId(), turn.turnId(), exception);
                }
            }
        }
        LOGGER.info("Coding Turn 恢复完成，中断遗留 Turn={}", interrupted);
    }

    /** 将单个损坏历史隔离为 Session 失败，不影响其他 Session 继续恢复。 */
    private void isolateDamagedHistory(
            com.codingreview.controlplane.session.model.CodingSession session,
            CodingTurnHistoryException exception
    ) {
        String code = switch (exception.kind()) {
            case DATA_CORRUPTED -> "TURN_HISTORY_CORRUPTED";
            case VERSION_UNSUPPORTED -> "TURN_HISTORY_VERSION_UNSUPPORTED";
            case IO_FAILURE -> "TURN_HISTORY_IO_FAILED";
        };
        String message = exception.kind() == CodingTurnHistoryFailureKind.DATA_CORRUPTED
                ? "Coding Turn 历史已损坏，原文件已保留，请人工检查后再继续。"
                : "Coding Turn 历史当前无法可靠读取，请检查版本或文件系统后再继续。";
        try {
            sessions.save(session.fail(code, message, Instant.now()));
        } catch (RuntimeException saveFailure) {
            LOGGER.error("Turn 历史异常且 Session 保护状态保存失败，既有仓库保护不会释放，sessionId={}, path={}",
                    session.sessionId(), exception.file(), saveFailure);
            return;
        }
        LOGGER.error("已隔离异常 Turn 历史，sessionId={}, kind={}, path={}",
                exception.sessionId(), exception.kind(), exception.file(), exception);
    }
}
