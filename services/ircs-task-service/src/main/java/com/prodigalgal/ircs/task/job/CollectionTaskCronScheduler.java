package com.prodigalgal.ircs.task.job;

import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.task.application.TaskCommandService;
import com.prodigalgal.ircs.task.domain.ScheduledTaskDefinition;
import com.prodigalgal.ircs.task.domain.TaskConfigurationChangedEvent;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import jakarta.annotation.PreDestroy;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

@Service
@Slf4j
class CollectionTaskCronScheduler {

    private final JdbcCollectionTaskRepository taskRepository;
    private final TaskCommandService taskCommandService;
    private final TaskScheduler scheduler;
    private final boolean enabled;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-collection-task-cron-trigger-vt-");
    private final Map<UUID, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    CollectionTaskCronScheduler(
            JdbcCollectionTaskRepository taskRepository,
            TaskCommandService taskCommandService,
            @Qualifier("collectionTaskSchedulerExecutor") TaskScheduler scheduler,
            @Value("${app.task.scheduler.enabled:true}") boolean enabled) {
        this.taskRepository = taskRepository;
        this.taskCommandService = taskCommandService;
        this.scheduler = scheduler;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void initialize() {
        if (!enabled) {
            log.info("Collection task cron scheduler is disabled");
            return;
        }
        refreshAll();
    }

    @EventListener
    void onTaskConfigurationChanged(TaskConfigurationChangedEvent event) {
        if (!enabled) {
            return;
        }
        if (event.deleted()) {
            cancel(event.taskId());
            return;
        }
        refresh(event.taskId());
    }

    void refreshAll() {
        if (!enabled) {
            return;
        }
        scheduledTasks.keySet().forEach(this::cancel);
        taskRepository.findScheduledTasks().forEach(this::schedule);
    }

    void refresh(UUID taskId) {
        if (!enabled) {
            return;
        }
        cancel(taskId);
        taskRepository.findScheduledTask(taskId).ifPresent(this::schedule);
    }

    void cancel(UUID taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    int scheduledCount() {
        return scheduledTasks.size();
    }

    private void schedule(ScheduledTaskDefinition task) {
        if (!Boolean.TRUE.equals(task.enabled()) || task.cronExpression() == null || task.cronExpression().isBlank()) {
            return;
        }
        try {
            ZoneId zoneId = task.timeZone() == null || task.timeZone().isBlank()
                    ? ZoneId.of("UTC")
                    : ZoneId.of(task.timeZone());
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> ScheduledTriggers.submit(
                            triggerExecutor,
                            () -> taskCommandService.startInternal(task.id(), false),
                            log,
                            "task.collection-cron." + task.id()),
                    new CronTrigger(task.cronExpression(), zoneId));
            if (future != null) {
                scheduledTasks.put(task.id(), future);
            }
        } catch (Exception ex) {
            log.warn("Collection task {} schedule skipped: {}", task.id(), ex.getMessage());
        }
    }
}
