package com.prodigalgal.ircs.common.lease;

import com.prodigalgal.ircs.common.lock.DistributedLockBackendStatus;
import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.lock.DistributedLockProfiles;
import com.prodigalgal.ircs.common.lock.DistributedLockService;
import com.prodigalgal.ircs.common.lock.DistributedLockStatusProvider;
import com.prodigalgal.ircs.common.lock.TimeSliceReservation;
import com.prodigalgal.ircs.common.lock.TimeSliceReservationRequest;
import com.prodigalgal.ircs.common.lock.TokenBucketReservation;
import com.prodigalgal.ircs.common.lock.TokenBucketReservationRequest;
import java.time.Duration;
import java.util.Optional;

public final class UnavailableClusterLeaseService
        implements DistributedLockService, DistributedLockManager, DistributedLockStatusProvider {

    private final String reason;

    public UnavailableClusterLeaseService(String reason) {
        this.reason = reason == null || reason.isBlank() ? "cluster lease backend is unavailable" : reason;
    }

    @Override
    public Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl) {
        throw new IllegalStateException(reason);
    }

    @Override
    public boolean renew(ClusterLease lease, Duration ttl) {
        throw new IllegalStateException(reason);
    }

    @Override
    public boolean release(ClusterLease lease) {
        throw new IllegalStateException(reason);
    }

    @Override
    public DistributedLockProfile profileFor(DistributedLockBusinessType businessType) {
        return DistributedLockProfiles.profileFor(businessType);
    }

    @Override
    public TimeSliceReservation reserveTimeSlice(TimeSliceReservationRequest request) {
        throw new IllegalStateException(reason);
    }

    @Override
    public TokenBucketReservation reserveTokenBucket(TokenBucketReservationRequest request) {
        throw new IllegalStateException(reason);
    }

    @Override
    public DistributedLockBackendStatus backendStatus() {
        return DistributedLockBackendStatus.unavailable("unavailable", reason);
    }
}
