package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.review.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

/** Explicit human report mutations, with immutable source Result retained separately. */
@RestController
@RequestMapping("/api/v1/coding-sessions/{sessionId}/review-runs/{runId}/report")
public final class ReviewReportController {
    private final ReviewReportService service;

    public ReviewReportController(ReviewReportService service) {
        this.service = service;
    }

    private <T> T body(HttpServletRequest request, Class<T> type) throws IOException {
        byte[] raw = request.getInputStream().readNBytes(ReviewReport.MAX_BYTES + 1);
        if (raw.length > ReviewReport.MAX_BYTES) throw new ReviewException("REPORT_TOO_LARGE");
        return ReviewReport.decode(raw, type, "REPORT_REQUEST_INVALID");
    }

    @GetMapping
    public ResponseEntity<ReviewReport.View> get(
            @PathVariable UUID sessionId,
            @PathVariable UUID runId,
            @RequestParam(required = false) Integer revision) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.get(sessionId, runId, revision));
    }

    @PostMapping
    public ReviewReport.View create(
            @PathVariable UUID sessionId, @PathVariable UUID runId, HttpServletRequest request)
            throws IOException {
        return service.create(
                sessionId, runId, body(request, ReviewReport.CreateRequest.class).requestId());
    }

    @PutMapping
    public ReviewReport.View edit(
            @PathVariable UUID sessionId, @PathVariable UUID runId, HttpServletRequest request)
            throws IOException {
        return service.edit(sessionId, runId, body(request, ReviewReport.EditRequest.class));
    }

    @PostMapping("/approve")
    public ReviewReport.Approval approve(
            @PathVariable UUID sessionId, @PathVariable UUID runId, HttpServletRequest request)
            throws IOException {
        return service.approve(sessionId, runId, body(request, ReviewReport.ApprovalRequest.class));
    }

    @PostMapping("/close")
    public ReviewReport.View close(
            @PathVariable UUID sessionId, @PathVariable UUID runId, HttpServletRequest request)
            throws IOException {
        return service.close(sessionId, runId, body(request, ReviewReport.CloseRequest.class));
    }
}
