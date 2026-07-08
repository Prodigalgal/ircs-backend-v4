package com.prodigalgal.ircs.search.config;

import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class SearchSchedulingConfiguration {

    public static final String SEARCH_SYNC_EXECUTOR = "searchSyncWorkerExecutor";
    public static final String AUDIT_ES_REPLICATION_EXECUTOR = "auditEsReplicationWorkerExecutor";

    @Bean(name = "taskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:4}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, poolSize));
        scheduler.setThreadNamePrefix("ircs-search-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean(name = SEARCH_SYNC_EXECUTOR, destroyMethod = "close")
    ExecutorService searchSyncWorkerExecutor() {
        return ScheduledTriggers.virtualThreadExecutor("ircs-search-sync-vt-");
    }

    @Bean(name = AUDIT_ES_REPLICATION_EXECUTOR, destroyMethod = "close")
    ExecutorService auditEsReplicationWorkerExecutor() {
        return ScheduledTriggers.virtualThreadExecutor("ircs-search-audit-es-vt-");
    }
}
