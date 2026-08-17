package com.codingreview.controlplane.observability.run.stream;

import com.codingreview.controlplane.observability.run.FileRunEventJournal;
import com.codingreview.controlplane.observability.run.RunEvent;
import com.codingreview.controlplane.observability.run.RunEventJournal;
import com.codingreview.controlplane.observability.run.RunEventType;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证实时通知不会破坏事件持久化和慢订阅隔离。 */
class RunEventStreamHubTest {

    @TempDir
    Path tempDir;

    @Test
    void publishesOnlyAfterDurableAppend() throws Exception {
        RunEventStreamHub hub = new RunEventStreamHub();
        RunEventJournal journal = new PublishingRunEventJournal(
                new FileRunEventJournal(tempDir), hub
        );
        try (RunEventStreamHub.Subscription subscription = hub.subscribe("run-stream")) {
            RunEvent appended = journal.append(
                    "run-stream",
                    "run",
                    1,
                    RunEventType.RUN_CREATED,
                    JsonMapper.builder().build().createObjectNode()
            );

            assertThat(subscription.poll(Duration.ofSeconds(1))).isEqualTo(appended);
            assertThat(journal.readAfter("run-stream", 0)).containsExactly(appended);
        }
    }

    @Test
    void closesSlowSubscriptionWhenItsBufferIsFull() throws Exception {
        RunEventStreamHub hub = new RunEventStreamHub(1);
        RunEventStreamHub.Subscription subscription = hub.subscribe("run-slow");
        RunEventJournal journal = new FileRunEventJournal(tempDir);

        RunEvent first = journal.append("run-slow", "run", 1,
                RunEventType.RUN_CREATED, JsonMapper.builder().build().createObjectNode());
        RunEvent second = journal.append("run-slow", "run", 1,
                RunEventType.RUN_QUEUED, JsonMapper.builder().build().createObjectNode());
        hub.publish(first);
        hub.publish(second);

        assertThat(subscription.closed()).isTrue();
        assertThat(subscription.poll(Duration.ZERO)).isEqualTo(first);
        assertThat(journal.readAfter("run-slow", 0)).containsExactly(first, second);
    }
}
