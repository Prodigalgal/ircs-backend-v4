package com.prodigalgal.ircs.scraper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TrendDiscoveryTaskClientTest {

    private final OutboundHttpClient httpClient = org.mockito.Mockito.mock(OutboundHttpClient.class);
    private final ScraperTrendConfigValues configValues = org.mockito.Mockito.mock(ScraperTrendConfigValues.class);
    private final TrendDiscoveryTaskClient client =
            TrendDiscoveryTaskClient.forTest(new ObjectMapper(), httpClient, configValues);

    @Test
    void postsDiscoveryKeywordsToTaskOwner() throws Exception {
        when(configValues.trendDiscoveryEnabled()).thenReturn(true);
        when(configValues.taskOwnerBaseUrl()).thenReturn("http://task-service");
        when(configValues.taskOwnerRequestTimeout()).thenReturn(Duration.ofSeconds(30));
        when(configValues.taskOwnerServiceId()).thenReturn("scraper-service");
        when(configValues.taskOwnerServiceToken()).thenReturn("task-token");
        when(configValues.taskOwnerScopes()).thenReturn("task:maintenance");
        when(configValues.trendDiscoveryMaxDataSources()).thenReturn(1);
        when(httpClient.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OutboundHttpResponse(
                        202,
                        Map.of(),
                        new ObjectMapper().writeValueAsString(new TrendDiscoveryScheduleResponse(
                                "trend-discovery",
                                1,
                                2,
                                2,
                                0,
                                2,
                                List.of(),
                                List.of())).getBytes(StandardCharsets.UTF_8)));

        TrendDiscoveryScheduleResponse response = client.schedule(List.of("Codex Trend"), "corr-trend");

        assertThat(response.queuedTasks()).isEqualTo(2);
        ArgumentCaptor<com.prodigalgal.ircs.common.outbound.OutboundHttpRequest> captor =
                ArgumentCaptor.forClass(com.prodigalgal.ircs.common.outbound.OutboundHttpRequest.class);
        org.mockito.Mockito.verify(httpClient).execute(captor.capture());
        String requestJson = new String(captor.getValue().body(), StandardCharsets.UTF_8);
        assertThat(requestJson).contains("\"maxDataSources\":1");
    }
}
