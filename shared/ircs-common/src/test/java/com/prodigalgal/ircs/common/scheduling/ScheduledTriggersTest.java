package com.prodigalgal.ircs.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class ScheduledTriggersTest {

    @Test
    void submitsTaskThroughExecutor() {
        AtomicBoolean ran = new AtomicBoolean(false);
        Logger log = mock(Logger.class);

        ScheduledTriggers.submit(Runnable::run, () -> ran.set(true), log, "test.trigger");

        assertThat(ran).isTrue();
        verify(log, never()).warn(any(String.class), any(), any(), any());
    }

    @Test
    void logsTaskFailureWithTriggerName() {
        Logger log = mock(Logger.class);

        ScheduledTriggers.submit(Runnable::run, () -> {
            throw new IllegalStateException("boom");
        }, log, "test.trigger");

        verify(log).warn(
                eq("Scheduled trigger failed: trigger={}, reason={}"),
                eq("test.trigger"),
                eq("boom"),
                any(IllegalStateException.class));
    }

    @Test
    void logsRejectedTaskWithTriggerName() {
        Logger log = mock(Logger.class);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("closed");
        };

        ScheduledTriggers.submit(rejectingExecutor, () -> {
        }, log, "test.trigger");

        verify(log).warn(
                eq("Scheduled trigger rejected: trigger={}, reason={}"),
                eq("test.trigger"),
                eq("closed"),
                any(RejectedExecutionException.class));
    }
}
