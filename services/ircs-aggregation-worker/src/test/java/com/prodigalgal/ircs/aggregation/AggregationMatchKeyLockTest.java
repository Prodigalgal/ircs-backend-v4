package com.prodigalgal.ircs.aggregation;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class AggregationMatchKeyLockTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void releasesLocksOnlyAfterTransactionCompletionWhenSynchronizationIsActive() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager();
        AggregationMatchKeyLock matchKeyLock = new AggregationMatchKeyLock(
                lockManager,
                "ircs-aggregation-worker",
                "worker-1",
                true,
                Duration.ofMinutes(10));
        TransactionSynchronizationManager.initSynchronization();

        AggregationMatchKeyLock.MatchLockScope scope =
                matchKeyLock.tryAcquire(List.of("title:b", "title:a")).orElseThrow();
        scope.close();

        assertThat(lockManager.acquiredNames).containsExactly(
                "lock:aggregation:match:title:a",
                "lock:aggregation:match:title:b");
        assertThat(lockManager.releasedNames).isEmpty();

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(lockManager.releasedNames).containsExactly(
                "lock:aggregation:match:title:b",
                "lock:aggregation:match:title:a");
    }

    private static final class FakeDistributedLockManager implements DistributedLockManager {
        private final List<String> acquiredNames = new ArrayList<>();
        private final List<String> releasedNames = new ArrayList<>();

        @Override
        public Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl) {
            acquiredNames.add(name);
            Instant now = Instant.parse("2026-06-11T00:00:00Z");
            return Optional.of(new ClusterLease(name, ownerId, "token-" + acquiredNames.size(), now, now.plus(ttl)));
        }

        @Override
        public boolean renew(ClusterLease lease, Duration ttl) {
            return true;
        }

        @Override
        public boolean release(ClusterLease lease) {
            releasedNames.add(lease.name());
            return true;
        }

        @Override
        public DistributedLockProfile profileFor(DistributedLockBusinessType businessType) {
            return DistributedLockProfiles.profileFor(businessType);
        }

        @Override
        public TimeSliceReservation reserveTimeSlice(TimeSliceReservationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenBucketReservation reserveTokenBucket(TokenBucketReservationRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
