package com.prodigalgal.ircs.common.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prodigalgal.ircs.common.lease.ClusterLease;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DistributedLockServiceTest {

    @Test
    void runWithLockSkipsActionWhenLockIsHeldElsewhere() {
        FakeDistributedLockService service = new FakeDistributedLockService(false);
        AtomicBoolean executed = new AtomicBoolean(false);

        boolean acquired = service.runWithLock(
                "ops:maintenance",
                "worker-1",
                Duration.ofSeconds(30),
                () -> executed.set(true));

        assertThat(acquired).isFalse();
        assertThat(executed).isFalse();
        assertThat(service.releaseAttempts).isZero();
    }

    @Test
    void runWithLockReleasesAfterSuccessfulAction() {
        FakeDistributedLockService service = new FakeDistributedLockService(true);
        AtomicBoolean executed = new AtomicBoolean(false);

        boolean acquired = service.runWithLock(
                "ops:maintenance",
                "worker-1",
                Duration.ofSeconds(30),
                () -> executed.set(true));

        assertThat(acquired).isTrue();
        assertThat(executed).isTrue();
        assertThat(service.releaseAttempts).isEqualTo(1);
    }

    @Test
    void runWithLockReleasesAfterFailure() {
        FakeDistributedLockService service = new FakeDistributedLockService(true);

        assertThatThrownBy(() -> service.runWithLock(
                "ops:maintenance",
                "worker-1",
                Duration.ofSeconds(30),
                () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(service.releaseAttempts).isEqualTo(1);
    }

    private static class FakeDistributedLockService implements DistributedLockService {

        private final boolean acquire;
        private int releaseAttempts;

        private FakeDistributedLockService(boolean acquire) {
            this.acquire = acquire;
        }

        @Override
        public Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl) {
            if (!acquire) {
                return Optional.empty();
            }
            Instant now = Instant.parse("2026-06-11T12:00:00Z");
            return Optional.of(new ClusterLease(name, ownerId, ownerId + ":token", now, now.plus(ttl)));
        }

        @Override
        public boolean renew(ClusterLease lease, Duration ttl) {
            return true;
        }

        @Override
        public boolean release(ClusterLease lease) {
            releaseAttempts++;
            return true;
        }
    }
}
