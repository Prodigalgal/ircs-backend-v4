package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.contracts.search.SearchSyncMessage;

public class UnsupportedSearchSyncProcessor implements SearchSyncProcessor {

    @Override
    public void process(SearchSyncMessage message) {
        throw new IllegalStateException("No active SearchSyncProcessor is configured");
    }
}
