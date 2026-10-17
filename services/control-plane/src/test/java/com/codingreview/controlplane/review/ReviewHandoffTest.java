package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.codingreview.controlplane.application.session.*;
import com.codingreview.controlplane.session.model.*;
import com.codingreview.controlplane.session.persistence.*;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class ReviewHandoffTest {
    @TempDir Path root;

    @Test
    void preparationPreservesUserRevisionAndOriginalEvidenceWithoutStartingCoding()
            throws Exception {
        var fixture = fixture();
        var input = fixture.input();
        var run = input.run();
        var service = fixture.service();
        var revision = fixture.revision();
        var edited = revision.findingReviews().getFirst();
        var coding = fixture.coding();
        byte[] original = input.store().result(run);
        var request =
                new ReviewHandoff.PrepareRequest(UUID.randomUUID(), 2, revision.revisionDigest());
        var view = service.prepare(run.sessionId(), run.runId(), request);
        assertThat(view.lifecycle().status()).isEqualTo(ReviewHandoff.Status.PREPARED);
        assertThat(view.content().materials().getFirst().review()).isEqualTo(edited);
        assertThat(view.content().materials().getFirst().originalFindingJson()).contains("RISK");
        assertThat(view.content().materials().getFirst().evidenceJson()).isNotEmpty();
        assertThat(service.prepare(run.sessionId(), run.runId(), request)).isEqualTo(view);
        assertThat(service.get(run.sessionId(), run.runId(), view.content().handoffId()))
                .isEqualTo(view);
        assertThat(input.store().result(run)).containsExactly(original);
        verifyNoInteractions(coding);
    }

    record Fixture(
            ReportTestInput.Imported input,
            ReviewReportService reports,
            ReviewReport.Revision revision,
            CodingTurnApplicationService coding,
            CodingTurnRepository turns,
            ReviewHandoffService service,
            CodingSessionRepository sessions,
            SessionOperationLockManager locks) {}

    private Fixture fixture() throws Exception {
        var input = ReportTestInput.imported(root, "RISK");
        var run = input.run();
        var sessions =
                new FileCodingSessionRepository(
                        root, JsonMapper.builder().findAndAddModules().build());
        sessions.save(
                CodingSession.initializing(
                                run.sessionId(),
                                "handoff",
                                root,
                                "main",
                                new ChangePolicy(List.of(), List.of(".git/**")),
                                Instant.now())
                        .activateRealRepository(
                                new GitRepositorySnapshot(root, "main", "a".repeat(40), true),
                                Instant.now()));
        var locks = new SessionOperationLockManager();
        var reports = new ReviewReportService(sessions, locks, input.store());
        var revision =
                reports.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        var finding = revision.findingReviews().getFirst();
        var edited =
                new ReviewReport.FindingReview(
                        finding.findingId(),
                        finding.originalDisposition(),
                        "human title",
                        "human description",
                        "human fix",
                        "human note",
                        ReviewReport.Decision.APPROVED_FOR_FIX);
        revision =
                reports.edit(
                                run.sessionId(),
                                run.runId(),
                                new ReviewReport.EditRequest(
                                        1,
                                        UUID.randomUUID(),
                                        List.of(edited),
                                        "human requirements"))
                        .reportRevision();
        reports.approve(
                run.sessionId(),
                run.runId(),
                new ReviewReport.ApprovalRequest(
                        2,
                        revision.revisionDigest(),
                        UUID.randomUUID(),
                        List.of(finding.findingId()),
                        true,
                        "approval requirements"));
        var coding = mock(CodingTurnApplicationService.class);
        var turns =
                new FileCodingTurnRepository(
                        root, JsonMapper.builder().findAndAddModules().build());
        var service =
                new ReviewHandoffService(
                        sessions,
                        locks,
                        input.store(),
                        reports,
                        new ReviewExecutionGuard(input.store()),
                        coding,
                        turns,
                        (session, execution) -> {});
        return new Fixture(input, reports, revision, coding, turns, service, sessions, locks);
    }

    private ReviewHandoff.View prepare(Fixture fixture) {
        var run = fixture.input().run();
        return fixture.service()
                .prepare(
                        run.sessionId(),
                        run.runId(),
                        new ReviewHandoff.PrepareRequest(
                                UUID.randomUUID(),
                                fixture.revision().revision(),
                                fixture.revision().revisionDigest()));
    }

    @Test
    void explicitStartKeepsOneTurnEvenWhenItFailsToQueueAndResponseIsLost() throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        when(f.coding().submitHandoff(any(), any(), any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            var turn =
                                    CodingTurn.queued(
                                                    invocation.getArgument(1),
                                                    run.sessionId(),
                                                    1,
                                                    invocation.getArgument(3),
                                                    Instant.now())
                                            .withSourceHandoff(invocation.getArgument(2))
                                            .failBeforeStart(
                                                    "TURN_QUEUE_REJECTED",
                                                    "not queued",
                                                    Instant.now());
                            f.turns().save(turn);
                            throw new IllegalStateException("response lost");
                        });
        var request =
                new ReviewHandoff.StartRequest(
                        UUID.randomUUID(), prepared.content().contentDigest());
        var started =
                f.service()
                        .start(
                                run.sessionId(),
                                run.runId(),
                                prepared.content().handoffId(),
                                request);
        assertThat(started.lifecycle().status()).isEqualTo(ReviewHandoff.Status.STARTED);
        assertThat(
                        f.service()
                                .start(
                                        run.sessionId(),
                                        run.runId(),
                                        prepared.content().handoffId(),
                                        request))
                .isEqualTo(started);
        assertThat(f.turns().findAll(run.sessionId())).hasSize(1);
        assertThat(f.turns().findAll(run.sessionId()).getFirst().sourceHandoffId())
                .isEqualTo(prepared.content().handoffId());
        verify(f.coding(), times(1)).submitHandoff(any(), any(), any(), anyString());
    }

    @Test
    void newRevisionSupersedesPreparedContentAndRevocationNeverStartsCoding() throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        f.reports()
                .edit(
                        run.sessionId(),
                        run.runId(),
                        new ReviewReport.EditRequest(
                                2,
                                UUID.randomUUID(),
                                f.revision().findingReviews(),
                                "changed requirements"));
        assertThat(
                        f.service()
                                .get(run.sessionId(), run.runId(), prepared.content().handoffId())
                                .lifecycle()
                                .status())
                .isEqualTo(ReviewHandoff.Status.SUPERSEDED);
        assertThatThrownBy(
                        () ->
                                f.service()
                                        .start(
                                                run.sessionId(),
                                                run.runId(),
                                                prepared.content().handoffId(),
                                                new ReviewHandoff.StartRequest(
                                                        UUID.randomUUID(),
                                                        prepared.content().contentDigest())))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("HANDOFF_NOT_STARTABLE");
        verifyNoInteractions(f.coding());
    }

    private ReviewHandoffService service(
            Fixture f, ReviewExecutionStore store, ReviewHandoffService.WorkspaceCheck check) {
        return new ReviewHandoffService(
                f.sessions(),
                f.locks(),
                store,
                new ReviewReportService(f.sessions(), f.locks(), store),
                new ReviewExecutionGuard(store),
                f.coding(),
                f.turns(),
                check);
    }

    @Test
    void absentTurnAfterLostSubmissionRetainsIntentBlocksEditsAndOnlyExplicitRetryDispatches()
            throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        var request =
                new ReviewHandoff.StartRequest(
                        UUID.randomUUID(), prepared.content().contentDigest());
        when(f.coding().submitHandoff(any(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("before turn write"));
        assertThatThrownBy(
                        () ->
                                f.service()
                                        .start(
                                                run.sessionId(),
                                                run.runId(),
                                                prepared.content().handoffId(),
                                                request))
                .isInstanceOf(IllegalStateException.class);
        var starting =
                f.service().get(run.sessionId(), run.runId(), prepared.content().handoffId());
        assertThat(starting.lifecycle().status()).isEqualTo(ReviewHandoff.Status.STARTING);
        assertThatThrownBy(
                        () ->
                                f.reports()
                                        .edit(
                                                run.sessionId(),
                                                run.runId(),
                                                new ReviewReport.EditRequest(
                                                        2,
                                                        UUID.randomUUID(),
                                                        f.revision().findingReviews(),
                                                        "bypass")))
                .hasMessageContaining("HANDOFF_START_UNCERTAIN");
        assertThatThrownBy(
                        () ->
                                f.service()
                                        .revoke(
                                                run.sessionId(),
                                                run.runId(),
                                                prepared.content().handoffId(),
                                                new ReviewHandoff.RevokeRequest(
                                                        UUID.randomUUID(),
                                                        prepared.content().contentDigest())))
                .hasMessageContaining("HANDOFF_START_UNCERTAIN");
        clearInvocations(f.coding());
        var restarted = service(f, f.input().store(), (session, execution) -> {});
        restarted.recover();
        assertThat(restarted.get(run.sessionId(), run.runId(), prepared.content().handoffId()))
                .isEqualTo(starting);
        verifyNoInteractions(f.coding());
        doAnswer(
                        invocation -> {
                            var turn =
                                    CodingTurn.queued(
                                                    invocation.getArgument(1),
                                                    run.sessionId(),
                                                    1,
                                                    invocation.getArgument(3),
                                                    Instant.now())
                                            .withSourceHandoff(invocation.getArgument(2));
                            f.turns().save(turn);
                            return turn;
                        })
                .when(f.coding())
                .submitHandoff(any(), any(), any(), anyString());
        var result =
                restarted.start(
                        run.sessionId(), run.runId(), prepared.content().handoffId(), request);
        assertThat(result.lifecycle().turnId()).isEqualTo(starting.lifecycle().turnId());
        assertThat(result.lifecycle().status()).isEqualTo(ReviewHandoff.Status.STARTED);
    }

    @Test
    void workspaceDriftPersistsBlockedAndNeverSubmitsOrPermitsForceRetry() throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        var blocked =
                service(
                        f,
                        f.input().store(),
                        (session, execution) -> {
                            throw new com.codingreview.controlplane.review.snapshot
                                    .SnapshotException("SNAPSHOT_SOURCE_DRIFTED");
                        });
        var request =
                new ReviewHandoff.StartRequest(
                        UUID.randomUUID(), prepared.content().contentDigest());
        assertThatThrownBy(
                        () ->
                                blocked.start(
                                        run.sessionId(),
                                        run.runId(),
                                        prepared.content().handoffId(),
                                        request))
                .hasMessageContaining("HANDOFF_WORKSPACE_DRIFTED");
        assertThat(
                        blocked.get(run.sessionId(), run.runId(), prepared.content().handoffId())
                                .lifecycle()
                                .status())
                .isEqualTo(ReviewHandoff.Status.START_BLOCKED);
        assertThatThrownBy(
                        () ->
                                f.service()
                                        .start(
                                                run.sessionId(),
                                                run.runId(),
                                                prepared.content().handoffId(),
                                                request))
                .hasMessageContaining("HANDOFF_NOT_STARTABLE");
        verify(f.coding(), never()).submitHandoff(any(), any(), any(), anyString());
    }

    @Test
    void revokedOrClosedHandoffsRemainReadOnlyAndOriginalBytesStayImmutable() throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        var revoked =
                f.service()
                        .revoke(
                                run.sessionId(),
                                run.runId(),
                                prepared.content().handoffId(),
                                new ReviewHandoff.RevokeRequest(
                                        UUID.randomUUID(), prepared.content().contentDigest()));
        assertThat(revoked.lifecycle().status()).isEqualTo(ReviewHandoff.Status.REVOKED);
        assertThat(revoked.content()).isEqualTo(prepared.content());
        assertThatThrownBy(
                        () ->
                                f.service()
                                        .start(
                                                run.sessionId(),
                                                run.runId(),
                                                prepared.content().handoffId(),
                                                new ReviewHandoff.StartRequest(
                                                        UUID.randomUUID(),
                                                        prepared.content().contentDigest())))
                .hasMessageContaining("HANDOFF_NOT_STARTABLE");
        var session = f.sessions().find(run.sessionId()).orElseThrow();
        f.sessions().save(session.close(Instant.now()));
        assertThat(f.service().get(run.sessionId(), run.runId(), prepared.content().handoffId()))
                .isEqualTo(revoked);
        assertThatThrownBy(
                        () ->
                                f.service()
                                        .prepare(
                                                run.sessionId(),
                                                run.runId(),
                                                new ReviewHandoff.PrepareRequest(
                                                        UUID.randomUUID(),
                                                        2,
                                                        f.revision().revisionDigest())))
                .hasMessageContaining("关闭");
        verifyNoInteractions(f.coding());
    }

    @Test
    void publishedTurnIsReconciledAtRestartWithoutQueuingAgain() throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        var failHead = new java.util.concurrent.atomic.AtomicBoolean();
        var store =
                new ReviewExecutionStore(
                        root,
                        (source, target) -> {
                            if (failHead.get()
                                    && target.getFileName().toString().equals("report-head.json"))
                                throw new java.io.IOException("head unavailable");
                            Files.move(
                                    source,
                                    target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        });
        var service = service(f, store, (session, execution) -> {});
        when(f.coding().submitHandoff(any(), any(), any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            var turn =
                                    CodingTurn.queued(
                                                    invocation.getArgument(1),
                                                    run.sessionId(),
                                                    1,
                                                    invocation.getArgument(3),
                                                    Instant.now())
                                            .withSourceHandoff(invocation.getArgument(2))
                                            .interrupted(Instant.now());
                            f.turns().save(turn);
                            failHead.set(true);
                            return turn;
                        });
        assertThatThrownBy(
                        () ->
                                service.start(
                                        run.sessionId(),
                                        run.runId(),
                                        prepared.content().handoffId(),
                                        new ReviewHandoff.StartRequest(
                                                UUID.randomUUID(),
                                                prepared.content().contentDigest())))
                .hasMessageContaining("REVIEW_STATE_WRITE_UNCERTAIN");
        assertThat(
                        f.service()
                                .get(run.sessionId(), run.runId(), prepared.content().handoffId())
                                .lifecycle()
                                .status())
                .isEqualTo(ReviewHandoff.Status.STARTING);
        clearInvocations(f.coding());
        f.service().recover();
        assertThat(
                        f.service()
                                .get(run.sessionId(), run.runId(), prepared.content().handoffId())
                                .lifecycle()
                                .status())
                .isEqualTo(ReviewHandoff.Status.STARTED);
        verifyNoInteractions(f.coding());
        assertThat(f.turns().findAll(run.sessionId())).hasSize(1);
    }

    @Test
    void productionCodingEntryUsesSameQueueAndPreservesProvenanceOnRejectedQueue()
            throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        var guard = new ReviewExecutionGuard(f.input().store());
        var coding =
                new CodingTurnApplicationService(
                        f.sessions(),
                        f.turns(),
                        mock(
                                com.codingreview.controlplane.workflow.coding.CodingTurnOrchestrator
                                        .class),
                        mock(
                                com.codingreview.controlplane.workspace.change.GitChangeCollector
                                        .class),
                        mock(com.codingreview.controlplane.observability.run.RunEventJournal.class),
                        JsonMapper.builder().findAndAddModules().build(),
                        task -> {
                            throw new java.util.concurrent.RejectedExecutionException(
                                    "offline queue");
                        },
                        () ->
                                com.codingreview.controlplane.runtime.readiness.model
                                        .RuntimeReadiness.from(Instant.now(), List.of()),
                        mock(
                                com.codingreview.controlplane.session.guard.SessionWorkspaceGuard
                                        .class),
                        f.locks(),
                        guard);
        assertThatThrownBy(() -> coding.submit(run.sessionId(), "ordinary prompt bypass"))
                .hasMessageContaining("显式关闭人工审阅");
        var service =
                new ReviewHandoffService(
                        f.sessions(),
                        f.locks(),
                        f.input().store(),
                        f.reports(),
                        guard,
                        coding,
                        f.turns(),
                        (session, execution) -> {});
        var request =
                new ReviewHandoff.StartRequest(
                        UUID.randomUUID(), prepared.content().contentDigest());
        var started =
                service.start(
                        run.sessionId(), run.runId(), prepared.content().handoffId(), request);
        var turn = f.turns().find(run.sessionId(), started.lifecycle().turnId()).orElseThrow();
        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.failureCode()).isEqualTo("TURN_QUEUE_REJECTED");
        assertThat(turn.sourceHandoffId()).isEqualTo(prepared.content().handoffId());
        assertThat(turn.userPrompt())
                .contains("human requirements", "approval requirements", "RISK", "不受信的数据");
        assertThat(
                        service.start(
                                run.sessionId(),
                                run.runId(),
                                prepared.content().handoffId(),
                                request))
                .isEqualTo(started);
        assertThat(f.turns().findAll(run.sessionId())).hasSize(1);
    }

    @Test
    void unknownReviewResourcesBlockStartWithoutErasingProtectionOrAllocatingTurn()
            throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        var guard = new ReviewExecutionGuard(f.input().store());
        guard.uncertain(run.sessionId());
        var service =
                new ReviewHandoffService(
                        f.sessions(),
                        f.locks(),
                        f.input().store(),
                        f.reports(),
                        guard,
                        f.coding(),
                        f.turns(),
                        (session, execution) -> {});
        assertThatThrownBy(
                        () ->
                                service.start(
                                        run.sessionId(),
                                        run.runId(),
                                        prepared.content().handoffId(),
                                        new ReviewHandoff.StartRequest(
                                                UUID.randomUUID(),
                                                prepared.content().contentDigest())))
                .hasMessageContaining("Review 提交");
        assertThat(f.turns().findAll(run.sessionId())).isEmpty();
        assertThat(guard.blocks(run.sessionId())).isTrue();
        assertThat(
                        service.get(run.sessionId(), run.runId(), prepared.content().handoffId())
                                .lifecycle()
                                .status())
                .isEqualTo(ReviewHandoff.Status.PREPARED);
        verifyNoInteractions(f.coding());
    }

    @Test
    void httpReadsAreSideEffectFreeAndStrictRequestsCannotForgeTurnIdentity() throws Exception {
        var f = fixture();
        var run = f.input().run();
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new com.codingreview.controlplane.api.session
                                        .ReviewHandoffController(f.service()))
                        .setControllerAdvice(
                                new com.codingreview.controlplane.api.common.error
                                        .ApiExceptionHandler())
                        .build();
        String path =
                "/api/v1/coding-sessions/"
                        + run.sessionId()
                        + "/review-runs/"
                        + run.runId()
                        + "/handoffs";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .json("[]"));
        var body =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        ReviewJson.parse(
                                ReviewJson.canonical(
                                        new ReviewHandoff.PrepareRequest(
                                                UUID.randomUUID(),
                                                2,
                                                f.revision().revisionDigest())));
        body.put("turnId", UUID.randomUUID().toString());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        path)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(ReviewJson.canonical(body)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        assertThat(f.service().list(run.sessionId(), run.runId())).isEmpty();
        body.remove("turnId");
        var response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post(path)
                                        .contentType(
                                                org.springframework.http.MediaType.APPLICATION_JSON)
                                        .content(ReviewJson.canonical(body)))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .status()
                                        .isOk())
                        .andReturn();
        var view =
                ReviewReport.decode(
                        response.getResponse().getContentAsByteArray(),
                        ReviewHandoff.View.class,
                        "HANDOFF_INTEGRITY_ERROR");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                path + "/" + view.content().handoffId()))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                .string("Cache-Control", "no-store"));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        path + "/" + view.content().handoffId() + "/start")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(
                                        ReviewJson.canonical(
                                                new ReviewHandoff.StartRequest(
                                                        UUID.randomUUID(),
                                                        "sha256:" + "0".repeat(64)))))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isConflict());
        verifyNoInteractions(f.coding());
    }

    @Test
    void orphanHandoffIsNotReadableAndOnlyOriginalPreparationConfirmsIt() throws Exception {
        var f = fixture();
        var run = f.input().run();
        var failure = new java.util.concurrent.atomic.AtomicBoolean(true);
        var store =
                new ReviewExecutionStore(
                        root,
                        (source, target) -> {
                            if (failure.get()
                                    && target.getFileName().toString().equals("report-head.json"))
                                throw new java.io.IOException("head not written");
                            Files.move(
                                    source,
                                    target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        });
        var service = service(f, store, (session, execution) -> {});
        var request =
                new ReviewHandoff.PrepareRequest(
                        UUID.randomUUID(), 2, f.revision().revisionDigest());
        assertThatThrownBy(() -> service.prepare(run.sessionId(), run.runId(), request))
                .hasMessageContaining("REVIEW_STATE_WRITE_UNCERTAIN");
        assertThat(service.list(run.sessionId(), run.runId())).isEmpty();
        failure.set(false);
        assertThatThrownBy(
                        () ->
                                service.prepare(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewHandoff.PrepareRequest(
                                                UUID.randomUUID(),
                                                2,
                                                f.revision().revisionDigest())))
                .hasMessageContaining("HANDOFF_WRITE_UNCERTAIN");
        var confirmed = service.prepare(run.sessionId(), run.runId(), request);
        assertThat(service.get(run.sessionId(), run.runId(), confirmed.content().handoffId()))
                .isEqualTo(confirmed);
        verifyNoInteractions(f.coding());
    }

    @Test
    void concurrentStartsSerializeToOneAssociatedTurnAndUnknownIntentWriteNeverDispatches()
            throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        var store =
                new ReviewExecutionStore(
                        root,
                        (source, target) -> {
                            if (target.getFileName().toString().equals("report-head.json"))
                                throw new java.io.IOException("intent not confirmed");
                            Files.move(
                                    source,
                                    target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        });
        var failing = service(f, store, (session, execution) -> {});
        var request =
                new ReviewHandoff.StartRequest(
                        UUID.randomUUID(), prepared.content().contentDigest());
        assertThatThrownBy(
                        () ->
                                failing.start(
                                        run.sessionId(),
                                        run.runId(),
                                        prepared.content().handoffId(),
                                        request))
                .hasMessageContaining("REVIEW_STATE_WRITE_UNCERTAIN");
        verify(f.coding(), never()).submitHandoff(any(), any(), any(), anyString());
        when(f.coding().submitHandoff(any(), any(), any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            var turn =
                                    CodingTurn.queued(
                                                    invocation.getArgument(1),
                                                    run.sessionId(),
                                                    1,
                                                    invocation.getArgument(3),
                                                    Instant.now())
                                            .withSourceHandoff(invocation.getArgument(2));
                            f.turns().save(turn);
                            return turn;
                        });
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () ->
                                    f.service()
                                            .start(
                                                    run.sessionId(),
                                                    run.runId(),
                                                    prepared.content().handoffId(),
                                                    request));
            var second =
                    executor.submit(
                            () ->
                                    f.service()
                                            .start(
                                                    run.sessionId(),
                                                    run.runId(),
                                                    prepared.content().handoffId(),
                                                    request));
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        verify(f.coding(), times(1)).submitHandoff(any(), any(), any(), anyString());
        assertThat(f.turns().findAll(run.sessionId())).hasSize(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void anotherOpenReportRejectsBeforeIntentAndCanCloseBeforeSameHandoffStarts(
            boolean blockerFirst) throws Exception {
        var f = fixture();
        var run = f.input().run();
        var prepared = prepare(f);
        var sibling = ReportTestInput.imported(root, "VERIFIED", null, run.sessionId());
        var siblingReport =
                f.reports().create(run.sessionId(), sibling.run().runId(), UUID.randomUUID());
        var orderedStore =
                new ReviewExecutionStore(root) {
                    @Override
                    public void scan(
                            UUID sessionId, java.util.function.Consumer<ReviewExecution> consumer) {
                        var values = new ArrayList<ReviewExecution>();
                        super.scan(sessionId, values::add);
                        values.sort(
                                Comparator.comparingInt(
                                        value ->
                                                value.runId().equals(sibling.run().runId())
                                                                == blockerFirst
                                                        ? 0
                                                        : 1));
                        values.forEach(consumer);
                    }
                };
        var guard = new ReviewExecutionGuard(orderedStore);
        var queue = new java.util.concurrent.atomic.AtomicInteger();
        var coding =
                new CodingTurnApplicationService(
                        f.sessions(),
                        f.turns(),
                        mock(
                                com.codingreview.controlplane.workflow.coding.CodingTurnOrchestrator
                                        .class),
                        mock(
                                com.codingreview.controlplane.workspace.change.GitChangeCollector
                                        .class),
                        mock(com.codingreview.controlplane.observability.run.RunEventJournal.class),
                        JsonMapper.builder().findAndAddModules().build(),
                        task -> {
                            queue.incrementAndGet();
                            throw new java.util.concurrent.RejectedExecutionException(
                                    "offline queue");
                        },
                        () ->
                                com.codingreview.controlplane.runtime.readiness.model
                                        .RuntimeReadiness.from(Instant.now(), List.of()),
                        mock(
                                com.codingreview.controlplane.session.guard.SessionWorkspaceGuard
                                        .class),
                        f.locks(),
                        guard);
        var service =
                new ReviewHandoffService(
                        f.sessions(),
                        f.locks(),
                        orderedStore,
                        f.reports(),
                        guard,
                        coding,
                        f.turns(),
                        (session, execution) -> {});
        var request =
                new ReviewHandoff.StartRequest(
                        UUID.randomUUID(), prepared.content().contentDigest());
        assertThatThrownBy(
                        () ->
                                service.start(
                                        run.sessionId(),
                                        run.runId(),
                                        prepared.content().handoffId(),
                                        request))
                .isInstanceOf(
                        com.codingreview.controlplane.application.session.error
                                .CodingSessionConflictException.class);
        assertThat(
                        service.get(run.sessionId(), run.runId(), prepared.content().handoffId())
                                .lifecycle()
                                .status())
                .isEqualTo(ReviewHandoff.Status.PREPARED);
        assertThat(f.turns().findAll(run.sessionId())).isEmpty();
        assertThat(queue.get()).isZero();
        service.recover();
        assertThat(
                        service.get(run.sessionId(), run.runId(), prepared.content().handoffId())
                                .lifecycle()
                                .status())
                .isEqualTo(ReviewHandoff.Status.PREPARED);
        f.reports()
                .close(
                        run.sessionId(),
                        sibling.run().runId(),
                        new ReviewReport.CloseRequest(
                                siblingReport.currentRevision(), UUID.randomUUID()));
        synchronized (f.locks().lock(run.sessionId())) {
            assertThatThrownBy(
                            () ->
                                    guard.assertHandoffInteractionAllowed(
                                            run.sessionId(),
                                            sibling.run().runId(),
                                            prepared.content().handoffId()))
                    .isInstanceOf(
                            com.codingreview.controlplane.application.session.error
                                    .CodingSessionConflictException.class);
            assertThatThrownBy(
                            () ->
                                    guard.assertCodingAllowed(
                                            run.sessionId(), prepared.content().handoffId()))
                    .isInstanceOf(
                            com.codingreview.controlplane.application.session.error
                                    .CodingSessionConflictException.class);
        }
        var started =
                service.start(
                        run.sessionId(), run.runId(), prepared.content().handoffId(), request);
        assertThat(started.lifecycle().status()).isEqualTo(ReviewHandoff.Status.STARTED);
        service.recover();
        assertThat(
                        service.start(
                                run.sessionId(),
                                run.runId(),
                                prepared.content().handoffId(),
                                request))
                .isEqualTo(started);
        assertThat(f.turns().findAll(run.sessionId())).hasSize(1);
        assertThat(queue.get()).isEqualTo(1);
    }
}
