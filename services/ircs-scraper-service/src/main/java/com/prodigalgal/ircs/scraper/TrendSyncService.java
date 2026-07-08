package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.contracts.trend.TrendItemPayload;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleResponse;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyRequest;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyResponse;
import com.prodigalgal.ircs.contracts.trend.TrendSyncRunResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class TrendSyncService {

    private final List<TrendListProvider> providers;
    private final TrendSyncContentClient contentClient;
    private final TrendDiscoveryTaskClient discoveryTaskClient;

    TrendSyncRunResponse syncTrends(String correlationId) {
        List<TrendItemPayload> items = new ArrayList<>();
        List<String> providerErrors = new ArrayList<>();
        for (TrendListProvider provider : providers) {
            try {
                items.addAll(provider.fetchTrending());
            } catch (RuntimeException ex) {
                providerErrors.add(provider.name() + ": " + ex.getMessage());
            }
        }

        if (items.isEmpty()) {
            return new TrendSyncRunResponse(
                    "trend-sync",
                    providers.size(),
                    0,
                    emptyApply(),
                    providerErrors,
                    TrendDiscoveryScheduleResponse.empty("trend-discovery"));
        }

        TrendSyncApplyResponse applyResult = contentClient.apply(new TrendSyncApplyRequest(items), correlationId);
        TrendDiscoveryScheduleResponse discoveryResult = scheduleDiscovery(applyResult, correlationId, providerErrors);
        return new TrendSyncRunResponse(
                "trend-sync",
                providers.size(),
                items.size(),
                applyResult,
                providerErrors,
                discoveryResult);
    }

    private TrendSyncApplyResponse emptyApply() {
        return new TrendSyncApplyResponse("trend-sync", 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
    }

    private TrendDiscoveryScheduleResponse scheduleDiscovery(
            TrendSyncApplyResponse applyResult,
            String correlationId,
            List<String> providerErrors) {
        if (applyResult == null || applyResult.discoveryKeywords().isEmpty()) {
            return TrendDiscoveryScheduleResponse.empty("trend-discovery");
        }
        try {
            return discoveryTaskClient.schedule(applyResult.discoveryKeywords(), correlationId);
        } catch (RuntimeException ex) {
            providerErrors.add("discovery: " + ex.getMessage());
            return new TrendDiscoveryScheduleResponse(
                    "trend-discovery",
                    applyResult.discoveryKeywords().size(),
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of(ex.getMessage()));
        }
    }
}
