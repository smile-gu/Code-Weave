package com.codingreview.controlplane.api.run;

import com.codingreview.controlplane.api.run.error.ApiExceptionHandler;
import com.codingreview.controlplane.application.run.M0RunApplicationService;
import com.codingreview.controlplane.application.run.RunExecutionSnapshot;
import com.codingreview.controlplane.application.run.RunExecutionStatus;
import com.codingreview.controlplane.application.run.error.RunNotFoundException;
import com.codingreview.controlplane.application.run.error.RunOutputUnavailableException;
import com.codingreview.controlplane.application.run.error.RunOutputUnavailableReason;
import com.codingreview.controlplane.application.run.error.RunQueueFullException;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.runtime.readiness.error.RuntimeNotReadyException;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheck;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeCheckStatus;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeComponent;
import com.codingreview.controlplane.runtime.readiness.model.RuntimeReadiness;
import com.codingreview.controlplane.workflow.m0.model.M0RunFailureCode;
import com.codingreview.controlplane.workflow.m0.model.M0RunResult;
import com.codingreview.controlplane.workflow.m0.model.M0RunStage;
import com.codingreview.controlplane.workflow.m0.model.M0RunStatus;
import com.codingreview.controlplane.workspace.change.model.GitChangeSet;
import com.codingreview.controlplane.workspace.change.model.GitChangeType;
import com.codingreview.controlplane.workspace.change.model.GitChangedFile;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class M0RunControllerTest {

    private M0RunApplicationService applicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        applicationService = mock(M0RunApplicationService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new M0RunController(applicationService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void acceptsValidRunAsynchronously() throws Exception {
        UUID runId = UUID.randomUUID();
        when(applicationService.submit(any())).thenReturn(queued(runId));

        mockMvc.perform(post("/api/v1/m0/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.statusUrl")
                        .value("/api/v1/m0/runs/" + runId));
    }

    @Test
    void rejectsInvalidRequestWithFieldDetails() throws Exception {
        mockMvc.perform(post("/api/v1/m0/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors.repository").exists())
                .andExpect(jsonPath("$.fieldErrors.allowedPaths").exists());
    }

    @Test
    void returnsNotFoundForUnknownRun() throws Exception {
        UUID runId = UUID.randomUUID();
        when(applicationService.get(runId)).thenThrow(new RunNotFoundException(runId));

        mockMvc.perform(get("/api/v1/m0/runs/{runId}", runId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"))
                .andExpect(jsonPath("$.runId").value(runId.toString()));
    }

    @Test
    void returnsServiceUnavailableWhenQueueIsFull() throws Exception {
        UUID runId = UUID.randomUUID();
        when(applicationService.submit(any()))
                .thenThrow(new RunQueueFullException(runId, null));

        mockMvc.perform(post("/api/v1/m0/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RUN_QUEUE_FULL"))
                .andExpect(jsonPath("$.runId").value(runId.toString()));
    }

    @Test
    void returnsServiceUnavailableWhenRuntimeIsNotReady() throws Exception {
        RuntimeReadiness readiness = RuntimeReadiness.from(
                Instant.now(),
                List.of(new RuntimeCheck(
                        RuntimeComponent.CLAUDE_CODE,
                        RuntimeCheckStatus.FAILED,
                        true,
                        null,
                        null,
                        "Claude Code 不可用。",
                        "请配置 claude.cmd。"
                ))
        );
        when(applicationService.submit(any()))
                .thenThrow(new RuntimeNotReadyException(readiness));

        mockMvc.perform(post("/api/v1/m0/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RUNTIME_NOT_READY"))
                .andExpect(jsonPath("$.runId").doesNotExist());
    }

    @Test
    void returnsEventsWithNextSequenceCursor() throws Exception {
        UUID runId = UUID.randomUUID();
        RunEvent event = new RunEvent(
                RunEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(),
                runId.toString(),
                "run",
                1,
                2,
                Instant.now(),
                RunEventType.RUN_QUEUED,
                JsonMapper.builder().build().createObjectNode()
        );
        when(applicationService.events(runId, 1)).thenReturn(List.of(event));

        mockMvc.perform(get("/api/v1/m0/runs/{runId}/events", runId)
                        .param("afterSequence", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextSequence").value(2))
                .andExpect(jsonPath("$.events[0].type").value("RUN_QUEUED"));
    }

    @Test
    void rejectsNegativeEventCursor() throws Exception {
        UUID runId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/m0/runs/{runId}/events", runId)
                        .param("afterSequence", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsPartialBusinessResultWithoutInternalDiagnostic() throws Exception {
        UUID runId = UUID.randomUUID();
        when(applicationService.result(runId)).thenReturn(failedResult(runId));

        mockMvc.perform(get("/api/v1/m0/runs/{runId}/result", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedStage").value("WORKTREE"))
                .andExpect(jsonPath("$.failureCode").value("WORKTREE_FAILED"))
                .andExpect(jsonPath("$.changesAvailable").value(false))
                .andExpect(jsonPath("$.diagnostic").doesNotExist());
    }

    @Test
    void returnsCollectedUnifiedDiff() throws Exception {
        UUID runId = UUID.randomUUID();
        when(applicationService.changes(runId)).thenReturn(changes(runId));

        mockMvc.perform(get("/api/v1/m0/runs/{runId}/changes", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCommit").value("abc123"))
                .andExpect(jsonPath("$.totalFiles").value(1))
                .andExpect(jsonPath("$.files[0].path")
                        .value("src/main/java/demo/UserService.java"))
                .andExpect(jsonPath("$.files[0].changeType").value("MODIFIED"))
                .andExpect(jsonPath("$.unifiedDiff").value("diff --git sample"));
    }

    @Test
    void returnsConflictWhenRunHasNotFinished() throws Exception {
        UUID runId = UUID.randomUUID();
        when(applicationService.result(runId)).thenThrow(new RunOutputUnavailableException(
                runId, RunOutputUnavailableReason.RUN_NOT_FINISHED
        ));

        mockMvc.perform(get("/api/v1/m0/runs/{runId}/result", runId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FINISHED"))
                .andExpect(jsonPath("$.runId").value(runId.toString()));
    }

    @Test
    void returnsConflictWhenChangesWereNotCollected() throws Exception {
        UUID runId = UUID.randomUUID();
        when(applicationService.changes(runId)).thenThrow(new RunOutputUnavailableException(
                runId, RunOutputUnavailableReason.CHANGES_UNAVAILABLE
        ));

        mockMvc.perform(get("/api/v1/m0/runs/{runId}/changes", runId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGES_UNAVAILABLE"));
    }

    private RunExecutionSnapshot queued(UUID runId) {
        return new RunExecutionSnapshot(
                runId,
                RunExecutionStatus.QUEUED,
                Instant.now(),
                null,
                null,
                "任务已进入执行队列。",
                null
        );
    }

    private M0RunResult failedResult(UUID runId) {
        Instant now = Instant.now();
        return new M0RunResult(
                runId,
                M0RunStatus.FAILED,
                M0RunStage.WORKTREE,
                M0RunFailureCode.WORKTREE_FAILED,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                "无法创建 Worktree。",
                "不得暴露的内部诊断"
        );
    }

    private GitChangeSet changes(UUID runId) {
        return new GitChangeSet(
                "abc123",
                Path.of("D:/runtime/worktrees/" + runId),
                List.of(new GitChangedFile(
                        "src/main/java/demo/UserService.java",
                        null,
                        GitChangeType.MODIFIED,
                        false
                )),
                "diff --git sample",
                18
        );
    }

    private String validRequest() {
        return """
                {
                  "repository": "D:/repository",
                  "baseRef": "HEAD",
                  "taskPrompt": "修复示例问题",
                  "acceptanceCriteria": ["测试通过"],
                  "allowedPaths": ["src/**"],
                  "deniedPaths": [".git/**"]
                }
                """;
    }
}
