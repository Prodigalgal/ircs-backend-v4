package com.prodigalgal.ircs.common.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

class DistributedLockMetrics {

    static final String OPERATIONS = "ircs.distributed.lock.operations";
    static final String DURATION = "ircs.distributed.lock.operation.duration";
    static final String WAIT = "ircs.distributed.lock.wait";

    private final MeterRegistry registry;

    DistributedLockMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    static DistributedLockMetrics noop() {
        return new DistributedLockMetrics(null);
    }

    void recordOperation(String lockType, String operation, String result, Duration duration) {
        if (registry == null) {
            return;
        }
        Counter.builder(OPERATIONS)
                .tag("lock_type", safe(lockType))
                .tag("operation", safe(operation))
                .tag("result", safe(result))
                .register(registry)
                .increment();
        Timer.builder(DURATION)
                .tag("lock_type", safe(lockType))
                .tag("operation", safe(operation))
                .tag("result", safe(result))
                .register(registry)
                .record(duration == null || duration.isNegative() ? Duration.ZERO : duration);
    }

    void recordWait(String lockType, String result, Duration wait) {
        if (registry == null || wait == null || wait.isNegative()) {
            return;
        }
        Timer.builder(WAIT)
                .tag("lock_type", safe(lockType))
                .tag("result", safe(result))
                .register(registry)
                .record(wait);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
