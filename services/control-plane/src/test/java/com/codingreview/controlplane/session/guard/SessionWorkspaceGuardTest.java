package com.codingreview.controlplane.session.guard;

import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.GitRepositoryInspector;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 Coding Turn 的分支与 HEAD 防漂移门禁。 */
class SessionWorkspaceGuardTest {
    @Test
    void allowsUncommittedChangesWhenBranchAndHeadRemainStable() {
        GitRepositoryInspector inspector = mock(GitRepositoryInspector.class);
        Path repository = Path.of("D:/test/repository").toAbsolutePath();
        CodingSession session = session(repository, "main", "abc123");
        when(inspector.inspect(repository)).thenReturn(
                new GitRepositorySnapshot(repository, "main", "abc123", true));

        new SessionWorkspaceGuard(inspector).assertStable(session);
    }

    @Test
    void rejectsChangedBranch() {
        GitRepositoryInspector inspector = mock(GitRepositoryInspector.class);
        Path repository = Path.of("D:/test/repository").toAbsolutePath();
        CodingSession session = session(repository, "main", "abc123");
        when(inspector.inspect(repository)).thenReturn(
                new GitRepositorySnapshot(repository, "feature/new", "abc123", false));

        assertThatThrownBy(() -> new SessionWorkspaceGuard(inspector).assertStable(session))
                .isInstanceOf(SessionWorkspaceDriftException.class)
                .hasMessageContaining("分支已从 main 变为 feature/new");
    }

    @Test
    void rejectsChangedHead() {
        GitRepositoryInspector inspector = mock(GitRepositoryInspector.class);
        Path repository = Path.of("D:/test/repository").toAbsolutePath();
        CodingSession session = session(repository, "main", "abc123");
        when(inspector.inspect(repository)).thenReturn(
                new GitRepositorySnapshot(repository, "main", "def456", false));

        assertThatThrownBy(() -> new SessionWorkspaceGuard(inspector).assertStable(session))
                .isInstanceOf(SessionWorkspaceDriftException.class)
                .hasMessageContaining("HEAD 已从 abc123 变为 def456");
    }

    private CodingSession session(Path repository, String branch, String head) {
        Instant now = Instant.parse("2026-08-18T06:00:00Z");
        return CodingSession.initializing(
                UUID.randomUUID(), "测试会话", repository, branch,
                new ChangePolicy(List.of("src/**"), List.of("target/**")), now
        ).activateRealRepository(new GitRepositorySnapshot(repository, branch, head, false), now.plusSeconds(1));
    }
}
