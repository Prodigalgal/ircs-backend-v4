package com.prodigalgal.ircs.common.lock;

public interface DistributedLockManager extends DistributedLockService {

    DistributedLockProfile profileFor(DistributedLockBusinessType businessType);

    TimeSliceReservation reserveTimeSlice(TimeSliceReservationRequest request);

    TokenBucketReservation reserveTokenBucket(TokenBucketReservationRequest request);
}
