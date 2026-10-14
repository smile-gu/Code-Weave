package com.codingreview.controlplane.review;

import static com.codingreview.controlplane.review.ReviewReport.*;

import com.codingreview.controlplane.application.session.SessionOperationLockManager;
import com.codingreview.controlplane.application.session.error.*;
import com.codingreview.controlplane.session.model.*;
import com.codingreview.controlplane.session.persistence.CodingSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Only explicit human writes publish report heads; historical reads never schedule execution. */
public final class ReviewReportService {
    private final CodingSessionRepository sessions;
    private final SessionOperationLockManager locks;
    private final ReviewExecutionStore store;

    public ReviewReportService(
            CodingSessionRepository sessions,
            SessionOperationLockManager locks,
            ReviewExecutionStore store) {
        this.sessions = sessions;
        this.locks = locks;
        this.store = store;
    }

    private CodingSession session(UUID id, boolean write) {
        var value = sessions.find(id).orElseThrow(() -> new CodingSessionNotFoundException(id));
        if (write && value.status() != CodingSessionStatus.ACTIVE)
            throw new CodingSessionConflictException("SESSION_NOT_ACTIVE", "关闭的会话仅允许读取报告历史。");
        return value;
    }

    private record Source(ReviewExecution run, JsonNode result) {}

    private Source source(UUID session, UUID runId) {
        var run =
                store.find(session, runId)
                        .orElseThrow(() -> new ReviewException("REVIEW_RUN_NOT_FOUND"));
        require(
                run.state().equals("COMPLETED") && run.resourceState().equals("RELEASED"),
                "REVIEW_RESULT_NOT_AVAILABLE");
        ReviewProtocol.persisted(store, run);
        var result = ReviewJson.parse(store.result(run));
        require(
                Set.of("2.0", "3.0").contains(result.path("resultVersion").asText())
                        && result.path("diagnostics").path("executionMode").asText().equals("REAL"),
                "REPORT_REAL_RESULT_REQUIRED");
        return new Source(run, result);
    }

    private void bind(Revision revision, Source source) {
        revision.verify();
        var run = source.run();
        var result = source.result();
        require(
                revision.runId().equals(run.runId())
                        && revision.sessionId().equals(run.sessionId())
                        && revision.reviewThreadId().equals(run.reviewThreadId())
                        && revision.snapshotId().equals(run.snapshotId())
                        && revision.manifestSha256().equals(run.manifestSha256())
                        && revision.sourceResultId()
                                .toString()
                                .equals(result.path("resultId").asText())
                        && revision.sourceResultSha256()
                                .equals(run.result().path("resultSha256").asText())
                        && revision.sourceResultVersion()
                                .equals(result.path("resultVersion").asText()),
                "REPORT_INTEGRITY_ERROR");
        var originals = new LinkedHashMap<UUID, String>();
        result.path("findings")
                .forEach(
                        f ->
                                originals.put(
                                        UUID.fromString(f.path("findingId").asText()),
                                        f.path("disposition").asText()));
        require(revision.findingReviews().size() == originals.size(), "REPORT_FINDING_INVALID");
        for (var f : revision.findingReviews())
            require(
                    f.originalDisposition().equals(originals.get(f.findingId())),
                    "REPORT_FINDING_INVALID");
    }

    private Head head(Source source) {
        var head =
                store.reportHead(source.run())
                        .orElseThrow(() -> new ReviewException("REPORT_NOT_FOUND"));
        var current = revision(source, head, head.currentRevision());
        require(head.reportId().equals(current.reportId()), "REPORT_INTEGRITY_ERROR");
        return head;
    }

    private Revision revision(Source source, Head head, int number) {
        require(number > 0 && number <= head.currentRevision(), "REPORT_NOT_FOUND");
        var revision =
                store.reportRevision(source.run(), number)
                        .orElseThrow(() -> new ReviewException("REPORT_INTEGRITY_ERROR"));
        var pointer = head.revisions().get(number - 1);
        require(
                revision.reportId().equals(head.reportId())
                        && revision.revisionDigest().equals(pointer.revisionDigest())
                        && revision.createdAt().equals(pointer.createdAt()),
                "REPORT_INTEGRITY_ERROR");
        bind(revision, source);
        return revision;
    }

    private View view(Source source, Head head, int number, boolean active) {
        return new View(
                revision(source, head, number),
                head.currentRevision(),
                active
                        && head.interaction() == Interaction.OPEN
                        && number == head.currentRevision(),
                head.interaction(),
                head.revisions(),
                head.approval());
    }

    private String requestDigest(Operation operation, Object request) {
        return ReviewJson.sha256(
                ReviewJson.canonical(Map.of("operation", operation, "request", request)));
    }

    private Optional<Receipt> replay(Head head, UUID id, String digest) {
        var found = head.receipts().stream().filter(r -> r.requestId().equals(id)).findFirst();
        if (found.isPresent())
            require(found.get().requestDigest().equals(digest), "REPORT_REQUEST_CONFLICT");
        return found;
    }

