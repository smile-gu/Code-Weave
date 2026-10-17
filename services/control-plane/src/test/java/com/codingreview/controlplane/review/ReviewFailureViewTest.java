package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewFailureViewTest {
    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1.1"})
    void projectsValidatedFailureCodeWithoutRewritingHistoryOrExposingMessage(String protocol) {
        var run = mock(ReviewExecution.class);
        var store = mock(ReviewExecutionStore.class);
        var id = UUID.randomUUID();
        when(run.runId()).thenReturn(id);
        when(run.state()).thenReturn("FAILED");
        when(run.eventCursor()).thenReturn(1L);
        when(run.wire()).thenReturn(new LinkedHashMap<>());
        when(run.requestBytes()).thenReturn(ReviewJson.canonical(Map.of(
                "protocolVersion", protocol, "reviewPlan", Map.of("selectedPasses", java.util.List.of()))));
        when(store.event(run, 1)).thenReturn(ReviewJson.canonical(Map.of(
                "protocolVersion", protocol, "runId", id.toString(), "sequence", 1,
                "eventType", "REVIEW_RUN_FAILED", "occurredAt", "2026-10-17T07:11:23.000000Z",
                "payload", Map.of("status", "FAILED", "resourceState", "RELEASED",
                        "failure", Map.of("code", "EVIDENCE_REFERENCE_INVALID",
                                "message", "Internal diagnostic text", "diagnosticId", UUID.randomUUID().toString())))));
        var service = new ReviewApplicationService(null, null, null, null, null, null, null, store, null, null, null);
        var view = service.view(run);
        assertThat(view.get("diagnosticCode")).isEqualTo("EVIDENCE_REFERENCE_INVALID");
        assertThat(view.toString()).doesNotContain("Internal diagnostic text");
        verify(store, never()).save(any());
    }
}
