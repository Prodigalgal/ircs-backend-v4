package com.prodigalgal.ircs.common.lease;

import java.time.Duration;
import java.util.Optional;

public interface ClusterLeaseService {

    Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl);

    boolean renew(ClusterLease lease, Duration ttl);

    boolean release(ClusterLease lease);
}
