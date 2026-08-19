package com.codingreview.controlplane.api.session;

import com.codingreview.controlplane.application.session.CodingTurnApplicationService;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.observability.run.stream.RunEventStreamHub;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** 为 Coding Turn 提供断点续传的 SSE 事件流。 */
@Validated
@RestController
@RequestMapping("/api/v1/coding-sessions/{sessionId}/turns/{turnId}/stream")
public class CodingTurnEventStreamController {
    private final CodingTurnApplicationService turns;
    private final RunEventJournal journal;
    private final RunEventStreamHub hub;
    private final ExecutorService executor;

    /** 装配 Turn SSE 依赖。 */
    public CodingTurnEventStreamController(CodingTurnApplicationService turns, RunEventJournal journal,
                                           RunEventStreamHub hub, @Qualifier("runEventStreamExecutor") ExecutorService executor) {
        this.turns = turns; this.journal = journal; this.hub = hub; this.executor = executor;
    }

    /** 先补发历史，再发送当前 Turn 的实时事件。 */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID sessionId, @PathVariable UUID turnId,
                             @RequestParam(defaultValue = "0") long afterSequence,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        turns.get(sessionId, turnId);
        long cursor = afterSequence;
        if (lastEventId != null && !lastEventId.isBlank()) cursor = Math.max(cursor, Long.parseLong(lastEventId));
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        RunEventStreamHub.Subscription sub = hub.subscribe(turnId.toString());
        AtomicBoolean done = new AtomicBoolean();
        Runnable cleanup = () -> { done.set(true); sub.close(); };
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(e -> cleanup.run());
        long initialCursor = cursor;
        executor.execute(() -> send(emitter, sub, turnId, initialCursor, done));
        return emitter;
    }

    /** 按序发送 Turn 事件并在终态后关闭连接。 */
    private void send(SseEmitter emitter, RunEventStreamHub.Subscription sub, UUID turnId, long cursor, AtomicBoolean done) {
        try (sub) {
            for (RunEvent event : journal.readAfter(turnId.toString(), cursor)) {
                cursor = sendOne(emitter, event, cursor);
                if (terminal(event.type())) { emitter.complete(); return; }
            }
            while (!done.get() && !sub.closed()) {
                RunEvent event = sub.poll(Duration.ofSeconds(15));
                if (event == null) { emitter.send(SseEmitter.event().comment("heartbeat")); continue; }
                cursor = sendOne(emitter, event, cursor);
                if (terminal(event.type())) { emitter.complete(); return; }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); emitter.completeWithError(e); }
        catch (IOException | RuntimeException e) { if (!done.get()) emitter.completeWithError(e); }
    }

    /** 发送单条新事件并推进游标。 */
    private long sendOne(SseEmitter emitter, RunEvent event, long cursor) throws IOException {
        if (event.sequence() <= cursor) return cursor;
        emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("run-event").data(event));
        return event.sequence();
    }

    /** 判断事件是否结束当前 Turn。 */
    private boolean terminal(RunEventType type) { return type == RunEventType.RUN_COMPLETED || type == RunEventType.RUN_FAILED || type == RunEventType.RUN_CANCELLED; }
}
