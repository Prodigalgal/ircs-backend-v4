package com.prodigalgal.ircs.task.infrastructure;

import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TaskDistributedLockRunner {

    private final DistributedLockManager lockManager;
    private final String workerId;
    private final boolean enabled;
    private final Duration ttl;

    public TaskDistributedLockRunner(
            DistributedLockManager lockManager,
            @Value("${spring.application.name:ircs-task-service}") String applicationName,
            @Value("${app.task.cluster-lock.worker-id:${APP_TASK_CLUSTER_LOCK_WORKER_ID:}}") String configuredWorkerId,
            @Value("${app.task.cluster-lock.enabled:true}") boolean enabled,
            @Value("${app.task.cluster-lock.ttl:PT10M}") Duration ttl) {
        this.lockManager = lockManager;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.enabled = enabled;
        this.ttl = ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl;
    }

    public static TaskDistributedLockRunner local() {
        return new TaskDistributedLockRunner(
                null,
                "ircs-task-service",
                "local-test",
                false,
                Duration.ofMinutes(10));
    }

    public boolean runExclusive(String lockName, Runnable action) {
        if (!enabled) {
            action.run();
            return true;
        }
        if (lockManager == null) {
            throw new IllegalStateException("task distributed lock manager is unavailable");
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.MAINTENANCE_RUNNER);
        return lockManager.runWithLock(profile.keyPrefix() + lockName, workerId, ttl, action);
    }

    public String workerId() {
        return workerId;
    }
}
