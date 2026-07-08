package com.prodigalgal.ircs.task.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class TaskRuntimeConfiguration {

    @Bean
    Executor collectionTaskRunnerExecutor(
            @Value("${app.task.runner.concurrency:1}") int concurrency,
            @Value("${app.task.runner.queue-capacity:500}") int queueCapacity,
            @Value("${app.task.runner.await-termination-seconds:300}") int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("collection-task-runner-");
        executor.setCorePoolSize(Math.max(1, concurrency));
        executor.setMaxPoolSize(Math.max(1, concurrency));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setRejectedExecutionHandler(new BlockingRejectedExecutionHandler());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(1, awaitTerminationSeconds));
        executor.initialize();
        return executor;
    }

    @Bean
    Executor taskQueueDispatchExecutor(
            @Value("${app.task.queue.dispatch.concurrency:2}") int concurrency,
            @Value("${app.task.queue.dispatch.queue-capacity:500}") int queueCapacity,
            @Value("${app.task.queue.dispatch.await-termination-seconds:120}") int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("task-queue-dispatch-");
        executor.setCorePoolSize(Math.max(1, concurrency));
        executor.setMaxPoolSize(Math.max(1, concurrency));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setRejectedExecutionHandler(new BlockingRejectedExecutionHandler());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(1, awaitTerminationSeconds));
        executor.initialize();
        return executor;
    }

    @Bean
    TaskScheduler collectionTaskSchedulerExecutor() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("collection-task-scheduler-");
        scheduler.initialize();
        return scheduler;
    }

    static class BlockingRejectedExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor == null || executor.isShutdown()) {
                throw new RejectedExecutionException("collection task runner executor is shut down");
            }
            try {
                executor.getQueue().put(task);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("interrupted while waiting to enqueue collection task", ex);
            }
        }
    }
}
