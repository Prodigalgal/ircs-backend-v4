package com.prodigalgal.ircs.contracts.trend;

import java.util.List;

public record TrendSyncApplyRequest(List<TrendItemPayload> items) {

    public TrendSyncApplyRequest {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
