package com.prodigalgal.ircs.scraper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleResponse;
import com.prodigalgal.ircs.contracts.trend.TrendItemPayload;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyRequest;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TrendSyncServiceTest {

    private final TrendSyncContentClient contentClient = org.mockito.Mockito.mock(TrendSyncContentClient.class);
    private final TrendDiscoveryTaskClient discoveryTaskClient = org.mockito.Mockito.mock(TrendDiscoveryTaskClient.class);

    @Test
    void fetchesProvidersAppliesItemsAndSchedulesBackgroundDiscovery() {
        UUID createdId = UUID.randomUUID();
        TrendItemPayload item = new TrendItemPayload(
                "Codex Trend",
                null,
                null,
                "2026",
                null,
                null,
                null,
                null,
                "d-1",
                null,
                "movie");
        TrendListProvider provider = provider("fake", List.of(item));
        when(contentClient.apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("corr-trend")))
                .thenReturn(new TrendSyncApplyResponse(
                        "trend-sync",
                        1,
                        0,
                        0,
                        1,
                        0,
                        List.of(),
                        List.of(createdId),
                        List.of("Codex Trend")));
        when(discoveryTaskClient.schedule(List.of("Codex Trend"), "corr-trend"))
                .thenReturn(new TrendDiscoveryScheduleResponse(
                        "trend-discovery",
                        1,
                        2,
                        2,
                        0,
                        2,
                        List.of(),
                        List.of()));
        TrendSyncService service = new TrendSyncService(List.of(provider), contentClient, discoveryTaskClient);

        var response = service.syncTrends("corr-trend");

        assertThat(response.taskName()).isEqualTo("trend-sync");
        assertThat(response.providerCount()).isEqualTo(1);
        assertThat(response.fetchedItems()).isEqualTo(1);
        assertThat(response.applyResult().createdUnifiedVideoIds()).containsExactly(createdId);
        ArgumentCaptor<TrendSyncApplyRequest> request = ArgumentCaptor.forClass(TrendSyncApplyRequest.class);
        verify(contentClient).apply(request.capture(), org.mockito.ArgumentMatchers.eq("corr-trend"));
        assertThat(request.getValue().items()).containsExactly(item);
        assertThat(response.discoveryResult().queuedTasks()).isEqualTo(2);
        verify(discoveryTaskClient).schedule(List.of("Codex Trend"), "corr-trend");
    }

    @Test
    void providerFailureIsRecordedAndEmptyItemsDoNotCallContentOwner() {
        TrendListProvider failing = new TrendListProvider() {
            @Override
            public String name() {
                return "bad-provider";
            }

            @Override
            public List<TrendItemPayload> fetchTrending() {
                throw new IllegalStateException("boom");
            }
        };
        TrendSyncService service = new TrendSyncService(List.of(failing), contentClient, discoveryTaskClient);

        var response = service.syncTrends("corr-empty");

        assertThat(response.fetchedItems()).isZero();
        assertThat(response.applyResult().candidates()).isZero();
        assertThat(response.providerErrors()).containsExactly("bad-provider: boom");
        verify(contentClient, never()).apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(discoveryTaskClient, never()).schedule(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private TrendListProvider provider(String name, List<TrendItemPayload> items) {
        return new TrendListProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<TrendItemPayload> fetchTrending() {
                return items;
            }
        };
    }
}
