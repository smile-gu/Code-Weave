package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.api.run.error.ApiExceptionHandler;
import com.codingreview.controlplane.application.session.CodingSessionApplicationService;
import com.codingreview.controlplane.application.session.ReviewBaselineApplicationService;
import com.codingreview.controlplane.application.session.ReviewBaselineReadiness;
import com.codingreview.controlplane.application.session.error.CodingSessionCreationException;
import com.codingreview.controlplane.application.session.error.CodingSessionNotFoundException;
import com.codingreview.controlplane.session.model.CodingSession;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodingSessionControllerTest {

    private CodingSessionApplicationService applicationService;
    private ReviewBaselineApplicationService baselineService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        applicationService = mock(CodingSessionApplicationService.class);
        baselineService = mock(ReviewBaselineApplicationService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CodingSessionController(
                        applicationService, baselineService
                ))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createsActiveSessionWithLocation() throws Exception {
        CodingSession session = activeSession(UUID.randomUUID(), Instant.parse("2026-08-18T06:00:00Z"));
        when(applicationService.create(any())).thenReturn(session);

        mockMvc.perform(post("/api/v1/coding-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/v1/coding-sessions/" + session.sessionId()))
                .andExpect(jsonPath("$.sessionId").value(session.sessionId().toString()))
                .andExpect(jsonPath("$.title").value("用户模块开发"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.worktreePath").value("D:\\repository"))
                .andExpect(jsonPath("$.resolvedCommit").value("abc123"))
                .andExpect(jsonPath("$.activeReviewBaselineCommit").value("abc123"))
                .andExpect(jsonPath("$.allowedPaths[0]").value("src/**"));
    }

    @Test
    void returnsReviewBaselineReadiness() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(baselineService.readiness(sessionId)).thenReturn(new ReviewBaselineReadiness(
                sessionId, true, "abc123", "def456", "main", 2,
                "workspace-fingerprint", List.of()
        ));

        mockMvc.perform(get(
                        "/api/v1/coding-sessions/{sessionId}/review-baseline-readiness",
                        sessionId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.activeBaselineCommit").value("abc123"))
                .andExpect(jsonPath("$.currentHead").value("def456"))
                .andExpect(jsonPath("$.changedFiles").value(2));
    }

    @Test
    void advancesReviewBaselineFromConfirmedPreview() throws Exception {
        CodingSession session = directSession(
                UUID.randomUUID(), Instant.parse("2026-08-18T06:00:00Z")
        ).advanceReviewBaseline("def456", Instant.parse("2026-08-18T06:10:00Z"));
        when(baselineService.advance(
                org.mockito.ArgumentMatchers.eq(session.sessionId()), any()
        )).thenReturn(session);

        mockMvc.perform(post(
                        "/api/v1/coding-sessions/{sessionId}/review-baseline/advance",
                        session.sessionId()
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedActiveBaselineCommit": "abc123",
                                  "expectedHead": "def456",
                                  "workspaceFingerprint": "workspace-fingerprint"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedCommit").value("abc123"))
                .andExpect(jsonPath("$.activeReviewBaselineCommit").value("def456"));
    }

    @Test
    void listsRecentSessions() throws Exception {
        CodingSession first = activeSession(UUID.randomUUID(), Instant.parse("2026-08-18T06:00:00Z"));
        CodingSession second = activeSession(UUID.randomUUID(), Instant.parse("2026-08-18T05:00:00Z"));
        when(applicationService.recent(2)).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/v1/coding-sessions").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(2))
                .andExpect(jsonPath("$.sessions[0].sessionId")
                        .value(first.sessionId().toString()))
                .andExpect(jsonPath("$.sessions[1].sessionId")
                        .value(second.sessionId().toString()));
    }

    @Test
    void returnsSessionDetails() throws Exception {
        CodingSession session = activeSession(UUID.randomUUID(), Instant.parse("2026-08-18T06:00:00Z"));
        when(applicationService.get(session.sessionId())).thenReturn(session);

        mockMvc.perform(get("/api/v1/coding-sessions/{sessionId}", session.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(session.sessionId().toString()))
                .andExpect(jsonPath("$.sourceRepository").value("D:\\repository"))
                .andExpect(jsonPath("$.sourceRepositoryDirty").value(false));
    }

    @Test
    void rejectsInvalidCreateRequest() throws Exception {
        mockMvc.perform(post("/api/v1/coding-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors.repository").exists())
                .andExpect(jsonPath("$.fieldErrors.allowedPaths").exists())
                .andExpect(jsonPath("$.fieldErrors.deniedPaths").exists());
    }

    @Test
    void rejectsSessionWithoutAllowedPath() throws Exception {
        mockMvc.perform(post("/api/v1/coding-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repository": "D:/repository",
                                  "baseRef": "HEAD",
                                  "allowedPaths": [],
                                  "deniedPaths": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.allowedPaths").value(
                        "请至少配置一条允许修改的路径规则"));
    }

    @Test
    void rejectsOutOfRangeListLimit() throws Exception {
        mockMvc.perform(get("/api/v1/coding-sessions").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsNotFoundForUnknownSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(applicationService.get(sessionId))
                .thenThrow(new CodingSessionNotFoundException(sessionId));

        mockMvc.perform(get("/api/v1/coding-sessions/{sessionId}", sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        "找不到指定的 Coding Session，请确认会话标识是否正确。"));
    }

    @Test
    void returnsBusinessFailureWithoutDiagnostic() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(applicationService.create(any())).thenThrow(new CodingSessionCreationException(
                sessionId,
                "SOURCE_REPOSITORY_NOT_FOUND",
                "找不到指定的本地 Git 仓库。",
                "D:/missing/repository",
                new IllegalStateException("internal detail")
        ));

        mockMvc.perform(post("/api/v1/coding-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOURCE_REPOSITORY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("找不到指定的本地 Git 仓库。"))
                .andExpect(jsonPath("$.diagnostic").doesNotExist());
    }

    /** 创建直接绑定真实仓库的 API 测试 Session。 */
    private CodingSession activeSession(UUID sessionId, Instant createdAt) {
        Path repository = Path.of("D:/repository");
        return CodingSession.initializing(
                sessionId, "用户模块开发", repository, "main",
                new ChangePolicy(List.of("src/**"), List.of(".git/**")), createdAt
        ).activateRealRepository(
                new GitRepositorySnapshot(repository, "main", "abc123", false),
                createdAt.plusSeconds(1)
        );
    }

    /** 复用直接工作区夹具创建可推进基线的 Session。 */
    private CodingSession directSession(UUID sessionId, Instant createdAt) {
        return activeSession(sessionId, createdAt);
    }

    /** 返回一份合法的 Session 创建请求。 */
    private String validRequest() {
        return """
                {
                  "title": "用户模块开发",
                  "repository": "D:/repository",
                  "baseRef": "HEAD",
                  "allowedPaths": ["src/**"],
                  "deniedPaths": [".git/**"]
                }
                """;
    }
}
