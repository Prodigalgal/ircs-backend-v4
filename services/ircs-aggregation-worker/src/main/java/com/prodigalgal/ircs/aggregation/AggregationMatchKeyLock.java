package com.prodigalgal.ircs.aggregation;

import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Component
@Slf4j
class AggregationMatchKeyLock {

    private final DistributedLockManager lockManager;
    private final String workerId;
    private final boolean enabled;
    private final Duration ttl;

    AggregationMatchKeyLock(
            DistributedLockManager lockManager,
            @Value("${spring.application.name:ircs-aggregation-worker}") String applicationName,
            @Value("${app.aggregation.match-lock.worker-id:${APP_AGGREGATION_MATCH_LOCK_WORKER_ID:}}")
                    String configuredWorkerId,
            @Value("${app.aggregation.match-lock.enabled:true}") boolean enabled,
            @Value("${app.aggregation.match-lock.ttl:PT10M}") Duration ttl) {
        this.lockManager = lockManager;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.enabled = enabled;
        this.ttl = ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl;
    }

    static AggregationMatchKeyLock localNoop() {
        return new AggregationMatchKeyLock(
                null,
                "ircs-aggregation-worker",
                "local-test",
                false,
                Duration.ofMinutes(10));
    }

    Optional<MatchLockScope> tryAcquire(Collection<String> rawKeys) {
        if (!enabled) {
            return Optional.of(MatchLockScope.noop());
        }
        if (lockManager == null) {
            throw new IllegalStateException("aggregation distributed lock manager is unavailable");
        }
        List<String> keys = normalize(rawKeys);
        if (keys.isEmpty()) {
            return Optional.of(MatchLockScope.noop());
        }

        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.AGGREGATION_MATCH);
        List<ClusterLease> leases = new ArrayList<>();
        for (String key : keys) {
            String lockName = profile.keyPrefix() + key;
            Optional<ClusterLease> lease = lockManager.tryAcquire(lockName, workerId, ttl);
            if (lease.isEmpty()) {
                releaseAll(leases);
                return Optional.empty();
            }
            leases.add(lease.orElseThrow());
        }
        MatchLockScope scope = new MatchLockScope(lockManager, leases);
        registerTransactionRelease(scope);
        return Optional.of(scope);
    }

    private void registerTransactionRelease(MatchLockScope scope) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        scope.transactionManaged = true;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                scope.release();
            }
        });
    }

    private List<String> normalize(Collection<String> rawKeys) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            return List.of();
        }
        return rawKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    private void releaseAll(List<ClusterLease> leases) {
        for (int i = leases.size() - 1; i >= 0; i--) {
            try {
                lockManager.release(leases.get(i));
            } catch (RuntimeException ex) {
                log.warn("Aggregation match lock release failed: {}", leases.get(i).name(), ex);
            }
        }
    }

    static final class MatchLockScope implements AutoCloseable {
        private final DistributedLockManager lockManager;
        private final List<ClusterLease> leases;
        private boolean transactionManaged;
        private boolean released;

        private MatchLockScope(DistributedLockManager lockManager, List<ClusterLease> leases) {
            this.lockManager = lockManager;
            this.leases = List.copyOf(leases);
        }

        static MatchLockScope noop() {
            return new MatchLockScope(null, List.of());
        }

        @Override
        public void close() {
            if (!transactionManaged) {
                release();
            }
        }

        private void release() {
            if (released || lockManager == null) {
                return;
            }
            released = true;
            for (int i = leases.size() - 1; i >= 0; i--) {
                lockManager.release(leases.get(i));
            }
        }
    }
}