    public View get(UUID sessionId, UUID runId, Integer number) {
        synchronized (locks.lock(sessionId)) {
            var session = session(sessionId, false);
            var source = source(sessionId, runId);
            var head = head(source);
            return view(
                    source,
                    head,
                    number == null ? head.currentRevision() : number,
                    session.status() == CodingSessionStatus.ACTIVE);
        }
    }

    public View create(UUID sessionId, UUID runId, UUID requestId) {
        synchronized (locks.lock(sessionId)) {
            session(sessionId, true);
            Objects.requireNonNull(requestId);
            var source = source(sessionId, runId);
            var digest = requestDigest(Operation.CREATE, Map.of("requestId", requestId));
            var previous = store.reportHead(source.run());
            if (previous.isPresent()) {
                var head = head(source);
                var receipt =
                        replay(head, requestId, digest)
                                .orElseThrow(() -> new ReviewException("REPORT_ALREADY_EXISTS"));
                return view(source, head, receipt.revision(), true);
            }
            var findings = new ArrayList<FindingReview>();
            source.result()
                    .path("findings")
                    .forEach(
                            f ->
                                    findings.add(
                                            new FindingReview(
                                                    UUID.fromString(f.path("findingId").asText()),
                                                    f.path("disposition").asText(),
                                                    f.path("title").asText(),
                                                    f.path("claim").asText(),
                                                    f.path("suggestion").asText(),
                                                    "",
                                                    Decision.PENDING)));
            UUID reportId =
                    UUID.nameUUIDFromBytes(
                            ("review-report:"
                                            + runId
                                            + ":"
                                            + source.result().path("resultId").asText())
                                    .getBytes(StandardCharsets.UTF_8));
            var candidate =
                    new Revision(
                                    "1.0",
                                    reportId,
                                    1,
                                    sessionId,
                                    source.run().reviewThreadId(),
                                    runId,
                                    UUID.fromString(source.result().path("resultId").asText()),
                                    source.run().result().path("resultSha256").asText(),
                                    source.result().path("resultVersion").asText(),
                                    source.run().snapshotId(),
                                    source.run().manifestSha256(),
                                    ReviewJson.timestamp(Instant.now()),
                                    findings,
                                    "",
                                    requestId,
                                    digest,
                                    EMPTY_DIGEST)
                            .sealed();
            candidate = publicationCandidate(source, candidate);
            var head =
                    new Head(
                                    reportId,
                                    sessionId,
                                    runId,
                                    1,
                                    Interaction.OPEN,
                                    List.of(
                                            new Pointer(
                                                    1,
                                                    candidate.revisionDigest(),
                                                    candidate.createdAt())),
                                    List.of(
                                            new Receipt(
                                                    requestId, digest, Operation.CREATE, 1, null)),
                                    null,
                                    EMPTY_DIGEST)
                            .sealed();
            preflightHead(source, candidate, head);
            store.saveReportRevision(source.run(), candidate);
            store.saveReportHead(source.run(), head);
            return view(source, head, 1, true);
        }
    }

    public View edit(UUID sessionId, UUID runId, EditRequest request) {
        synchronized (locks.lock(sessionId)) {
            session(sessionId, true);
            var source = source(sessionId, runId);
            var head = head(source);
            var digest = requestDigest(Operation.EDIT, request);
            var receipt = replay(head, request.requestId(), digest);
            if (receipt.isPresent()) return view(source, head, receipt.get().revision(), true);
            require(head.interaction() == Interaction.OPEN, "REPORT_CLOSED");
            require(
                    head.currentRevision() == request.expectedRevision(),
                    "REPORT_REVISION_CONFLICT");
            var previous = revision(source, head, head.currentRevision());
            var candidate =
                    new Revision(
                                    previous.reportVersion(),
                                    previous.reportId(),
                                    previous.revision() + 1,
                                    previous.sessionId(),
                                    previous.reviewThreadId(),
                                    previous.runId(),
                                    previous.sourceResultId(),
                                    previous.sourceResultSha256(),
                                    previous.sourceResultVersion(),
                                    previous.snapshotId(),
                                    previous.manifestSha256(),
                                    ReviewJson.timestamp(Instant.now()),
                                    request.findingReviews(),
                                    request.userInstructions(),
                                    request.requestId(),
                                    digest,
                                    EMPTY_DIGEST)
                            .sealed();
            candidate = publicationCandidate(source, candidate);
            var pointers = new ArrayList<>(head.revisions());
            pointers.add(
                    new Pointer(
                            candidate.revision(),
                            candidate.revisionDigest(),
                            candidate.createdAt()));
            var receipts = new ArrayList<>(head.receipts());
            receipts.add(
                    new Receipt(
                            request.requestId(),
                            digest,
                            Operation.EDIT,
                            candidate.revision(),
                            null));
            // A new report revision invalidates any previous approval selection.
            var next =
                    new Head(
                                    head.reportId(),
                                    sessionId,
                                    runId,
                                    candidate.revision(),
                                    head.interaction(),
                                    pointers,
                                    receipts,
                                    null,
                                    EMPTY_DIGEST)
                            .sealed();
            preflightHead(source, candidate, next);
            store.saveReportRevision(source.run(), candidate);
            store.saveReportHead(source.run(), next);
            return view(source, next, candidate.revision(), true);
        }
    }

