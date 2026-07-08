package com.prodigalgal.ircs.common.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LogRetentionSchedulerTest {

    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);

    @Test
    void deletesTargetWithDefaultThirtyDayRetention() {
        FakeRetentionTarget target = new FakeRetentionTarget("audit-es", 3L);
        LogRetentionScheduler scheduler = scheduler(target, fixedClock());
        when(runtimeConfig.booleanValue("app.log-retention.enabled", true)).thenReturn(true);
        when(runtimeConfig.booleanValue("app.log-retention.target.audit-es.enabled", true)).thenReturn(true);
        when(runtimeConfig.positiveDurationValue("app.log-retention.default-retention", Duration.ofDays(30)))
                .thenReturn(Duration.ofDays(30));
        when(runtimeConfig.positiveDurationValue("app.log-retention.target.audit-es.retention", Duration.ofDays(30)))
                .thenReturn(Duration.ofDays(30));

        List<LogRetentionResult> results = scheduler.runOnce();

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().deletedCount()).isEqualTo(3L);
        assertThat(target.cutoff).isEqualTo(Instant.parse("2026-05-22T00:00:00Z"));
    }

    @Test
    void targetRetentionOverridesGlobalRetention() {
        FakeRetentionTarget target = new FakeRetentionTarget("audit-es", 1L);
        LogRetentionScheduler scheduler = scheduler(target, fixedClock());
        when(runtimeConfig.booleanValue("app.log-retention.enabled", true)).thenReturn(true);
        when(runtimeConfig.booleanValue("app.log-retention.target.audit-es.enabled", true)).thenReturn(true);
        when(runtimeConfig.positiveDurationValue("app.log-retention.default-retention", Duration.ofDays(30)))
                .thenReturn(Duration.ofDays(30));
        when(runtimeConfig.positiveDurationValue("app.log-retention.target.audit-es.retention", Duration.ofDays(30)))
                .thenReturn(Duration.ofDays(7));

        scheduler.runOnce();

        assertThat(target.cutoff).isEqualTo(Instant.parse("2026-06-14T00:00:00Z"));
        assertThat(target.retention).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void disabledSchedulerSkipsTargets() {
        FakeRetentionTarget target = new FakeRetentionTarget("audit-es", 1L);
        LogRetentionScheduler scheduler = scheduler(target, fixedClock());
        when(runtimeConfig.booleanValue("app.log-retention.enabled", true)).thenReturn(false);

        List<LogRetentionResult> results = scheduler.runOnce();

        assertThat(results).isEmpty();
        assertThat(target.calls).isZero();
    }

    @Test
    void disabledTargetIsSkipped() {
        FakeRetentionTarget target = new FakeRetentionTarget("audit-es", 1L);
        LogRetentionScheduler scheduler = scheduler(target, fixedClock());
        when(runtimeConfig.booleanValue("app.log-retention.enabled", true)).thenReturn(true);
        when(runtimeConfig.booleanValue("app.log-retention.target.audit-es.enabled", true)).thenReturn(false);

        List<LogRetentionResult> results = scheduler.runOnce();

        assertThat(results).isEmpty();
        assertThat(target.calls).isZero();
    }

    @SuppressWarnings("unchecked")
    private LogRetentionScheduler scheduler(FakeRetentionTarget target, Clock clock) {
        ObjectProvider<LogRetentionTarget> targetProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        ObjectProvider<Clock> clockProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(targetProvider.orderedStream()).thenReturn(java.util.stream.Stream.of(target));
        when(clockProvider.getIfUnique()).thenReturn(clock);
        return new LogRetentionScheduler(targetProvider, runtimeConfig, clockProvider);
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);
    }

    private static final class FakeRetentionTarget implements LogRetentionTarget {
        private final String id;
        private final long deletedCount;
        private int calls;
        private Instant cutoff;
        private Duration retention;

        private FakeRetentionTarget(String id, long deletedCount) {
            this.id = id;
            this.deletedCount = deletedCount;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public LogRetentionResult deleteOlderThan(Instant cutoff, Duration retention) {
            calls++;
            this.cutoff = cutoff;
            this.retention = retention;
            return new LogRetentionResult(id, cutoff, retention, deletedCount);
        }
    }
}
