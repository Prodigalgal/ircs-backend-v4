package com.prodigalgal.ircs.common.lock;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

class DistributedLockHealthIndicator implements HealthIndicator {

    private final DistributedLockManager lockManager;

    DistributedLockHealthIndicator(DistributedLockManager lockManager) {
        this.lockManager = lockManager;
    }

    @Override
    public Health health() {
        if (lockManager instanceof DistributedLockStatusProvider statusProvider) {
            DistributedLockBackendStatus status = statusProvider.backendStatus();
            Health.Builder builder = status.available() ? Health.up() : Health.down();
            return builder
                    .withDetail("backend", status.backend())
                    .withDetail("reason", status.reason())
                    .build();
        }
        return Health.unknown()
                .withDetail("backend", lockManager == null ? "missing" : lockManager.getClass().getName())
                .withDetail("reason", "distributed lock manager does not expose backend status")
                .build();
    }
}
