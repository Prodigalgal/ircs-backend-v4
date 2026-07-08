package com.prodigalgal.ircs.task.job;

import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.task.domain.ValidatedCreateTask;
import com.prodigalgal.ircs.task.infrastructure.DataSourceSeedCandidate;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Order(2)
@Slf4j
class DefaultCollectionTaskSeeder implements ApplicationRunner {

    static final String AUTO_PREFIX = "自动采集";
    static final String FULL_PREFIX = "全量采集";
    static final String AUTO_CRON = "0 0 3 * * ?";
    static final String TIME_ZONE = "Asia/Shanghai";
    static final int AUTO_FILTER_HOURS = 48;
    static final int START_PAGE = 1;
    static final int END_PAGE = 0;
    static final int FIXED_DELAY_MS = 0;
    static final int RANDOM_DELAY_MIN_MS = 3000;
    static final int RANDOM_DELAY_MAX_MS = 8000;
    static final int TIMEOUT_MS = 15000;
    static final int MAX_RETRIES = 3;

    private final JdbcCollectionTaskRepository taskRepository;
    private final CollectionTaskCronScheduler cronScheduler;
    private final TaskDistributedLockRunner lockRunner;
    private final boolean enabled;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-default-task-seed-trigger-vt-");

    DefaultCollectionTaskSeeder(
            JdbcCollectionTaskRepository taskRepository,
            CollectionTaskCronScheduler cronScheduler,
            TaskDistributedLockRunner lockRunner,
            @Value("${app.task.default-seed.enabled:true}") boolean enabled) {
        this.taskRepository = taskRepository;
        this.cronScheduler = cronScheduler;
        this.lockRunner = lockRunner;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedDefaultTasks();
    }

    @Scheduled(
            fixedDelayString = "${app.task.default-seed.fixed-delay-ms:60000}",
            initialDelayString = "${app.task.default-seed.initial-delay-ms:30000}")
    void scheduledSeedDefaultTasks() {
        ScheduledTriggers.submit(triggerExecutor, this::seedDefaultTasks, log, "task.default-seed.run");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    void seedDefaultTasks() {
        if (!enabled) {
            return;
        }
        if (!lockRunner.runExclusive("task:default-seed", this::seedDefaultTasksLocked)) {
            log.debug("Default collection task seed skipped: distributed lock is held by another instance");
        }
    }

    private void seedDefaultTasksLocked() {
        int inserted = 0;
        for (DataSourceSeedCandidate dataSource : taskRepository.findDefaultTaskSeedDataSources()) {
            inserted += ensureTask(dataSource, AUTO_PREFIX, AUTO_CRON, AUTO_FILTER_HOURS) ? 1 : 0;
            inserted += ensureTask(dataSource, FULL_PREFIX, null, null) ? 1 : 0;
        }
        if (inserted > 0) {
            log.info("Default collection task seed inserted {} tasks", inserted);
            cronScheduler.refreshAll();
        }
    }

    private boolean ensureTask(
            DataSourceSeedCandidate dataSource,
            String prefix,
            String cronExpression,
            Integer filterHours
    ) {
        String taskName = prefix + " - " + dataSource.name();
        if (taskRepository.existsTaskByName(taskName)) {
            return false;
        }
        try {
            taskRepository.create(defaultTask(taskName, dataSource.id(), cronExpression, filterHours));
            return true;
        } catch (DuplicateKeyException ex) {
            log.debug("Default collection task [{}] already exists", taskName);
            return false;
        } catch (RuntimeException ex) {
            log.warn("Default collection task seed skipped [{}]: {}", taskName, ex.getMessage());
            return false;
        }
    }

    private ValidatedCreateTask defaultTask(
            String name,
            UUID dataSourceId,
            String cronExpression,
            Integer filterHours
    ) {
        return new ValidatedCreateTask(
                name,
                dataSourceId,
                "BY_PAGE",
                true,
                cronExpression,
                TIME_ZONE,
                START_PAGE,
                END_PAGE,
                null,
                filterHours,
                null,
                "RANDOM",
                FIXED_DELAY_MS,
                RANDOM_DELAY_MIN_MS,
                RANDOM_DELAY_MAX_MS,
                TIMEOUT_MS,
                MAX_RETRIES,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
