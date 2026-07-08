package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.contracts.trend.TrendItemPayload;
import java.util.List;

interface TrendListProvider {

    String name();

    List<TrendItemPayload> fetchTrending();
}
