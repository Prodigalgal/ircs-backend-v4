package com.prodigalgal.ircs.search.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SearchSchedulingConfigurationTest {

    private final SearchSchedulingConfiguration configuration = new SearchSchedulingConfiguration();

    @Test
    void workerExecutorsUseNamedVirtualThreads() throws Exception {
        try (ExecutorService executor = configuration.searchSyncWorkerExecutor()) {
            Future<ThreadSnapshot> snapshot = executor.submit(() ->
                    new ThreadSnapshot(Thread.currentThread().getName(), Thread.currentThread().isVirtual()));

            ThreadSnapshot thread = snapshot.get();

            assertThat(thread.virtual()).isTrue();
            assertThat(thread.name()).startsWith("ircs-search-sync-vt-");
        }
    }

    @Test
    void auditWorkerExecutorUsesNamedVirtualThreads() throws Exception {
        try (ExecutorService executor = configuration.auditEsReplicationWorkerExecutor()) {
            Future<ThreadSnapshot> snapshot = executor.submit(() ->
                    new ThreadSnapshot(Thread.currentThread().getName(), Thread.currentThread().isVirtual()));

            ThreadSnapshot thread = snapshot.get();

            assertThat(thread.virtual()).isTrue();
            assertThat(thread.name()).startsWith("ircs-search-audit-es-vt-");
        }
    }

    @Test
    void taskSchedulerUsesBoundedNamedPlatformPool() {
        ThreadPoolTaskScheduler scheduler = configuration.taskScheduler(1);
        try {
            scheduler.initialize();

            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("ircs-search-scheduler-");
        } finally {
            scheduler.shutdown();
        }
    }

    private record ThreadSnapshot(String name, boolean virtual) {
    }
}
