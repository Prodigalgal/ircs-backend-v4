package com.prodigalgal.ircs.content.video.controller;


import com.prodigalgal.ircs.content.video.application.TrendSyncContentService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.content.security.ContentInternalAccessPolicy;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyRequest;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TrendSyncContentInternalControllerTest {

    private final TrendSyncContentService service = org.mockito.Mockito.mock(TrendSyncContentService.class);
    private final ContentInternalAccessPolicy accessPolicy = new ContentInternalAccessPolicy();
    private final TrendSyncContentInternalController controller =
            new TrendSyncContentInternalController(service, accessPolicy);

    @Test
    void appliesTrendItemsThroughInternalEndpoint() {
        TrendSyncApplyRequest request = new TrendSyncApplyRequest(List.of());
        when(service.apply(request)).thenReturn(new TrendSyncApplyResponse(
                "trend-sync",
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of()));

        var response = controller.apply(request, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().taskName()).isEqualTo("trend-sync");
    }
}
