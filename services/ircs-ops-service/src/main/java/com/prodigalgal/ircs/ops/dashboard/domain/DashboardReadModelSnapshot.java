package com.prodigalgal.ircs.ops.dashboard.domain;

import java.time.Instant;

public record DashboardReadModelSnapshot<T>(
        T payload,
        Instant generatedAt,
        Instant expiresAt,
        Instant staleUntil,
        String source
) {
    public boolean freshAt(Instant now) {
        return now != null && now.isBefore(expiresAt);
    }

    public boolean usableAt(Instant now) {
        return now != null && now.isBefore(staleUntil);
    }
}
