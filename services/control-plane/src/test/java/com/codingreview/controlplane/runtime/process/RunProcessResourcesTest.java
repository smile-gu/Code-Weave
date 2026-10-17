package com.codingreview.controlplane.runtime.process;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 验证读取线程仍存活时不能确认本轮资源已释放。 */
class RunProcessResourcesTest {
    @Test
    void liveReaderPreventsSuccessfulCleanupAndPreservesInterrupt() throws Exception {
        Process process = mock(Process.class);
        ProcessTreeCleaner cleaner = mock(ProcessTreeCleaner.class);
        when(cleaner.clean(any(), any(), any())).thenReturn(true);
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        executor.submit(() -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            var resources = new RunProcessResources(process, cleaner, Duration.ZERO, Duration.ofMillis(20));
            resources.readers(executor);
            Thread.currentThread().interrupt();
            assertThat(resources.finish()).isFalse();
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            Thread.interrupted();
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
