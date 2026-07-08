package com.prodigalgal.ircs.scraper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyResponse;
import com.prodigalgal.ircs.contracts.trend.TrendSyncRunResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TrendSyncInternalControllerTest {

    private final TrendSyncService service = org.mockito.Mockito.mock(TrendSyncService.class);
    private final ScraperInternalAccessPolicy accessPolicy = new ScraperInternalAccessPolicy();
    private final TrendSyncInternalController controller =
            new TrendSyncInternalController(service, accessPolicy);

    @Test
    void triggersTrendSyncThroughInternalEndpoint() {
        when(service.syncTrends("corr-trend")).thenReturn(new TrendSyncRunResponse(
                "trend-sync",
                2,
                0,
                new TrendSyncApplyResponse("trend-sync", 0, 0, 0, 0, 0, List.of(), List.of(), List.of()),
                List.of()));

        var response = controller.sync("corr-trend", null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().taskName()).isEqualTo("trend-sync");
        verify(service).syncTrends("corr-trend");
    }
}
