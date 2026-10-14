package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.review.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/** Explicit local user actions only. GET never reconciles, queues, or captures repository input. */
@RestController
@RequestMapping("/api/v1/coding-sessions/{sessionId}/review-runs/{runId}/handoffs")
public final class ReviewHandoffController {
    private final ReviewHandoffService service;

    public ReviewHandoffController(ReviewHandoffService service) {
        this.service = service;
    }

    private <T> T body(HttpServletRequest request, Class<T> type) throws IOException {
        byte[] raw = request.getInputStream().readNBytes(ReviewReport.MAX_BYTES + 1);
        if (raw.length > ReviewReport.MAX_BYTES) throw new ReviewException("REPORT_TOO_LARGE");
        return ReviewReport.decode(raw, type, "HANDOFF_REQUEST_INVALID");
    }

    @GetMapping
    public ResponseEntity<List<ReviewHandoff.View>> list(
            @PathVariable UUID sessionId, @PathVariable UUID runId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.list(sessionId, runId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReviewHandoff.View> get(
            @PathVariable UUID sessionId, @PathVariable UUID runId, @PathVariable UUID id) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.get(sessionId, runId, id));
    }

    @PostMapping
    public ReviewHandoff.View prepare(
            @PathVariable UUID sessionId, @PathVariable UUID runId, HttpServletRequest request)
            throws IOException {
        return service.prepare(sessionId, runId, body(request, ReviewHandoff.PrepareRequest.class));
    }

    @PostMapping("/{id}/start")
    public ReviewHandoff.View start(
            @PathVariable UUID sessionId,
            @PathVariable UUID runId,
            @PathVariable UUID id,
            HttpServletRequest request)
            throws IOException {
        return service.start(sessionId, runId, id, body(request, ReviewHandoff.StartRequest.class));
    }

    @PostMapping("/{id}/revoke")
    public ReviewHandoff.View revoke(
            @PathVariable UUID sessionId,
            @PathVariable UUID runId,
            @PathVariable UUID id,
            HttpServletRequest request)
            throws IOException {
        return service.revoke(
                sessionId, runId, id, body(request, ReviewHandoff.RevokeRequest.class));
    }
}
