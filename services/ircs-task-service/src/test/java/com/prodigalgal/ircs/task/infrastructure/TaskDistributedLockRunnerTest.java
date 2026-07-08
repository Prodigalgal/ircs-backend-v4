package com.prodigalgal.ircs.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.lock.DistributedLockProfiles;
import com.prodigalgal.ircs.common.lock.TimeSliceReservation;
import com.prodigalgal.ircs.common.lock.TimeSliceReservationRequest;
import com.prodigalgal.ircs.common.lock.TokenBucketReservation;
import com.prodigalgal.ircs.common.lock.TokenBucketReservationRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TaskDistributedLockRunnerTest {

    @Test
    void disabledLocalRunnerExecutesActionWithoutDistributedLock() {
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean acquired = TaskDistributedLockRunner.local().runExclusive("task:default-seed", () -> ran.set(true));

        assertThat(acquired).isTrue();
        assertThat(ran).isTrue();
    }

    @Test
    void enabledRunnerUsesMaintenanceMutexProfileAndUniqueWorkerId() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(true);
        TaskDistributedLockRunner runner = new TaskDistributedLockRunner(
                lockManager,
                "ircs-task-service",
                "task-pod-1",
                true,
                Duration.ofSeconds(30));
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean acquired = runner.runExclusive("task:watchdog", () -> ran.set(true));

        assertThat(acquired).isTrue();
        assertThat(ran).isTrue();
        assertThat(lockManager.lastName).isEqualTo("lock:task:watchdog");
        assertThat(lockManager.lastOwnerId).isEqualTo("task-pod-1");
        assertThat(lockManager.lastTtl).isEqualTo(Duration.ofSeconds(30));
        assertThat(lockManager.released).isTrue();
    }

    @Test
    void enabledRunnerSkipsActionWhenLockIsHeldElsewhere() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(false);
        TaskDistributedLockRunner runner = new TaskDistributedLockRunner(
                lockManager,
                "ircs-task-service",
                "task-pod-1",
                true,
                Duration.ofSeconds(30));
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean acquired = runner.runExclusive("task:watchdog", () -> ran.set(true));

        assertThat(acquired).isFalse();
        assertThat(ran).isFalse();
        assertThat(lockManager.released).isFalse();
    }

    private static class FakeDistributedLockManager implements DistributedLockManager {

        private final boolean acquire;
        private String lastName;
        private String lastOwnerId;
        private Duration lastTtl;
        private boolean released;

        private FakeDistributedLockManager(boolean acquire) {
            this.acquire = acquire;
        }

        @Override
        public DistributedLockProfile profileFor(DistributedLockBusinessType businessType) {
            return DistributedLockProfiles.profileFor(businessType);
        }

        @Override
        public Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl) {
            this.lastName = name;
            this.lastOwnerId = ownerId;
            this.lastTtl = ttl;
            if (!acquire) {
                return Optional.empty();
            }
            Instant now = Instant.parse("2026-06-11T00:00:00Z");
            return Optional.of(new ClusterLease(name, ownerId, ownerId + ":token", now, now.plus(ttl)));
        }

        @Override
        public boolean renew(ClusterLease lease, Duration ttl) {
            return true;
        }

        @Override
        public boolean release(ClusterLease lease) {
            released = true;
            return true;
        }

        @Override
        public TimeSliceReservation reserveTimeSlice(TimeSliceReservationRequest request) {
            throw new UnsupportedOperationException("not used by task mutex runner");
        }

        @Override
        public TokenBucketReservation reserveTokenBucket(TokenBucketReservationRequest request) {
            throw new UnsupportedOperationException("not used by task mutex runner");
        }
    }
}
