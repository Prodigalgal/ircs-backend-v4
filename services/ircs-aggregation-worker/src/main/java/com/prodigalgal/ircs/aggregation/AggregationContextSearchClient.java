package com.prodigalgal.ircs.aggregation;

import java.util.List;
import java.util.UUID;

interface AggregationContextSearchClient {

    ContextSearchResult findCandidateUnifiedVideoIds(String title, String year);

    record ContextSearchResult(boolean attempted, List<UUID> unifiedVideoIds) {

        public ContextSearchResult {
            unifiedVideoIds = unifiedVideoIds == null ? List.of() : List.copyOf(unifiedVideoIds);
        }

        static ContextSearchResult attempted(List<UUID> unifiedVideoIds) {
            return new ContextSearchResult(true, unifiedVideoIds);
        }

        static ContextSearchResult notAttempted() {
            return new ContextSearchResult(false, List.of());
        }
    }
}
