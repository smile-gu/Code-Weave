package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.review.*;
import com.codingreview.controlplane.review.snapshot.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Fixed-input review and immutable draft history; delivery is not approval. */
@RestController
@RequestMapping("/api/v1/coding-sessions/{sessionId}")
public final class ReviewController {
    private final ReviewApplicationService service;

    public ReviewController(ReviewApplicationService service) {
        this.service = service;
    }

    @GetMapping("/review-scope-preview")
    public ScopePreview preview(@PathVariable UUID sessionId) {
        return service.preview(sessionId);
    }

    @PostMapping("/review-runs")
    public ResponseEntity<Map<String, Object>> start(
            @PathVariable UUID sessionId, HttpServletRequest request) throws java.io.IOException {
        byte[] raw = request.getInputStream().readNBytes(65537);
        if (raw.length > 65536) throw new ReviewException("REVIEW_REQUEST_TOO_LARGE");
        var value = ReviewJson.parse(raw);
        ReviewProtocol.fields(
                value,
                Set.of(
                        "expectedActiveBaselineCommit",
                        "expectedHeadCommit",
                        "expectedBranch",
                        "expectedWorkspaceFingerprint",
                        "targetPaths",
                        "input"),
                Set.of("reviewPlan", "selectedPasses", "feeAuthorization"),
                "REVIEW_REQUEST_INVALID");
        for (String field :
                List.of(
                        "expectedActiveBaselineCommit",
                        "expectedHeadCommit",
                        "expectedBranch",
                        "expectedWorkspaceFingerprint"))
            if (!value.path(field).isTextual()) throw new ReviewException("REVIEW_REQUEST_INVALID");
        if (!value.path("targetPaths").isArray()) throw new ReviewException("REVIEW_SCOPE_EMPTY");
        List<String> targets = new ArrayList<>();
        value.path("targetPaths")
                .forEach(
                        item -> {
                            if (!item.isTextual())
                                throw new ReviewException("REVIEW_REQUEST_INVALID");
                            targets.add(item.asText());
                        });
        ReviewProtocol.fields(
                value.get("input"), Set.of("message"), Set.of(), "REVIEW_REQUEST_INVALID");
        if (!value.path("input").path("message").isTextual())
            throw new ReviewException("REVIEW_REQUEST_INVALID");
        var expected =
                new ScopeConfirmation(
                        value.path("expectedActiveBaselineCommit").asText(),
                        value.path("expectedHeadCommit").asText(),
                        value.path("expectedBranch").asText(),
                        value.path("expectedWorkspaceFingerprint").asText(),
                        targets);
        com.fasterxml.jackson.databind.JsonNode plan = null;
        if (value.has("reviewPlan")) {
            if (value.has("feeAuthorization") || value.has("selectedPasses"))
                throw new ReviewException("REVIEW_REQUEST_INVALID");
            plan = value.get("reviewPlan");
            ReviewPlan.validate(plan);
        } else if (value.has("feeAuthorization")) {
            List<String> passes = null;
            if (value.has("selectedPasses")) {
                if (!value.path("selectedPasses").isArray())
                    throw new ReviewException("REVIEW_REQUEST_INVALID");
                passes = new ArrayList<>();
                for (var pass : value.path("selectedPasses")) {
                    if (!pass.isTextual()) throw new ReviewException("REVIEW_REQUEST_INVALID");
                    passes.add(pass.asText());
                }
            }
            plan = ReviewPlan.create(passes, value.get("feeAuthorization"));
        } else if (value.has("selectedPasses"))
            throw new ReviewException("FEE_AUTHORIZATION_REQUIRED");
        String message = value.path("input").path("message").asText();
        var started =
                plan == null
                        ? service.start(sessionId, expected, message)
                        : service.start(sessionId, expected, message, plan);
        return ResponseEntity.accepted().body(started.wire());
    }

    @GetMapping("/review-runs")
    public List<Map<String, Object>> list(
            @PathVariable UUID sessionId, @RequestParam(defaultValue = "50") int limit) {
        return service.list(sessionId, limit).stream().map(service::view).toList();
    }

    @GetMapping("/review-runs/{runId}")
    public Map<String, Object> get(@PathVariable UUID sessionId, @PathVariable UUID runId) {
        return service.view(service.get(sessionId, runId));
    }

    @PostMapping("/review-runs/{runId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable UUID sessionId, @PathVariable UUID runId) {
        return ResponseEntity.accepted().body(service.cancel(sessionId, runId).wire());
    }

    @GetMapping(value = "/review-runs/{runId}/result", produces = "application/json")
    public ResponseEntity<byte[]> result(@PathVariable UUID sessionId, @PathVariable UUID runId) {
        byte[] raw = service.result(sessionId, runId);
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("ETag", "\"" + ReviewJson.sha256(raw) + "\"")
                .header("Cache-Control", "no-store").body(raw);
    }

    @GetMapping("/review-price-profile")
    public com.fasterxml.jackson.databind.JsonNode priceProfile(@PathVariable UUID sessionId) {
        return service.priceProfile(sessionId);
    }

    @GetMapping(
            value = "/review-runs/{runId}/evidence/{evidenceId}/materials",
            produces = "application/json")
    public ResponseEntity<byte[]> evidenceMaterials(
            @PathVariable UUID sessionId, @PathVariable UUID runId, @PathVariable UUID evidenceId) {
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .body(service.evidenceMaterials(sessionId, runId, evidenceId));
    }
}
