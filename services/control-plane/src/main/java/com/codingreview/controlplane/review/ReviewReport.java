package com.codingreview.controlplane.review;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Human report facts. Model provenance remains solely in the immutable Result. */
public final class ReviewReport {
    private ReviewReport() {}

    // Result claim/suggestion permit 4096 Unicode characters, each at most four UTF-8 bytes.
    private static final int SOURCE_TEXT_MAX_BYTES = 4096 * 4;
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int CLOSE_RESERVE_BYTES = 1024;
    public static final String EMPTY_DIGEST = "sha256:" + "0".repeat(64);
    private static final ObjectMapper MAPPER =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build()
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public enum Decision {
        PENDING,
        APPROVED_FOR_FIX,
        DEFERRED,
        ACCEPTED_RISK,
        DISMISSED
    }

    public enum Interaction {
        OPEN,
        CLOSED
    }

    public enum Operation {
        CREATE,
        EDIT,
        APPROVE,
        CLOSE
    }

    static void require(boolean value, String code) {
        if (!value) throw new ReviewException(code);
    }

    static void text(String value, int limit) {
        require(value != null, "REPORT_REQUEST_INVALID");
        ReviewJson.unicode(value);
        require(value.getBytes(StandardCharsets.UTF_8).length <= limit, "REPORT_TOO_LARGE");
    }

    static void digest(String value) {
        require(value != null && value.matches("sha256:[0-9a-f]{64}"), "REPORT_INTEGRITY_ERROR");
    }

    static void time(String value) {
        require(value != null && value.matches(".*\\.\\d{6}Z"), "REPORT_INTEGRITY_ERROR");
        try {
            Instant.parse(value);
        } catch (RuntimeException error) {
            throw new ReviewException("REPORT_INTEGRITY_ERROR");
        }
    }

    public static <T> T decode(byte[] raw, Class<T> type, String code) {
        try {
            require(raw.length <= MAX_BYTES, code);
            var node = ReviewJson.parse(raw);
            ReviewWireSchemas.validate(schema(type), node);
            T value = MAPPER.treeToValue(node, type);
            // UUIDs and typed scalars must have their canonical representation.
            require(Arrays.equals(ReviewJson.canonical(node), ReviewJson.canonical(value)), code);
            return value;
        } catch (Exception error) {
            throw new ReviewException(code);
        }
    }

    public static String schema(Class<?> type) {
        if (type == View.class) return "review-report-v1";
        if (type == Head.class) return "review-report-head-v1";
        if (type == Revision.class) return "review-report-revision-v1";
        if (type == CreateRequest.class) return "review-report-create-v1";
        if (type == EditRequest.class) return "review-report-edit-v1";
        if (type == ApprovalRequest.class) return "review-report-approve-v1";
        if (type == CloseRequest.class) return "review-report-close-v1";
        if (type == Approval.class) return "review-report-approval-v1";
        throw new ReviewException("REPORT_CONTRACT_INVALID");
    }

    private static String contentDigest(Object value, String field) {
        ObjectNode node = (ObjectNode) MAPPER.valueToTree(value);
        node.remove(field);
        return ReviewJson.sha256(ReviewJson.canonical(node));
    }

    public record FindingReview(
            UUID findingId,
            String originalDisposition,
            String title,
            String description,
            String fixInstructions,
            String note,
            Decision decision) {
        public FindingReview {
            Objects.requireNonNull(findingId);
            Objects.requireNonNull(decision);
            require(
                    Set.of("VERIFIED", "RISK", "SUGGESTION").contains(originalDisposition),
                    "REPORT_REQUEST_INVALID");
            text(title, 2048);
            text(description, SOURCE_TEXT_MAX_BYTES);
            text(fixInstructions, SOURCE_TEXT_MAX_BYTES);
            text(note, 4096);
            require(!title.isBlank(), "REPORT_REQUEST_INVALID");
        }
    }

    public record Revision(
            String reportVersion,
            UUID reportId,
            int revision,
            UUID sessionId,
            UUID reviewThreadId,
            UUID runId,
            UUID sourceResultId,
            String sourceResultSha256,
            String sourceResultVersion,
            UUID snapshotId,
            String manifestSha256,
            String createdAt,
            List<FindingReview> findingReviews,
            String userInstructions,
            UUID requestId,
            String requestDigest,
            String revisionDigest) {
        public Revision {
            require("1.0".equals(reportVersion) && revision > 0, "REPORT_INTEGRITY_ERROR");
            Objects.requireNonNull(reportId);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(reviewThreadId);
            Objects.requireNonNull(runId);
            Objects.requireNonNull(sourceResultId);
            Objects.requireNonNull(snapshotId);
            Objects.requireNonNull(requestId);
            findingReviews = List.copyOf(findingReviews);
            require(
                    findingReviews.size() <= 32
                            && findingReviews.stream()
                                            .map(FindingReview::findingId)
                                            .distinct()
                                            .count()
                                    == findingReviews.size(),
                    "REPORT_REQUEST_INVALID");
            require(Set.of("2.0", "3.0").contains(sourceResultVersion), "REPORT_INTEGRITY_ERROR");
            text(userInstructions, 32768);
            time(createdAt);
            digest(sourceResultSha256);
            digest(manifestSha256);
            digest(requestDigest);
            digest(revisionDigest);
        }

        public void verify() {
            require(
                    revisionDigest.equals(contentDigest(this, "revisionDigest")),
                    "REPORT_INTEGRITY_ERROR");
        }

        public Revision sealed() {
            return new Revision(
                    reportVersion,
                    reportId,
                    revision,
                    sessionId,
                    reviewThreadId,
                    runId,
                    sourceResultId,
                    sourceResultSha256,
                    sourceResultVersion,
                    snapshotId,
                    manifestSha256,
                    createdAt,
                    findingReviews,
                    userInstructions,
                    requestId,
                    requestDigest,
                    contentDigest(this, "revisionDigest"));
        }
    }

