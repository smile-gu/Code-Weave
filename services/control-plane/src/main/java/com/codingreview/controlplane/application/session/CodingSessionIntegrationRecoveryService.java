package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.session.integration.model.IntegrationStatus;
import com.codingreview.controlplane.session.integration.persistence.CodingSessionIntegrationRepository;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/** 服务启动时核对中断合并与原仓库真实 HEAD，并收敛持久状态。 */
@Component
@Order(2)
public final class CodingSessionIntegrationRecoveryService implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            CodingSessionIntegrationRecoveryService.class
    );

    private final CodingSessionRepository sessions;
    private final CodingSessionIntegrationRepository integrations;
    private final CodingSessionIntegrationService service;

    /** 装配合并恢复所需的事实仓储和 Git 服务。 */
    public CodingSessionIntegrationRecoveryService(
            CodingSessionRepository sessions,
            CodingSessionIntegrationRepository integrations,
            CodingSessionIntegrationService service
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.integrations = Objects.requireNonNull(integrations, "integrations");
        this.service = Objects.requireNonNull(service, "service");
    }

    /** 检查所有 MERGING 快照并判断已完成、可重试或需人工处理。 */
    @Override
    public void run(ApplicationArguments args) {
        int merged = 0;
        int interrupted = 0;
        int attention = 0;
        for (var integration : integrations.findAll()) {
            if (integration.status() != IntegrationStatus.MERGING) {
                continue;
            }
            var session = sessions.find(integration.sessionId()).orElse(null);
            if (session == null) {
                service.saveIntegration(integration.needsAttention(
                        "SESSION_SNAPSHOT_MISSING",
                        "合并事实存在，但 Session 快照缺失，请人工检查。",
                        Instant.now()
                ));
                attention++;
                continue;
            }
            try {
                String sourceHead = service.sourceHead(session);
                if (integration.sessionCommit() != null
                        && sourceHead.equals(integration.sessionCommit())) {
                    service.completeRecoveredMerge(session, integration, sourceHead);
                    merged++;
                } else if (sourceHead.equals(integration.sourceHeadBefore())) {
                    service.saveIntegration(integration.interrupted(
                            "INTEGRATION_INTERRUPTED",
                            "服务在原仓库变化前中断，可以重新检查后重试。",
                            Instant.now()
                    ));
                    interrupted++;
                } else {
                    service.saveIntegration(integration.needsAttention(
                            "SOURCE_STATE_UNEXPECTED",
                            "原仓库 HEAD 与合并前后状态均不一致，请人工检查。",
                            Instant.now()
                    ));
                    attention++;
                }
            } catch (RuntimeException exception) {
                LOGGER.error("恢复 Session 合并状态失败，sessionId={}",
                        integration.sessionId(), exception);
                throw exception;
            }
        }
        LOGGER.info("Session 合并恢复完成，已完成={}，可重试={}，需人工检查={}",
                merged, interrupted, attention);
    }
}
