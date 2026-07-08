package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class SearchDistributedLockRunner {

    private final DistributedLockManager lockManager;
    private final String workerId;
    private final boolean enabled;
    private final Duration ttl;

    SearchDistributedLockRunner(
            DistributedLockManager lockManager,
            @Value("${spring.application.name:ircs-search-service}") String applicationName,
            @Value("${app.search.cluster-lock.worker-id:${APP_SEARCH_CLUSTER_LOCK_WORKER_ID:}}") String configuredWorkerId,
            @Value("${app.search.cluster-lock.enabled:true}") boolean enabled,
            @Value("${app.search.cluster-lock.ttl:PT10M}") Duration ttl) {
        this.lockManager = lockManager;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.enabled = enabled;
        this.ttl = ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl;
    }

    static SearchDistributedLockRunner local() {
        return new SearchDistributedLockRunner(
                null,
                "ircs-search-service",
                "local-test",
                false,
                Duration.ofMinutes(10));
    }

    <T> Optional<T> callExclusive(String lockName, Supplier<T> action) {
        if (!enabled) {
            return Optional.ofNullable(action.get());
        }
        if (lockManager == null) {
            throw new IllegalStateException("search distributed lock manager is unavailable");
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.MAINTENANCE_RUNNER);
        return lockManager.callWithLock(profile.keyPrefix() + lockName, workerId, ttl, action);
    }

    String workerId() {
        return workerId;
    }
}
