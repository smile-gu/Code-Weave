package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.application.session.error.CodingSessionConflictException;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnOutcome;
import com.codingreview.controlplane.session.model.TurnStatus;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.codingreview.controlplane.session.persistence.CodingTurnRepository;
import com.codingreview.controlplane.workspace.change.GitChangeCollector;
import com.codingreview.controlplane.workspace.change.WorkspaceFingerprintCalculator;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspector;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.codingreview.controlplane.workspace.model.WorkspaceHandle;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 校验用户完成 Review 与自测后的模块提交，并推进活动审查基线。 */
@Service
public final class ReviewBaselineApplicationService {
    private final CodingSessionRepository sessions;
    private final CodingTurnRepository turns;
    private final GitRepositoryInspector inspector;
    private final GitChangeCollector changes;
    private final WorkspaceFingerprintCalculator fingerprints;
    private final SessionOperationLockManager operationLocks;
    private final CodingIdleSafety codingSafety;
    private final com.codingreview.controlplane.review.ReviewExecutionGuard reviewGuard;

    /** 装配 Session 事实、Git 检查器和共享互斥锁。 */
    public ReviewBaselineApplicationService(
            CodingSessionRepository sessions,
            CodingTurnRepository turns,
            GitRepositoryInspector inspector,
            GitChangeCollector changes,
            SessionOperationLockManager operationLocks,
            CodingIdleSafety codingSafety,
            com.codingreview.controlplane.review.ReviewExecutionGuard reviewGuard
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.operationLocks = Objects.requireNonNull(operationLocks, "operationLocks");
        this.fingerprints = new WorkspaceFingerprintCalculator();
        this.codingSafety = codingSafety;
        this.reviewGuard = reviewGuard;
    }

    /** 重新读取 Session、Turn 和 Git 事实并生成基线推进预览。 */
    public ReviewBaselineReadiness readiness(UUID sessionId) {
        synchronized (operationLocks.lock(sessionId)) {
            return evaluate(requireSession(sessionId));
        }
    }

    /** 二次校验预览事实后将当前 HEAD 设为新活动基线。 */
    public CodingSession advance(UUID sessionId, AdvanceReviewBaselineRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (operationLocks.lock(sessionId)) {
            CodingSession session = requireSession(sessionId);
            ReviewBaselineReadiness current = evaluate(session);
            if (!current.ready()) {
                throw new CodingSessionConflictException(
                        "BASELINE_NOT_READY",
                        "当前提交不满足基线推进条件："
                                + String.join(", ", current.blockers())
                );
            }
            if (!current.activeBaselineCommit().equals(request.expectedActiveBaselineCommit())
                    || !current.currentHead().equals(request.expectedHead())
                    || !current.workspaceFingerprint().equals(request.workspaceFingerprint())) {
                throw new CodingSessionConflictException(
                        "BASELINE_PREVIEW_EXPIRED",
                        "Git 状态已经变化，请重新检查后再确认。"
                );
            }
            CodingSession advanced = session.advanceReviewBaseline(
                    current.currentHead(), Instant.now()
            );
            sessions.save(advanced);
            return advanced;
        }
    }

    /** 根据当前持久事实和 Git 快照计算阻断原因。 */
    private ReviewBaselineReadiness evaluate(CodingSession session) {
        List<String> blockers = new ArrayList<>();
        if (session.status() != CodingSessionStatus.ACTIVE) {
            blockers.add("SESSION_NOT_ACTIVE");
        }
        if (!session.usesRealRepository()) {
            blockers.add("DIRECT_WORKSPACE_REQUIRED");
        }
        if (!blockers.isEmpty()) {
            return unavailable(session, blockers);
        }
        try { reviewGuard.assertAvailable(session.sessionId()); codingSafety.assertIdleAndSafe(session.sessionId()); }
        catch (CodingSessionConflictException error) { return unavailable(session, List.of(error.code())); }

        List<CodingTurn> history = turns.findAll(session.sessionId());
        if (history.stream().anyMatch(this::active)) {
            blockers.add("TURN_IN_PROGRESS");
        }
        CodingTurn latest = history.isEmpty() ? null : history.getLast();
        if (latest == null || latest.status() != TurnStatus.COMPLETED
                || latest.outcome() != TurnOutcome.CHANGES_VERIFIED) {
            blockers.add("NO_VERIFIED_TURN");
        }

        GitRepositorySnapshot snapshot = inspector.inspect(session.sourceRepository());
        if (!snapshot.branch().equals(session.baseRef())) {
            blockers.add("SOURCE_BRANCH_CHANGED");
        }
        if (snapshot.headCommit().equals(session.activeReviewBaselineCommit())) {
            blockers.add("NO_NEW_COMMIT");
        } else if (!inspector.isAncestor(
                session.sourceRepository(), session.activeReviewBaselineCommit(), snapshot.headCommit()
        )) {
            blockers.add("HEAD_NOT_DESCENDANT");
        }
        if (snapshot.dirty()) {
            blockers.add("UNCOMMITTED_CHANGES");
        }
        if (blockers.contains("TURN_IN_PROGRESS")) {
            return new ReviewBaselineReadiness(
                    session.sessionId(), false, session.activeReviewBaselineCommit(),
                    snapshot.headCommit(), snapshot.branch(), 0, "", blockers
            );
        }

        GitChangeSet changeSet = changes.collect(new WorkspaceHandle(
                session.sourceRepository(), session.activeReviewBaselineCommit()
        ));
        String fingerprint = fingerprints.calculate(changeSet);
        if (changeSet.isEmpty()) {
            blockers.add("NO_CHANGES");
        }
        if (latest != null && latest.outcome() == TurnOutcome.CHANGES_VERIFIED
                && !fingerprint.equals(latest.workspaceFingerprint())) {
            blockers.add("CHANGES_NOT_VERIFIED");
        }
        return new ReviewBaselineReadiness(
                session.sessionId(), blockers.isEmpty(),
                session.activeReviewBaselineCommit(), snapshot.headCommit(), snapshot.branch(),
                changeSet.files().size(), fingerprint, blockers
        );
    }

    /** 为不可运行的 Session 生成不触发 Git 读取的预览。 */
    private ReviewBaselineReadiness unavailable(
            CodingSession session,
            List<String> blockers
    ) {
        return new ReviewBaselineReadiness(
                session.sessionId(), false,
                session.activeReviewBaselineCommit() == null
                        ? "" : session.activeReviewBaselineCommit(),
                "", "", 0, "", blockers
        );
    }

    /** 读取必须存在的 Coding Session。 */
    private CodingSession requireSession(UUID sessionId) {
        return sessions.find(sessionId).orElseThrow(
                () -> new IllegalArgumentException("Coding Session 不存在。")
        );
    }

    /** 判断 Turn 是否仍在占用 Session 执行权。 */
    private boolean active(CodingTurn turn) {
        return turn.status() == TurnStatus.QUEUED || turn.status() == TurnStatus.RUNNING;
    }
}
