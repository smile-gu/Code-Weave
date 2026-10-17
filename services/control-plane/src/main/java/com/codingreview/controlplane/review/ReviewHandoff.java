package com.codingreview.controlplane.review;

import static com.codingreview.controlplane.review.ReviewReport.*;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * Immutable authorized material and separately persisted lifecycle; neither grants tool authority.
 */
public final class ReviewHandoff {
    private ReviewHandoff() {}

    public enum Status {
        PREPARED,
        SUPERSEDED,
        REVOKED,
        STARTING,
        STARTED,
        START_BLOCKED
    }

    public record Material(
            ReviewReport.FindingReview review,
            String originalFindingJson,
            List<String> evidenceJson) {
        public Material {
            Objects.requireNonNull(review);
            require(review.decision() == Decision.APPROVED_FOR_FIX, "HANDOFF_REQUEST_INVALID");
            text(originalFindingJson, MAX_BYTES);
            evidenceJson = List.copyOf(evidenceJson);
            require(evidenceJson.size() <= 128, "HANDOFF_INTEGRITY_ERROR");
            evidenceJson.forEach(value -> text(value, MAX_BYTES));
        }
    }

    public record Content(
            String handoffVersion,
            UUID handoffId,
            UUID sessionId,
            UUID reviewThreadId,
            UUID runId,
            UUID snapshotId,
            String manifestSha256,
            UUID sourceResultId,
            String sourceResultSha256,
            String sourceResultVersion,
            UUID reportId,
            int reportRevision,
            String reportRevisionDigest,
            UUID approvalRequestId,
            List<UUID> selectedFindingIds,
            List<Material> materials,
            String reportInstructions,
            String approvalInstructions,
            String coverageJson,
            String createdAt,
            UUID requestId,
            String requestDigest,
            String contentDigest) {
        public Content {
            require("1.0".equals(handoffVersion), "HANDOFF_INTEGRITY_ERROR");
            for (var id :
                    List.of(
                            handoffId,
                            sessionId,
                            reviewThreadId,
                            runId,
                            snapshotId,
                            sourceResultId,
                            reportId,
                            approvalRequestId,
                            requestId)) Objects.requireNonNull(id);
            require(
                    reportRevision > 0 && Set.of("2.0", "3.0").contains(sourceResultVersion),
                    "HANDOFF_INTEGRITY_ERROR");
            for (var hash :
                    List.of(
                            manifestSha256,
                            sourceResultSha256,
                            reportRevisionDigest,
                            requestDigest,
                            contentDigest)) digest(hash);
            selectedFindingIds = List.copyOf(selectedFindingIds);
            materials = List.copyOf(materials);
            require(
                    !materials.isEmpty()
                            && materials.size() <= 32
                            && materials.stream()
                                    .map(m -> m.review().findingId())
                                    .toList()
                                    .equals(selectedFindingIds)
                            && new HashSet<>(selectedFindingIds).size()
                                    == selectedFindingIds.size(),
                    "HANDOFF_INTEGRITY_ERROR");
            text(reportInstructions, 32768);
            text(approvalInstructions, 32768);
            text(coverageJson, MAX_BYTES);
            time(createdAt);
        }

        public void verify() {
            require(contentDigest.equals(hash(this)), "HANDOFF_INTEGRITY_ERROR");
        }

        public Content sealed() {
            return new Content(
                    handoffVersion,
                    handoffId,
                    sessionId,
                    reviewThreadId,
                    runId,
                    snapshotId,
                    manifestSha256,
                    sourceResultId,
                    sourceResultSha256,
                    sourceResultVersion,
                    reportId,
                    reportRevision,
                    reportRevisionDigest,
                    approvalRequestId,
                    selectedFindingIds,
                    materials,
                    reportInstructions,
                    approvalInstructions,
                    coverageJson,
                    createdAt,
                    requestId,
                    requestDigest,
                    hash(this));
        }
    }

    private static String hash(Content value) {
        ObjectNode node = (ObjectNode) ReviewJson.parse(ReviewJson.canonical(value));
        node.remove("contentDigest");
        return ReviewJson.sha256(ReviewJson.canonical(node));
    }

    public record Lifecycle(
            UUID handoffId,
            String contentDigest,
            Status status,
            UUID turnId,
            UUID startRequestId,
            String startRequestDigest,
            String reason,
            String updatedAt) {
        public Lifecycle {
            Objects.requireNonNull(handoffId);
            Objects.requireNonNull(status);
            digest(contentDigest);
            time(updatedAt);
            require(
                    (turnId != null) == (startRequestId != null)
                            && (turnId != null) == (startRequestDigest != null),
                    "HANDOFF_INTEGRITY_ERROR");
            require(
                    !(status == Status.STARTING || status == Status.STARTED) || turnId != null,
                    "HANDOFF_INTEGRITY_ERROR");
            require(status != Status.PREPARED || turnId == null, "HANDOFF_INTEGRITY_ERROR");
            if (startRequestDigest != null) digest(startRequestDigest);
            require(
                    reason == null || reason.matches("[A-Z][A-Z0-9_]{0,127}"),
                    "HANDOFF_INTEGRITY_ERROR");
        }

        public Lifecycle status(Status next, String reason, String now) {
            return new Lifecycle(
                    handoffId,
                    contentDigest,
                    next,
                    turnId,
                    startRequestId,
                    startRequestDigest,
                    reason,
                    now);
        }
    }

    public record Index(String indexVersion, List<Lifecycle> entries) {
        public Index {
            require("1.0".equals(indexVersion), "HANDOFF_INTEGRITY_ERROR");
            entries = List.copyOf(entries);
            require(
                    !entries.isEmpty()
                            && entries.stream().map(Lifecycle::handoffId).distinct().count()
                                    == entries.size(),
                    "HANDOFF_INTEGRITY_ERROR");
        }
    }

    public record View(Content content, Lifecycle lifecycle) {}

    public record PrepareRequest(
            UUID requestId, int expectedRevision, String expectedRevisionDigest) {
        public PrepareRequest {
            Objects.requireNonNull(requestId);
            require(expectedRevision > 0, "HANDOFF_REQUEST_INVALID");
            digest(expectedRevisionDigest);
        }
    }

    public record StartRequest(UUID requestId, String expectedHandoffDigest) {
        public StartRequest {
            Objects.requireNonNull(requestId);
            digest(expectedHandoffDigest);
        }
    }

    public record RevokeRequest(UUID requestId, String expectedHandoffDigest) {
        public RevokeRequest {
            Objects.requireNonNull(requestId);
            digest(expectedHandoffDigest);
        }
    }
}