    public record Pointer(int revision, String revisionDigest, String createdAt) {
        public Pointer {
            require(revision > 0, "REPORT_INTEGRITY_ERROR");
            digest(revisionDigest);
            time(createdAt);
        }
    }

    public record Approval(
            UUID requestId,
            int revision,
            String revisionDigest,
            List<UUID> selectedFindingIds,
            boolean incompleteAcknowledged,
            String userInstructions,
            String createdAt) {
        public Approval {
            Objects.requireNonNull(requestId);
            require(revision > 0, "REPORT_REQUEST_INVALID");
            digest(revisionDigest);
            selectedFindingIds = List.copyOf(selectedFindingIds);
            require(
                    !selectedFindingIds.isEmpty()
                            && selectedFindingIds.size() <= 32
                            && new HashSet<>(selectedFindingIds).size()
                                    == selectedFindingIds.size(),
                    "REPORT_APPROVAL_INVALID");
            text(userInstructions, 32768);
            time(createdAt);
        }
    }

    public record Receipt(
            UUID requestId,
            String requestDigest,
            Operation operation,
            int revision,
            Approval approval) {
        public Receipt {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(operation);
            digest(requestDigest);
            require(revision > 0, "REPORT_INTEGRITY_ERROR");
            require(
                    (operation == Operation.APPROVE) == (approval != null),
                    "REPORT_INTEGRITY_ERROR");
            if (approval != null)
                require(
                        approval.requestId().equals(requestId) && approval.revision() == revision,
                        "REPORT_INTEGRITY_ERROR");
        }
    }

    public record Head(
            UUID reportId,
            UUID sessionId,
            UUID runId,
            int currentRevision,
            Interaction interaction,
            List<Pointer> revisions,
            List<Receipt> receipts,
            Approval approval,
            String headDigest) {
        public Head {
            Objects.requireNonNull(reportId);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(runId);
            Objects.requireNonNull(interaction);
            revisions = List.copyOf(revisions);
            receipts = List.copyOf(receipts);
            digest(headDigest);
            require(
                    currentRevision == revisions.size() && currentRevision > 0,
                    "REPORT_INTEGRITY_ERROR");
            for (int i = 0; i < revisions.size(); i++)
                require(revisions.get(i).revision() == i + 1, "REPORT_INTEGRITY_ERROR");
            require(
                    receipts.stream().map(Receipt::requestId).distinct().count() == receipts.size(),
                    "REPORT_INTEGRITY_ERROR");
            for (var receipt : receipts)
                require(receipt.revision() <= currentRevision, "REPORT_INTEGRITY_ERROR");
            if (approval != null)
                require(
                        interaction == Interaction.OPEN
                                && approval.revision() == currentRevision
                                && approval.revisionDigest()
                                        .equals(revisions.getLast().revisionDigest()),
                        "REPORT_INTEGRITY_ERROR");
        }

        public void verify() {
            require(headDigest.equals(contentDigest(this, "headDigest")), "REPORT_INTEGRITY_ERROR");
        }

        public Head sealed() {
            return new Head(
                    reportId,
                    sessionId,
                    runId,
                    currentRevision,
                    interaction,
                    revisions,
                    receipts,
                    approval,
                    contentDigest(this, "headDigest"));
        }
    }

    public record View(
            Revision reportRevision,
            int currentRevision,
            boolean editable,
            Interaction interaction,
            List<Pointer> revisionHistory,
            Approval approval) {}

    public record CreateRequest(UUID requestId) {
        public CreateRequest {
            Objects.requireNonNull(requestId);
        }
    }

    public record EditRequest(
            int expectedRevision,
            UUID requestId,
            List<FindingReview> findingReviews,
            String userInstructions) {
        public EditRequest {
            require(expectedRevision > 0 && requestId != null, "REPORT_REQUEST_INVALID");
            findingReviews = List.copyOf(findingReviews);
            text(userInstructions, 32768);
        }
    }

    public record ApprovalRequest(
            int expectedRevision,
            String expectedRevisionDigest,
            UUID requestId,
            List<UUID> selectedFindingIds,
            boolean incompleteAcknowledged,
            String userInstructions) {
        public ApprovalRequest {
            require(expectedRevision > 0 && requestId != null, "REPORT_REQUEST_INVALID");
            digest(expectedRevisionDigest);
            selectedFindingIds = List.copyOf(selectedFindingIds);
            text(userInstructions, 32768);
        }
    }

    public record CloseRequest(int expectedRevision, UUID requestId) {
        public CloseRequest {
            require(expectedRevision > 0 && requestId != null, "REPORT_REQUEST_INVALID");
        }
    }
}
