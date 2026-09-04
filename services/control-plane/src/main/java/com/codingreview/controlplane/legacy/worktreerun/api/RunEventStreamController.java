package com.codingreview.controlplane.legacy.worktreerun.api;

import com.codingreview.controlplane.legacy.worktreerun.api.stream.RunEventSseService;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** 向 Web 工作台提供 Run 事件的 SSE 实时订阅入口。 */
@Validated
@RestController
@Profile("legacy-worktree-run")
@RequestMapping("/api/v1/legacy/worktree-runs")
public class RunEventStreamController {

    private final RunEventSseService sseService;

    public RunEventStreamController(RunEventSseService sseService) {
        this.sseService = sseService;
    }

    /** 根据查询游标或 Last-Event-ID 打开 Run 事件流。 */
    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "afterSequence 不能小于 0") long afterSequence,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return sseService.open(runId, resolveCursor(afterSequence, lastEventId));
    }

    /** 合并显式查询游标与浏览器断线重连游标。 */
    private long resolveCursor(long afterSequence, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return afterSequence;
        }
        try {
            long resumedSequence = Long.parseLong(lastEventId);
            if (resumedSequence < 0) {
                throw new IllegalArgumentException("Last-Event-ID must not be negative");
            }
            return Math.max(afterSequence, resumedSequence);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be an integer", exception);
        }
    }
}
