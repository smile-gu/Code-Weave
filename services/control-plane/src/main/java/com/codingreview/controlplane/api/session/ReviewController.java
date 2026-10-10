package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.review.*;
import com.codingreview.controlplane.review.snapshot.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 本批仅后端模拟闭环，成功不表示代码审查通过。 */
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
                Set.of(),
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
        return ResponseEntity.accepted()
                .body(
                        service.start(
                                        sessionId,
                                        expected,
                                        value.path("input").path("message").asText())
                                .wire());
    }

    @GetMapping("/review-runs")
    public List<Map<String, Object>> list(
            @PathVariable UUID sessionId, @RequestParam(defaultValue = "50") int limit) {
        return service.list(sessionId, limit).stream().map(ReviewExecution::wire).toList();
    }

    @GetMapping("/review-runs/{runId}")
    public Map<String, Object> get(@PathVariable UUID sessionId, @PathVariable UUID runId) {
        return service.get(sessionId, runId).wire();
    }

    @PostMapping("/review-runs/{runId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable UUID sessionId, @PathVariable UUID runId) {
        return ResponseEntity.accepted().body(service.cancel(sessionId, runId).wire());
    }
}
