package com.prodigalgal.ircs.contracts.search;

import java.util.List;
import java.util.UUID;

public record AdminVideoSearchResult(
        List<UUID> ids,
        long total,
        TotalRelation totalRelation,
        long tookMillis,
        String source) {

    public AdminVideoSearchResult {
        ids = ids == null ? List.of() : List.copyOf(ids);
        totalRelation = totalRelation == null ? TotalRelation.UNKNOWN : totalRelation;
        source = source == null || source.isBlank() ? "elasticsearch" : source;
    }

    public enum TotalRelation {
        EXACT,
        GREATER_THAN_OR_EQUAL,
        UNKNOWN
    }
}
