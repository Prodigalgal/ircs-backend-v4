package com.prodigalgal.ircs.task.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class TaskRuntimeConfigurationTest {

    @Test
    void collectionTaskRunnerExecutorBlocksAndEventuallyQueuesWhenQueueIsFull() throws Exception {
        TaskRuntimeConfiguration configuration = new TaskRuntimeConfiguration();
        Executor executor = configuration.collectionTaskRunnerExecutor(1, 1, 1);
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch thirdRan = new CountDownLatch(1);

            executor.execute(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            });
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {
            });

            Thread submitter = new Thread(() -> executor.execute(thirdRan::countDown), "blocking-submit-test");
            submitter.start();
            Thread.sleep(100);
            assertThat(submitter.isAlive()).isTrue();
            assertThat(thirdRan.getCount()).isEqualTo(1);

            releaseFirst.countDown();

            submitter.join(Duration.ofSeconds(2));
            assertThat(submitter.isAlive()).isFalse();
            assertThat(thirdRan.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
