package com.prodigalgal.ircs.common.scheduling;

import com.prodigalgal.ircs.common.concurrent.VirtualThreadExecutors;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;

public final class ScheduledTriggers {

    private ScheduledTriggers() {
    }

    public static ExecutorService virtualThreadExecutor(String threadNamePrefix) {
        return VirtualThreadExecutors.newPerTaskExecutor(threadNamePrefix);
    }

    public static void submit(Executor executor, Runnable task, Logger log, String triggerName) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(triggerName, "triggerName");
        try {
            executor.execute(() -> runSafely(task, log, triggerName));
        } catch (RejectedExecutionException ex) {
            log.warn("Scheduled trigger rejected: trigger={}, reason={}", triggerName, ex.getMessage(), ex);
        }
    }

    private static void runSafely(Runnable task, Logger log, String triggerName) {
        try {
            task.run();
        } catch (RuntimeException ex) {
            log.warn("Scheduled trigger failed: trigger={}, reason={}", triggerName, ex.getMessage(), ex);
        }
    }
}
