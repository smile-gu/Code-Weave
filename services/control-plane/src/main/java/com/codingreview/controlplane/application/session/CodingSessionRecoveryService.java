package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Objects;

/** 服务启动时修正未完成初始化和 Worktree 丢失的持久 Session。 */
@Component
public class CodingSessionRecoveryService implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodingSessionRecoveryService.class);

    private final CodingSessionRepository repository;

    /** 装配需要扫描和更新的 Session 仓储。 */
    public CodingSessionRecoveryService(CodingSessionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** 扫描平台快照并修正无法继续使用的 Session。 */
    @Override
    public void run(ApplicationArguments args) {
        int recovered = 0;
        int failed = 0;
        for (CodingSession session : repository.findAll()) {
            if (session.status() == CodingSessionStatus.INITIALIZING) {
                repository.save(session.fail(
                        "SESSION_INITIALIZATION_INTERRUPTED",
                        "服务在 Session 初始化完成前重启，本次初始化已停止。",
                        Instant.now()
                ));
                failed++;
            } else if (session.status() == CodingSessionStatus.ACTIVE
                    && !Files.isDirectory(session.worktreePath())) {
                repository.save(session.fail(
                        "SESSION_WORKTREE_MISSING",
                        "Session 的隔离 Worktree 已不存在，无法继续 Coding。",
                        Instant.now()
                ));
                failed++;
            } else {
                recovered++;
            }
        }
        LOGGER.info("Coding Session 恢复完成，可用={}，修正失败={}", recovered, failed);
    }
}
