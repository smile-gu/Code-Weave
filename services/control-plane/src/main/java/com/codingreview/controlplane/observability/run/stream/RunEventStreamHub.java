package com.codingreview.controlplane.observability.run.stream;

import com.codingreview.controlplane.observability.run.RunEvent;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 以有限内存缓冲连接已持久化事件和实时 SSE 订阅者。 */
public final class RunEventStreamHub {

    private static final int DEFAULT_SUBSCRIBER_BUFFER_SIZE = 1_024;

    private final int subscriberBufferSize;
    private final ConcurrentHashMap<String, Set<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    public RunEventStreamHub() {
        this(DEFAULT_SUBSCRIBER_BUFFER_SIZE);
    }

    RunEventStreamHub(int subscriberBufferSize) {
        if (subscriberBufferSize < 1) {
            throw new IllegalArgumentException("subscriberBufferSize must be positive");
        }
        this.subscriberBufferSize = subscriberBufferSize;
    }

    /** 为指定 Run 注册一个实时事件订阅。 */
    public Subscription subscribe(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Subscription subscription = new Subscription(runId);
        subscriptions.computeIfAbsent(runId, ignored -> ConcurrentHashMap.newKeySet())
                .add(subscription);
        return subscription;
    }

    /** 将持久化成功的事件非阻塞投递给当前订阅者。 */
    public void publish(RunEvent event) {
        Objects.requireNonNull(event, "event");
        Set<Subscription> runSubscriptions = subscriptions.get(event.runId());
        if (runSubscriptions == null) {
            return;
        }
        for (Subscription subscription : runSubscriptions) {
            if (!subscription.offer(event)) {
                subscription.close();
            }
        }
    }

    /** 从 Hub 中移除已经结束的订阅。 */
    private void remove(Subscription subscription) {
        subscriptions.computeIfPresent(subscription.runId, (runId, runSubscriptions) -> {
            runSubscriptions.remove(subscription);
            return runSubscriptions.isEmpty() ? null : runSubscriptions;
        });
    }

    /** 单个 SSE 客户端使用的有限事件队列。 */
    public final class Subscription implements AutoCloseable {

        private final String runId;
        private final ArrayBlockingQueue<RunEvent> events =
                new ArrayBlockingQueue<>(subscriberBufferSize);
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscription(String runId) {
            this.runId = runId;
        }

        /** 在指定时间内等待一条新的实时事件。 */
        public RunEvent poll(Duration timeout) throws InterruptedException {
            Objects.requireNonNull(timeout, "timeout");
            return events.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        /** 判断订阅是否已经关闭。 */
        public boolean closed() {
            return closed.get();
        }

        /** 尝试将事件写入有限缓冲区。 */
        private boolean offer(RunEvent event) {
            return !closed.get() && events.offer(event);
        }

        /** 关闭订阅并释放 Hub 中的注册关系。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                remove(this);
            }
        }
    }
}
