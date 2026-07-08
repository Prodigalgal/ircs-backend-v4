package com.prodigalgal.ircs.common.lock;

import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lease.ClusterLeaseService;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

public interface DistributedLockService extends ClusterLeaseService {

    default Optional<ClusterLease> tryLock(String name, String ownerId, Duration ttl) {
        return tryAcquire(name, ownerId, ttl);
    }

    default boolean renewLock(ClusterLease lock, Duration ttl) {
        return renew(lock, ttl);
    }

    default boolean unlock(ClusterLease lock) {
        return release(lock);
    }

    default boolean runWithLock(String name, String ownerId, Duration ttl, Runnable action) {
        Optional<ClusterLease> lock = tryLock(name, ownerId, ttl);
        if (lock.isEmpty()) {
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            unlock(lock.orElseThrow());
        }
    }

    default <T> Optional<T> callWithLock(String name, String ownerId, Duration ttl, Supplier<T> action) {
        Optional<ClusterLease> lock = tryLock(name, ownerId, ttl);
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(action.get());
        } finally {
            unlock(lock.orElseThrow());
        }
    }
}
