package com.codingreview.controlplane.review;

import static org.assertj.core.api.Assertions.*;

import com.codingreview.controlplane.application.session.SessionOperationLockManager;
import com.codingreview.controlplane.session.model.*;
import com.codingreview.controlplane.session.persistence.FileCodingSessionRepository;
import com.codingreview.controlplane.workspace.change.policy.model.ChangePolicy;
import com.codingreview.controlplane.workspace.git.model.GitRepositorySnapshot;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class ReviewReportTest {
    @TempDir Path root;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2.0,中", "3.0,中", "2.0,😀", "3.0,😀"})
    void legalSourceTextSurvivesCreationAndEditing(String version, String character)
            throws Exception {
        String text = character.repeat(4096);
        var input =
                version.equals("2.0")
                        ? ReportTestInput.legacy(root, text)
                        : ReportTestInput.imported(root, "VERIFIED", text);
        var run = input.run();
        byte[] original = input.store().result(run);
        ReviewProtocol.persisted(input.store(), run);
        var service =
                new ReviewReportService(
                        sessions(run), new SessionOperationLockManager(), input.store());
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        var f = first.findingReviews().getFirst();
        assertThat(f.description()).isEqualTo(text);
        assertThat(f.fixInstructions()).isEqualTo(text);
        var edited =
                new ReviewReport.FindingReview(
                        f.findingId(),
                        f.originalDisposition(),
                        f.title(),
                        f.description(),
                        f.fixInstructions(),
                        "reviewed",
                        ReviewReport.Decision.PENDING);
        var second =
                service.edit(
                                run.sessionId(),
                                run.runId(),
                                new ReviewReport.EditRequest(
                                        1, UUID.randomUUID(), List.of(edited), ""))
                        .reportRevision();
        assertThat(service.get(run.sessionId(), run.runId(), 2).reportRevision()).isEqualTo(second);
        assertThat(second.findingReviews().getFirst().description()).isEqualTo(text);
        assertThat(second.findingReviews().getFirst().fixInstructions()).isEqualTo(text);
        assertThat(input.store().result(run)).containsExactly(original);
        for (boolean description : List.of(true, false)) {
            assertThatThrownBy(
                            () ->
                                    new ReviewReport.FindingReview(
                                            f.findingId(),
                                            f.originalDisposition(),
                                            f.title(),
                                            description ? "😀".repeat(4097) : text,
                                            description ? text : "😀".repeat(4097),
                                            "",
                                            ReviewReport.Decision.PENDING))
                    .isInstanceOf(ReviewException.class)
                    .hasMessageContaining("REPORT_TOO_LARGE");
        }
    }

    @Test
    void explicitReportCreationPreservesOriginalResultAndDefaultsToPending() throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var sessions = sessions(run);
        var service =
                new ReviewReportService(sessions, new SessionOperationLockManager(), input.store());
        byte[] original = input.store().result(run);
        assertThatThrownBy(() -> service.get(run.sessionId(), run.runId(), null))
                .isInstanceOf(ReviewException.class);
        var report = service.create(run.sessionId(), run.runId(), UUID.randomUUID());
        assertThat(report.reportRevision().revision()).isEqualTo(1);
        assertThat(report.reportRevision().findingReviews().getFirst().decision())
                .isEqualTo(ReviewReport.Decision.PENDING);
        assertThat(report.reportRevision().findingReviews().getFirst().originalDisposition())
                .isEqualTo("VERIFIED");
        assertThat(input.store().result(run)).containsExactly(original);
    }

    @Test
    void revisionsAreImmutableAndRetriesDoNotApplyAgainstNewerHeads() throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var service =
                new ReviewReportService(
                        sessions(run), new SessionOperationLockManager(), input.store());
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        var f = first.findingReviews().getFirst();
        var edited =
                new ReviewReport.FindingReview(
                        f.findingId(),
                        f.originalDisposition(),
                        "User title",
                        "User description",
                        "User fix",
                        "User note",
                        ReviewReport.Decision.APPROVED_FOR_FIX);
        var request =
                new ReviewReport.EditRequest(
                        1, UUID.randomUUID(), List.of(edited), "Keep API behavior");
        var second = service.edit(run.sessionId(), run.runId(), request).reportRevision();
        assertThat(second.revision()).isEqualTo(2);
        assertThat(service.edit(run.sessionId(), run.runId(), request).reportRevision())
                .isEqualTo(second);
        assertThat(service.get(run.sessionId(), run.runId(), 1).reportRevision()).isEqualTo(first);
        assertThatThrownBy(
                        () ->
                                service.edit(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewReport.EditRequest(
                                                1, UUID.randomUUID(), List.of(edited), "")))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REPORT_REVISION_CONFLICT");
        assertThatThrownBy(
                        () ->
                                service.edit(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewReport.EditRequest(
                                                1,
                                                request.requestId(),
                                                List.of(edited),
                                                "different")))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REPORT_REQUEST_CONFLICT");
    }

    @Test
    void approvalRequiresExplicitDecisionsExactVersionAndIncompleteAcknowledgement()
            throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var service =
                new ReviewReportService(
                        sessions(run), new SessionOperationLockManager(), input.store());
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        var f = first.findingReviews().getFirst();
        var pending =
                new ReviewReport.ApprovalRequest(
                        1,
                        first.revisionDigest(),
                        UUID.randomUUID(),
                        List.of(f.findingId()),
                        true,
                        "");
        assertThatThrownBy(() -> service.approve(run.sessionId(), run.runId(), pending))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REPORT_APPROVAL_INVALID");
        var approved =
                new ReviewReport.FindingReview(
                        f.findingId(),
                        f.originalDisposition(),
                        f.title(),
                        f.description(),
                        f.fixInstructions(),
                        "approved",
                        ReviewReport.Decision.APPROVED_FOR_FIX);
        var second =
                service.edit(
                                run.sessionId(),
                                run.runId(),
                                new ReviewReport.EditRequest(
                                        1, UUID.randomUUID(), List.of(approved), "instructions"))
                        .reportRevision();
        var withoutAck =
                new ReviewReport.ApprovalRequest(
                        2,
                        second.revisionDigest(),
                        UUID.randomUUID(),
                        List.of(f.findingId()),
                        false,
                        "");
        assertThatThrownBy(() -> service.approve(run.sessionId(), run.runId(), withoutAck))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REPORT_INCOMPLETE_ACK_REQUIRED");
        var exact =
                new ReviewReport.ApprovalRequest(
                        2,
                        second.revisionDigest(),
                        UUID.randomUUID(),
                        List.of(f.findingId()),
                        true,
                        "Keep assumptions");
        var confirmation = service.approve(run.sessionId(), run.runId(), exact);
        assertThat(service.approve(run.sessionId(), run.runId(), exact)).isEqualTo(confirmation);
        assertThat(service.get(run.sessionId(), run.runId(), null).approval())
                .isEqualTo(confirmation);
        service.edit(
                run.sessionId(),
                run.runId(),
                new ReviewReport.EditRequest(2, UUID.randomUUID(), List.of(approved), "changed"));
        assertThat(service.get(run.sessionId(), run.runId(), null).approval()).isNull();
        assertThat(service.approve(run.sessionId(), run.runId(), exact)).isEqualTo(confirmation);
        assertThat(service.get(run.sessionId(), run.runId(), null).approval()).isNull();
    }

    @Test
    void orphanRevisionIsNotPublicAndOnlySameRequestCanConfirmItAfterRestart() throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var sessions = sessions(run);
        var failing = new java.util.concurrent.atomic.AtomicBoolean(false);
        var store =
                new ReviewExecutionStore(
                        root,
                        (a, b) -> {
                            if (failing.get()
                                    && b.getFileName().toString().equals("report-head.json"))
                                throw new java.io.IOException("before publish");
                            Files.move(
                                    a,
                                    b,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        });
        var service = new ReviewReportService(sessions, new SessionOperationLockManager(), store);
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        var request =
                new ReviewReport.EditRequest(
                        1, UUID.randomUUID(), first.findingReviews(), "changed");
        failing.set(true);
        assertThatThrownBy(() -> service.edit(run.sessionId(), run.runId(), request))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REVIEW_STATE_WRITE_UNCERTAIN");
        assertThat(service.get(run.sessionId(), run.runId(), null).currentRevision()).isEqualTo(1);
        assertThatThrownBy(() -> service.get(run.sessionId(), run.runId(), 2))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REPORT_NOT_FOUND");
        failing.set(false);
        var restarted =
                new ReviewReportService(
                        sessions,
                        new SessionOperationLockManager(),
                        new ReviewExecutionStore(root));
        assertThatThrownBy(
                        () ->
                                restarted.edit(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewReport.EditRequest(
                                                1,
                                                UUID.randomUUID(),
                                                first.findingReviews(),
                                                "other")))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REPORT_WRITE_UNCERTAIN");
        var confirmed = restarted.edit(run.sessionId(), run.runId(), request);
        assertThat(confirmed.currentRevision()).isEqualTo(2);
        assertThat(confirmed.reportRevision().requestId()).isEqualTo(request.requestId());
    }

    @Test
    void bothPublicationsAreConfirmedAfterMoveAcknowledgementIsLost() throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var store =
                new ReviewExecutionStore(
                        root,
                        (a, b) -> {
                            Files.move(
                                    a,
                                    b,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                            if (b.getFileName().toString().startsWith("report-"))
                                throw new java.io.IOException("ack lost");
                        });
        var service =
                new ReviewReportService(sessions(run), new SessionOperationLockManager(), store);
        var id = UUID.randomUUID();
        var report = service.create(run.sessionId(), run.runId(), id);
        assertThat(service.create(run.sessionId(), run.runId(), id).reportRevision())
                .isEqualTo(report.reportRevision());
        assertThat(service.get(run.sessionId(), run.runId(), null).currentRevision()).isEqualTo(1);
    }

    @Test
    void concurrentEditsHaveOneWinnerAndClosedSessionsRemainReadOnly() throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var sessions = sessions(run);
        var service =
                new ReviewReportService(sessions, new SessionOperationLockManager(), input.store());
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 2; i++)
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        service.edit(
                                                run.sessionId(),
                                                run.runId(),
                                                new ReviewReport.EditRequest(
                                                        1,
                                                        UUID.randomUUID(),
                                                        first.findingReviews(),
                                                        "new"));
                                        return "success";
                                    } catch (ReviewException error) {
                                        return error.code();
                                    }
                                }));
            start.countDown();
            var values = new ArrayList<String>();
            for (var result : results)
                values.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(values).containsExactlyInAnyOrder("success", "REPORT_REVISION_CONFLICT");
        }
        sessions.save(sessions.find(run.sessionId()).orElseThrow().close(Instant.now()));
        assertThat(service.get(run.sessionId(), run.runId(), null).editable()).isFalse();
        assertThatThrownBy(
                        () ->
                                service.edit(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewReport.EditRequest(
                                                2, UUID.randomUUID(), first.findingReviews(), "")))
                .isInstanceOf(
                        com.codingreview.controlplane.application.session.error
                                .CodingSessionConflictException.class);
        assertThatThrownBy(
                        () ->
                                service.close(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewReport.CloseRequest(2, UUID.randomUUID())))
                .isInstanceOf(
                        com.codingreview.controlplane.application.session.error
                                .CodingSessionConflictException.class);
    }

    @Test
    void unacknowledgedCandidatesAndProvenanceCannotBeInsertedOrUpgraded() throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var service =
                new ReviewReportService(
                        sessions(run), new SessionOperationLockManager(), input.store());
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        var f = first.findingReviews().getFirst();
        for (var invalid :
                List.of(
                        new ReviewReport.FindingReview(
                                UUID.randomUUID(),
                                "VERIFIED",
                                f.title(),
                                f.description(),
                                f.fixInstructions(),
                                "",
                                f.decision()),
                        new ReviewReport.FindingReview(
                                f.findingId(),
                                "RISK",
                                f.title(),
                                f.description(),
                                f.fixInstructions(),
                                "",
                                f.decision()))) {
            assertThatThrownBy(
                            () ->
                                    service.edit(
                                            run.sessionId(),
                                            run.runId(),
                                            new ReviewReport.EditRequest(
                                                    1, UUID.randomUUID(), List.of(invalid), "")))
                    .isInstanceOf(ReviewException.class)
                    .hasMessageContaining("REPORT_FINDING_INVALID");
        }
        assertThat(service.get(run.sessionId(), run.runId(), null).currentRevision()).isEqualTo(1);
    }

    @Test
    void explicitClosePreservesHistoricalRevisionsAndDoesNotStartCoding() throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var service =
                new ReviewReportService(
                        sessions(run), new SessionOperationLockManager(), input.store());
        service.create(run.sessionId(), run.runId(), UUID.randomUUID());
        var request = new ReviewReport.CloseRequest(1, UUID.randomUUID());
        assertThat(service.close(run.sessionId(), run.runId(), request).interaction())
                .isEqualTo(ReviewReport.Interaction.CLOSED);
        assertThat(service.close(run.sessionId(), run.runId(), request).editable()).isFalse();
        assertThat(service.get(run.sessionId(), run.runId(), 1).reportRevision().revision())
                .isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RISK", "SUGGESTION"})
    void approvingRiskOrSuggestionDoesNotUpgradeOriginalJudgment(String grade) throws Exception {
        var input = ReportTestInput.imported(root, grade);
        var run = input.run();
        var service =
                new ReviewReportService(
                        sessions(run), new SessionOperationLockManager(), input.store());
        byte[] original = input.store().result(run);
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        var f = first.findingReviews().getFirst();
        var approved =
                new ReviewReport.FindingReview(
                        f.findingId(),
                        f.originalDisposition(),
                        f.title(),
                        f.description(),
                        f.fixInstructions(),
                        "consider assumptions",
                        ReviewReport.Decision.APPROVED_FOR_FIX);
        var second =
                service.edit(
                                run.sessionId(),
                                run.runId(),
                                new ReviewReport.EditRequest(
                                        1, UUID.randomUUID(), List.of(approved), ""))
                        .reportRevision();
        service.approve(
                run.sessionId(),
                run.runId(),
                new ReviewReport.ApprovalRequest(
                        2,
                        second.revisionDigest(),
                        UUID.randomUUID(),
                        List.of(f.findingId()),
                        true,
                        ""));
        assertThat(
                        service.get(run.sessionId(), run.runId(), null)
                                .reportRevision()
                                .findingReviews()
                                .getFirst()
                                .originalDisposition())
                .isEqualTo(grade);
        assertThat(input.store().result(run)).containsExactly(original);
    }

    @Test
    void emptyFindingReportsCanBeClosedButCannotCreateAnEmptyApproval() throws Exception {
        var input = ReportTestInput.imported(root, "EMPTY");
        var run = input.run();
        var service =
                new ReviewReportService(
                        sessions(run), new SessionOperationLockManager(), input.store());
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        assertThat(first.findingReviews()).isEmpty();
        assertThatThrownBy(
                        () ->
                                service.approve(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewReport.ApprovalRequest(
                                                1,
                                                first.revisionDigest(),
                                                UUID.randomUUID(),
                                                List.of(),
                                                true,
                                                "")))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REPORT_APPROVAL_INVALID");
        assertThat(
                        service.close(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewReport.CloseRequest(1, UUID.randomUUID()))
                                .interaction())
                .isEqualTo(ReviewReport.Interaction.CLOSED);
    }

    @Test
    void historicalResult2CanBeRevisedWithoutRewritingIt() throws Exception {
        var input = ReportTestInput.legacy(root);
        var run = input.run();
        var service =
                new ReviewReportService(
                        sessions(run), new SessionOperationLockManager(), input.store());
        byte[] original = input.store().result(run);
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        assertThat(first.sourceResultVersion()).isEqualTo("2.0");
        assertThat(first.findingReviews().getFirst().originalDisposition()).isEqualTo("RISK");
        service.edit(
                run.sessionId(),
                run.runId(),
                new ReviewReport.EditRequest(
                        1, UUID.randomUUID(), first.findingReviews(), "human requirements"));
        assertThat(input.store().result(run)).containsExactly(original);
    }

    @Test
    void headCapacityRejectsBeforePublishingAndStillAllowsExplicitClose() throws Exception {
        var input = ReportTestInput.imported(root);
        var run = input.run();
        var service =
                new ReviewReportService(
                        sessions(run), new SessionOperationLockManager(), input.store());
        var first =
                service.create(run.sessionId(), run.runId(), UUID.randomUUID()).reportRevision();
        var f = first.findingReviews().getFirst();
        var approved =
                new ReviewReport.FindingReview(
                        f.findingId(),
                        f.originalDisposition(),
                        f.title(),
                        f.description(),
                        f.fixInstructions(),
                        "",
                        ReviewReport.Decision.APPROVED_FOR_FIX);
        service.edit(
                run.sessionId(),
                run.runId(),
                new ReviewReport.EditRequest(1, UUID.randomUUID(), List.of(approved), ""));
        service.edit(
                run.sessionId(),
                run.runId(),
                new ReviewReport.EditRequest(
                        2, UUID.randomUUID(), List.of(approved), "new requirements"));
        var base = input.store().reportHead(run).orElseThrow();
        // Valid historical approvals of revision 2 exercise the public persistence boundary.
        int low = 1, high = 4096;
        int limit = ReviewReport.MAX_BYTES - ReviewReport.CLOSE_RESERVE_BYTES;
        while (low + 1 < high) {
            int middle = (low + high) / 2;
            if (ReviewJson.canonical(historyHead(base, f.findingId(), middle, 0)).length < limit)
                low = middle;
            else high = middle;
        }
        int remaining =
                limit - 1 - ReviewJson.canonical(historyHead(base, f.findingId(), low, 0)).length;
        assertThat(remaining).isBetween(0, 32768);
        var full = historyHead(base, f.findingId(), low, remaining);
        input.store().saveReportHead(run, full);
        assertThatThrownBy(
                        () ->
                                service.edit(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewReport.EditRequest(
                                                3,
                                                UUID.randomUUID(),
                                                List.of(approved),
                                                "changed")))
                .isInstanceOf(ReviewException.class)
                .hasMessageContaining("REPORT_TOO_LARGE");
        assertThat(input.store().reportRevision(run, 4)).isEmpty();
        assertThat(
                        service.close(
                                        run.sessionId(),
                                        run.runId(),
                                        new ReviewReport.CloseRequest(3, UUID.randomUUID()))
                                .interaction())
                .isEqualTo(ReviewReport.Interaction.CLOSED);
    }

    private ReviewReport.Head historyHead(
            ReviewReport.Head base, UUID finding, int count, int padding) {
        var receipts = new ArrayList<>(base.receipts());
        var pointer = base.revisions().get(1);
        for (int index = 0; index < count; index++) {
            var id =
                    UUID.nameUUIDFromBytes(
                            ("budget:" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String instruction = index == count - 1 ? "x".repeat(padding) : "";
            var request =
                    new ReviewReport.ApprovalRequest(
                            2, pointer.revisionDigest(), id, List.of(finding), true, instruction);
            var digest =
                    ReviewJson.sha256(
                            ReviewJson.canonical(
                                    Map.of(
                                            "operation",
                                            ReviewReport.Operation.APPROVE,
                                            "request",
                                            request)));
            var approval =
                    new ReviewReport.Approval(
                            id,
                            2,
                            pointer.revisionDigest(),
                            List.of(finding),
                            true,
                            instruction,
                            pointer.createdAt());
            receipts.add(
                    receipts.size() - 1,
                    new ReviewReport.Receipt(
                            id, digest, ReviewReport.Operation.APPROVE, 2, approval));
        }
        return new ReviewReport.Head(
                        base.reportId(),
                        base.sessionId(),
                        base.runId(),
                        base.currentRevision(),
                        base.interaction(),
                        base.revisions(),
                        receipts,
                        null,
                        ReviewReport.EMPTY_DIGEST)
                .sealed();
    }

    private FileCodingSessionRepository sessions(ReviewExecution run) {
        var repository =
                new FileCodingSessionRepository(
                        root, JsonMapper.builder().findAndAddModules().build());
        repository.save(
                CodingSession.initializing(
                                run.sessionId(),
                                "report",
                                root,
                                "main",
                                new ChangePolicy(List.of(), List.of(".git/**")),
                                Instant.now())
                        .activateRealRepository(
                                new GitRepositorySnapshot(root, "main", "a".repeat(40), true),
                                Instant.now()));
        return repository;
    }
}
