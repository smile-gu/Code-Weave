package com.codingreview.controlplane.api.run;

import com.codingreview.controlplane.api.run.error.ApiExceptionHandler;
import com.codingreview.controlplane.application.run.M0RunApplicationService;
import com.codingreview.controlplane.application.run.RunExecutionSnapshot;
import com.codingreview.controlplane.application.run.RunExecutionStatus;
import com.codingreview.controlplane.application.run.error.RunNotFoundException;
import com.codingreview.controlplane.application.run.error.RunQueueFullException;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
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
