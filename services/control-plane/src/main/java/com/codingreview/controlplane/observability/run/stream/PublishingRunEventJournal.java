package com.codingreview.controlplane.observability.run.stream;

import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/** 在可靠写入事实日志后尽力通知实时订阅者。 */
public final class PublishingRunEventJournal implements RunEventJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublishingRunEventJournal.class);

    private final RunEventJournal delegate;
    private final RunEventStreamHub streamHub;

    public PublishingRunEventJournal(RunEventJournal delegate, RunEventStreamHub streamHub) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.streamHub = Objects.requireNonNull(streamHub, "streamHub");
    }

    /** 持久化事件后向实时通道发出非关键通知。 */
    @Override
    public RunEvent append(
            String runId,
            String stage,
            int attempt,
            RunEventType type,
            JsonNode payload
    ) {
        RunEvent event = delegate.append(runId, stage, attempt, type, payload);
        try {
            streamHub.publish(event);
        } catch (RuntimeException exception) {
            LOGGER.warn("Run 事件实时通知失败，不影响已持久化事实，runId={}，sequence={}",
                    event.runId(), event.sequence(), exception);
        }
        return event;
    }

    /** 从底层事实日志重放指定序号之后的事件。 */
    @Override
    public List<RunEvent> readAfter(String runId, long afterSequence) {
        return delegate.readAfter(runId, afterSequence);
    }
}
