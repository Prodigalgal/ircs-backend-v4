package com.prodigalgal.ircs.ops.audit.notification;

public record NotificationMailSendHistorySummaryResponse(
        long totalLast24h,
        long skippedLast24h,
        long sentLast24h,
        long failedLast24h,
        String skippedSemantics,
        String sentSemantics,
        String failedSemantics) {
}
