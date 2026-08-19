package com.codingreview.controlplane.application.session;

import com.codingreview.controlplane.session.integration.model.IntegrationStatus;
import com.codingreview.controlplane.session.integration.persistence.FileCodingSessionIntegrationRepository;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.session.model.CodingSessionStatus;
import com.codingreview.controlplane.session.model.CodingTurn;
import com.codingreview.controlplane.session.model.TurnOutcome;
import com.codingreview.controlplane.session.persistence.FileCodingSessionRepository;
import com.codingreview.controlplane.session.persistence.FileCodingTurnRepository;
import com.codingreview.controlplane.workspace.change.GitChangeCollectorImpl;
import com.codingreview.controlplane.workspace.change.WorkspaceFingerprintCalculator;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.worktree.GitWorktreeManagerImpl;
import com.codingreview.controlplane.workspace.worktree.model.WorktreeCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 Git 仓库验证指纹门禁和 Fast-forward 写回。 */
class CodingSessionIntegrationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void mergesExactVerifiedWorkspaceIntoSourceRepository() throws Exception {
        Fixture fixture = fixture();

        MergeReadiness readiness = fixture.service.readiness(fixture.session.sessionId());
        CodingSession merged = fixture.service.merge(
                fixture.session.sessionId(),
                new MergeCodingSessionRequest(
                        "feat: 合并验证修改", readiness.sourceHead(),
                        readiness.targetBranch(), readiness.fingerprint()
                )
        );

        assertThat(readiness.ready()).isTrue();
        assertThat(merged.status()).isEqualTo(CodingSessionStatus.MERGED);
        assertThat(Files.readString(fixture.source.resolve("README.md")))
                .contains("verified change");
        assertThat(git(fixture.source, "rev-parse", "HEAD"))
                .isNotEqualTo(fixture.session.resolvedCommit());
        assertThat(fixture.integrations.find(fixture.session.sessionId()))
                .get().extracting(integration -> integration.status())
                .isEqualTo(IntegrationStatus.MERGED);
    }

    @Test
    void blocksMergeWhenWorkspaceChangesAfterVerification() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(
                fixture.session.worktreePath().resolve("README.md"),
                "\nmanual change", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND
        );

        MergeReadiness readiness = fixture.service.readiness(fixture.session.sessionId());

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers())
                .contains("WORKSPACE_CHANGED_AFTER_VERIFICATION");
        assertThat(git(fixture.source, "rev-parse", "HEAD"))
                .isEqualTo(fixture.session.resolvedCommit());
    }

    @Test
    void returnsBusinessBlockerForFailedSessionWithoutReadingWorktree() throws Exception {
        Fixture fixture = fixture();
        CodingSession failed = fixture.session.fail(
                "TURN_EXECUTION_FAILED", "测试失败 Session", Instant.now().plusSeconds(2)
        );
        fixture.sessions.save(failed);

        MergeReadiness readiness = fixture.service.readiness(fixture.session.sessionId());

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers()).contains("SESSION_NOT_ACTIVE");
        assertThat(readiness.changedFiles()).isZero();
        assertThat(readiness.fingerprint()).isEmpty();
    }

    /** 创建带一轮已验证修改的真实 Session。 */
    private Fixture fixture() throws Exception {
        Path source = tempDir.resolve("source-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(source);
        git(source, "init");
        git(source, "config", "user.name", "Code Weave Test");
        git(source, "config", "user.email", "code-weave@example.test");
        Files.writeString(source.resolve("README.md"), "base\n", StandardCharsets.UTF_8);
        git(source, "add", "README.md");
        git(source, "commit", "-m", "初始化测试仓库");

        Path runtime = tempDir.resolve("runtime-" + UUID.randomUUID()).toAbsolutePath();
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        FileCodingSessionRepository sessions = new FileCodingSessionRepository(runtime, mapper);
        FileCodingTurnRepository turns = new FileCodingTurnRepository(runtime, mapper);
        FileCodingSessionIntegrationRepository integrations =
                new FileCodingSessionIntegrationRepository(runtime, mapper);
        GitWorktreeManagerImpl worktrees = new GitWorktreeManagerImpl(runtime);
        UUID sessionId = UUID.randomUUID();
        CodingSession initializing = CodingSession.initializing(
                sessionId, "测试会话", source, "HEAD",
                new ChangePolicy(List.of("**"), List.of(".git/**")),
                Instant.now()
        );
        CodingSession session = initializing.activate(
                worktrees.create(new WorktreeCreateRequest(
                        source, sessionId.toString(), "HEAD"
                )).handle(),
                Instant.now().plusMillis(1)
        );
        sessions.save(session);
        Files.writeString(
                session.worktreePath().resolve("README.md"),
                "base\nverified change\n", StandardCharsets.UTF_8
        );

        GitChangeCollectorImpl collector = new GitChangeCollectorImpl();
        var changeSet = collector.collect(
                new com.codingreview.controlplane.workspace.worktree.model.WorktreeHandle(
                        session.sourceRepository(), session.worktreePath(), sessionId.toString(),
                        session.baseRef(), session.resolvedCommit(), session.sourceRepositoryDirty()
                )
        );
        String fingerprint = new WorkspaceFingerprintCalculator().calculate(changeSet);
        CodingTurn verified = CodingTurn.queued(
                UUID.randomUUID(), sessionId, 1, "修改 README", Instant.now()
        ).running(Instant.now().plusMillis(1)).complete(
                TurnOutcome.CHANGES_VERIFIED, changeSet.files().size(), fingerprint,
                "Maven 验证通过", "已完成", "claude-session", Instant.now().plusSeconds(1)
        );
        turns.save(verified);

        CodingSessionIntegrationService service = new CodingSessionIntegrationService(
                sessions, turns, integrations, collector
        );
        return new Fixture(source, session, sessions, integrations, service);
    }

    /** 执行测试使用的 Git 命令。 */
    private String git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    /** 聚合单个测试场景的仓储和领域对象。 */
    private record Fixture(
            Path source,
            CodingSession session,
            FileCodingSessionRepository sessions,
            FileCodingSessionIntegrationRepository integrations,
            CodingSessionIntegrationService service
    ) { }
}
