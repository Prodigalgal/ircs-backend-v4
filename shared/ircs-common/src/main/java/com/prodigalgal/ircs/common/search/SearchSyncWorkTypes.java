package com.prodigalgal.ircs.common.search;

import com.prodigalgal.ircs.contracts.search.SearchEntityType;

public final class SearchSyncWorkTypes {

    public static final String RAW = "search.sync.raw";
    public static final String UNIFIED = "search.sync.unified";

    private SearchSyncWorkTypes() {
    }

    public static String taskType(SearchEntityType entityType) {
        return entityType == SearchEntityType.RAW_VIDEO ? RAW : UNIFIED;
    }
}
