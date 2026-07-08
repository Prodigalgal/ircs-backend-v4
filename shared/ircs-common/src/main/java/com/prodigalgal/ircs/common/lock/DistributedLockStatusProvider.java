package com.prodigalgal.ircs.common.lock;

public interface DistributedLockStatusProvider {

    DistributedLockBackendStatus backendStatus();
}
