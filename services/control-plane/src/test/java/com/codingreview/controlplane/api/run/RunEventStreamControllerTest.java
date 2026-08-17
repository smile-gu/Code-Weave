package com.codingreview.controlplane.api.run;

import com.codingreview.controlplane.api.run.stream.RunEventSseService;
import com.codingreview.controlplane.application.run.M0RunApplicationService;
import com.codingreview.controlplane.observability.run.FileRunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.observability.run.stream.PublishingRunEventJournal;
import com.codingreview.controlplane.observability.run.stream.RunEventStreamHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/** 验证 SSE 的历史补发、实时通知和断点恢复契约。 */
class RunEventStreamControllerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @TempDir
    Path tempDir;

    private ExecutorService executor;
    private RunEventJournal journal;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RunEventStreamHub hub = new RunEventStreamHub();
        journal = new PublishingRunEventJournal(
                new FileRunEventJournal(tempDir, objectMapper), hub
        );
        executor = Executors.newVirtualThreadPerTaskExecutor();
        M0RunApplicationService applicationService = mock(M0RunApplicationService.class);
        RunEventSseService sseService = new RunEventSseService(
                applicationService, journal, hub, executor
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RunEventStreamController(sseService))
                .build();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void replaysHistoryThenStreamsNewTerminalEvent() throws Exception {
        UUID runId = UUID.randomUUID();
        journal.append(runId.toString(), "run", 1, RunEventType.RUN_CREATED,
                objectMapper.createObjectNode().put("message", "created"));

        MvcResult pending = mockMvc.perform(get("/api/v1/m0/runs/{runId}/stream", runId))
                .andExpect(request().asyncStarted())
                .andReturn();

        journal.append(runId.toString(), "run", 1, RunEventType.RUN_COMPLETED,
                objectMapper.createObjectNode().put("message", "completed"));
        MvcResult completed = mockMvc.perform(asyncDispatch(pending)).andReturn();
        String body = completed.getResponse().getContentAsString();

        assertThat(body)
                .contains("id:1", "id:2")
                .contains("event:run-event")
                .contains("RUN_CREATED", "RUN_COMPLETED");
        assertThat(body.indexOf("id:1")).isLessThan(body.indexOf("id:2"));
    }

    @Test
    void resumesAfterLargestAvailableCursor() throws Exception {
        UUID runId = UUID.randomUUID();
        journal.append(runId.toString(), "run", 1, RunEventType.RUN_CREATED,
                objectMapper.createObjectNode());
        journal.append(runId.toString(), "run", 1, RunEventType.RUN_QUEUED,
                objectMapper.createObjectNode());
        journal.append(runId.toString(), "run", 1, RunEventType.RUN_FAILED,
                objectMapper.createObjectNode());

        MvcResult pending = mockMvc.perform(get("/api/v1/m0/runs/{runId}/stream", runId)
                        .param("afterSequence", "1")
                        .header("Last-Event-ID", "2"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(pending))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("id:1", "id:2")
                .contains("id:3", "RUN_FAILED");
    }
}