    public Approval approve(UUID sessionId, UUID runId, ApprovalRequest request) {
        synchronized (locks.lock(sessionId)) {
            session(sessionId, true);
            var source = source(sessionId, runId);
            var head = head(source);
            var digest = requestDigest(Operation.APPROVE, request);
            var receipt = replay(head, request.requestId(), digest);
            if (receipt.isPresent()) return receipt.get().approval();
            require(head.interaction() == Interaction.OPEN, "REPORT_CLOSED");
            require(
                    head.currentRevision() == request.expectedRevision(),
                    "REPORT_REVISION_CONFLICT");
            var revision = revision(source, head, head.currentRevision());
            require(
                    revision.revisionDigest().equals(request.expectedRevisionDigest()),
                    "REPORT_REVISION_CONFLICT");
            var reviews = new HashMap<UUID, FindingReview>();
            revision.findingReviews().forEach(f -> reviews.put(f.findingId(), f));
            for (var id : request.selectedFindingIds())
                require(
                        reviews.containsKey(id)
                                && reviews.get(id).decision() == Decision.APPROVED_FOR_FIX,
                        "REPORT_APPROVAL_INVALID");
            boolean incomplete =
                    !source.result().path("resultVersion").asText().equals("3.0")
                            || !source.result()
                                    .path("coverageStatus")
                                    .asText()
                                    .equals("SELECTED_COMPLETE")
                            || source.result().path("reviewPlan").path("selectedPasses").size()
                                    != 6;
            require(
                    !incomplete || request.incompleteAcknowledged(),
                    "REPORT_INCOMPLETE_ACK_REQUIRED");
            var approval =
                    new Approval(
                            request.requestId(),
                            revision.revision(),
                            revision.revisionDigest(),
                            request.selectedFindingIds(),
                            request.incompleteAcknowledged(),
                            request.userInstructions(),
                            ReviewJson.timestamp(Instant.now()));
            var receipts = new ArrayList<>(head.receipts());
            receipts.add(
                    new Receipt(
                            request.requestId(),
                            digest,
                            Operation.APPROVE,
                            revision.revision(),
                            approval));
            var next =
                    new Head(
                                    head.reportId(),
                                    sessionId,
                                    runId,
                                    head.currentRevision(),
                                    head.interaction(),
                                    head.revisions(),
                                    receipts,
                                    approval,
                                    EMPTY_DIGEST)
                            .sealed();
            store.saveReportHead(source.run(), next);
            return approval;
        }
    }

    public View close(UUID sessionId, UUID runId, CloseRequest request) {
        synchronized (locks.lock(sessionId)) {
            session(sessionId, true);
            var source = source(sessionId, runId);
            var head = head(source);
            var digest = requestDigest(Operation.CLOSE, request);
            var receipt = replay(head, request.requestId(), digest);
            if (receipt.isPresent()) return view(source, head, receipt.get().revision(), true);
            require(head.interaction() == Interaction.OPEN, "REPORT_CLOSED");
            require(
                    head.currentRevision() == request.expectedRevision(),
                    "REPORT_REVISION_CONFLICT");
            var receipts = new ArrayList<>(head.receipts());
            receipts.add(
                    new Receipt(
                            request.requestId(),
                            digest,
                            Operation.CLOSE,
                            head.currentRevision(),
                            null));
            var next =
                    new Head(
                                    head.reportId(),
                                    sessionId,
                                    runId,
                                    head.currentRevision(),
                                    Interaction.CLOSED,
                                    head.revisions(),
                                    receipts,
                                    null,
                                    EMPTY_DIGEST)
                            .sealed();
            store.saveReportHead(source.run(), next);
            return view(source, next, next.currentRevision(), true);
        }
    }

    private void preflightHead(Source source, Revision candidate, Head head) {
        if (ReviewJson.canonical(head).length
                > ReviewReport.MAX_BYTES - ReviewReport.CLOSE_RESERVE_BYTES) {
            // An older orphan still requires its exact intent; do not classify it as a new rejected
            // input.
            throw new ReviewException(
                    store.reportRevision(source.run(), candidate.revision()).isPresent()
                            ? "REPORT_WRITE_UNCERTAIN"
                            : "REPORT_TOO_LARGE");
        }
    }

    private Revision publicationCandidate(Source source, Revision candidate) {
        // A prior failed head write may have left immutable content. Only its exact request can
        // confirm it.
        var orphan = store.reportRevision(source.run(), candidate.revision());
        if (orphan.isPresent()) {
            var existing = orphan.get();
            bind(existing, source);
            require(
                    existing.requestId().equals(candidate.requestId())
                            && existing.requestDigest().equals(candidate.requestDigest())
                            && existing.reportId().equals(candidate.reportId())
                            && existing.findingReviews().equals(candidate.findingReviews())
                            && existing.userInstructions().equals(candidate.userInstructions()),
                    "REPORT_WRITE_UNCERTAIN");
            candidate = existing;
        }
        bind(candidate, source);
        return candidate;
    }
}
