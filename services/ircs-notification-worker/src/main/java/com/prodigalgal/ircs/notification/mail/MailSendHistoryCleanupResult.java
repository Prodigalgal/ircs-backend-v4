package com.prodigalgal.ircs.notification.mail;

import java.time.Instant;

record MailSendHistoryCleanupResult(
        boolean success,
        Instant cutoff,
        int deletedRows,
        int batches,
        boolean dryRun,
        int candidateRows,
        String reason) {
}
