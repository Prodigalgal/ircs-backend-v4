package com.prodigalgal.ircs.common.lock;

public enum DistributedLockType {
    MUTEX_LEASE,
    TIME_SLICE_RESERVATION,
    TOKEN_BUCKET
}
