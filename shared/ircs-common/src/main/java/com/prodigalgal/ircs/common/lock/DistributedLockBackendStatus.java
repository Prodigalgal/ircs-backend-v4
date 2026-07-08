package com.prodigalgal.ircs.common.lock;

public record DistributedLockBackendStatus(
        boolean available,
        String backend,
        String reason) {

    public static DistributedLockBackendStatus available(String backend) {
        return new DistributedLockBackendStatus(true, backend, "OK");
    }

    public static DistributedLockBackendStatus unavailable(String backend, String reason) {
        return new DistributedLockBackendStatus(false, backend, normalizeReason(reason));
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "distributed lock backend is unavailable" : reason;
    }
}
