package com.codingreview.controlplane.legacy.worktreerun.api.stream;

import com.codingreview.controlplane.legacy.worktreerun.application.WorktreeRunApplicationService;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.codingreview.controlplane.observability.run.stream.RunEventStreamHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 负责 Run 历史事件补发、实时 SSE 推送、心跳和连接清理。 */
@Service
@Profile("legacy-worktree-run")
public class RunEventSseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunEventSseService.class);
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final String SSE_EVENT_NAME = "run-event";

    private final WorktreeRunApplicationService applicationService;
    private final RunEventJournal eventJournal;
    private final RunEventStreamHub streamHub;
    private final ExecutorService streamExecutor;

    public RunEventSseService(
            WorktreeRunApplicationService applicationService,
            RunEventJournal eventJournal,
            RunEventStreamHub streamHub,
            @Qualifier("runEventStreamExecutor") ExecutorService streamExecutor
    ) {
        this.applicationService = applicationService;
        this.eventJournal = eventJournal;
        this.streamHub = streamHub;
        this.streamExecutor = streamExecutor;
    }

    /** 打开一个先补历史再持续接收实时事件的 SSE 连接。 */
    public SseEmitter open(UUID runId, long afterSequence) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        applicationService.get(runId);

        RunEventStreamHub.Subscription subscription = streamHub.subscribe(runId.toString());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        AtomicBoolean completed = new AtomicBoolean();
        Runnable cleanup = () -> {
            completed.set(true);
            subscription.close();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        try {
            streamExecutor.execute(() -> stream(runId, afterSequence, emitter, subscription, completed));
        } catch (RejectedExecutionException exception) {
            subscription.close();
            throw new IllegalStateException("Run event stream executor rejected connection", exception);
        }
        return emitter;
    }

    /** 按序发送历史和实时事件，并在终态后结束连接。 */
    private void stream(
            UUID runId,
            long afterSequence,
            SseEmitter emitter,
            RunEventStreamHub.Subscription subscription,
            AtomicBoolean completed
    ) {
        long cursor = afterSequence;
        try (subscription) {
            for (RunEvent event : eventJournal.readAfter(runId.toString(), cursor)) {
                cursor = sendIfNew(emitter, event, cursor);
                if (terminal(event.type())) {
                    complete(emitter, completed);
                    return;
                }
            }

            while (!completed.get() && !subscription.closed()) {
                RunEvent event = subscription.poll(HEARTBEAT_INTERVAL);
                if (event == null) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    continue;
                }
                cursor = sendIfNew(emitter, event, cursor);
                if (terminal(event.type())) {
                    complete(emitter, completed);
                    return;
                }
            }
            if (!completed.get()) {
                fail(runId, emitter, completed,
                        new IllegalStateException("Run SSE subscriber buffer was closed"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(runId, emitter, completed, exception);
        } catch (IOException | RuntimeException exception) {
            fail(runId, emitter, completed, exception);
        }
    }

    /** 仅发送序号大于当前游标的事件并推进游标。 */
    private long sendIfNew(SseEmitter emitter, RunEvent event, long cursor) throws IOException {
        if (event.sequence() <= cursor) {
            return cursor;
        }
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.sequence()))
                .name(SSE_EVENT_NAME)
                .data(event));
        return event.sequence();
    }

    /** 判断事件是否代表 Run 已进入终态。 */
    private boolean terminal(RunEventType type) {
        return type == RunEventType.RUN_COMPLETED
                || type == RunEventType.RUN_FAILED
                || type == RunEventType.RUN_CANCELLED;
    }

    /** 正常结束 SSE 连接并触发订阅清理。 */
    private void complete(SseEmitter emitter, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    /** 在记录连接异常后结束 SSE，且不影响 Run 执行。 */
    private void fail(
            UUID runId,
            SseEmitter emitter,
            AtomicBoolean completed,
            Exception exception
    ) {
        LOGGER.debug("Run SSE 连接已结束，runId={}", runId, exception);
        if (completed.compareAndSet(false, true)) {
            emitter.completeWithError(exception);
        }
    }
}
