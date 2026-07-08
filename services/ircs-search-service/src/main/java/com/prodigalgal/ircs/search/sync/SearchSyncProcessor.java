package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.contracts.search.SearchSyncMessage;

public interface SearchSyncProcessor {

    void process(SearchSyncMessage message);
}
