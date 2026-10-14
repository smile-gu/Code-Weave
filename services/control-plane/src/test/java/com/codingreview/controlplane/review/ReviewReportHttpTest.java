package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.codingreview.controlplane.api.common.error.ApiExceptionHandler;
import com.codingreview.controlplane.api.session.ReviewReportController;
import com.codingreview.controlplane.application.session.SessionOperationLockManager;
import com.codingreview.controlplane.session.model.*;
import com.codingreview.controlplane.session.persistence.FileCodingSessionRepository;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class ReviewReportHttpTest {
    @TempDir Path root;

    @Test
    void httpRejectsStaleOrMalformedWritesAndNeverCreatesFromGet() throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var sessions =
                new FileCodingSessionRepository(
                        root, JsonMapper.builder().findAndAddModules().build());
        sessions.save(
                CodingSession.initializing(
                                run.sessionId(),
                                "HTTP report",
                                root,
                                "main",
                                new ChangePolicy(List.of(), List.of(".git/**")),
                                Instant.now())
                        .activateRealRepository(
                                new GitRepositorySnapshot(root, "main", "a".repeat(40), true),
                                Instant.now()));
        var service =
                new ReviewReportService(sessions, new SessionOperationLockManager(), input.store());
        var mvc =
                MockMvcBuilders.standaloneSetup(new ReviewReportController(service))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
        String path =
                "/api/v1/coding-sessions/"
                        + run.sessionId()
                        + "/review-runs/"
                        + run.runId()
                        + "/report";
        mvc.perform(get(path)).andExpect(status().isNotFound());
        assertThat(input.store().reportHead(run)).isEmpty();
        mvc.perform(
                        post(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"requestId\":\""
                                                + UUID.randomUUID()
                                                + "\",\"findingId\":\"invented\"}"))
                .andExpect(status().isBadRequest());
        var created =
                mvc.perform(
                                post(path)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                ReviewJson.canonical(
                                                        Map.of("requestId", UUID.randomUUID()))))
                        .andExpect(status().isOk())
                        .andReturn();
        var first =
                ReviewReport.decode(
                                created.getResponse().getContentAsByteArray(),
                                ReviewReport.View.class,
                                "REPORT_REQUEST_INVALID")
                        .reportRevision();
        var stale = new ReviewReport.EditRequest(9, UUID.randomUUID(), first.findingReviews(), "");
        mvc.perform(
                        put(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ReviewJson.canonical(stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_REVISION_CONFLICT"));
        var f = first.findingReviews().getFirst();
        var edit =
                new ReviewReport.EditRequest(
                        1,
                        UUID.randomUUID(),
                        List.of(
                                new ReviewReport.FindingReview(
                                        f.findingId(),
                                        f.originalDisposition(),
                                        f.title(),
                                        f.description(),
                                        f.fixInstructions(),
                                        "",
                                        ReviewReport.Decision.APPROVED_FOR_FIX)),
                        "");
        mvc.perform(
                        put(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ReviewJson.canonical(edit)))
                .andExpect(status().isOk());
        var revision = service.get(run.sessionId(), run.runId(), null).reportRevision();
        var wrongHash =
                new ReviewReport.ApprovalRequest(
                        2,
                        ReviewReport.EMPTY_DIGEST,
                        UUID.randomUUID(),
                        List.of(f.findingId()),
                        true,
                        "");
        mvc.perform(
                        post(path + "/approve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ReviewJson.canonical(wrongHash)))
                .andExpect(status().isConflict());
        var request =
                new ReviewReport.ApprovalRequest(
                        2,
                        revision.revisionDigest(),
                        UUID.randomUUID(),
                        List.of(f.findingId()),
                        false,
                        "");
        mvc.perform(
                        post(path + "/approve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ReviewJson.canonical(request)))
                .andExpect(status().isBadRequest());
        sessions.save(sessions.find(run.sessionId()).orElseThrow().close(Instant.now()));
        mvc.perform(get(path + "?revision=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false));
        mvc.perform(
                        put(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ReviewJson.canonical(edit)))
                .andExpect(status().isConflict());
    }
}
