package com.prodigalgal.ircs.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ManualAggregationControllerTest {

    private final AggregationService aggregationService = org.mockito.Mockito.mock(AggregationService.class);
    private final ManualAggregationController controller = new ManualAggregationController(aggregationService);

    @Test
    void returnsAcceptedManualMergeResponse() {
        UUID rootId = UUID.randomUUID();
        UUID victimId = UUID.randomUUID();
        ManualUnifiedMergeResponse response = new ManualUnifiedMergeResponse(
                rootId,
                List.of(victimId),
                List.of(UUID.randomUUID()),
                "MERGED",
                null);
        when(aggregationService.mergeUnifiedVideos(List.of(rootId, victimId))).thenReturn(response);

        var result = controller.mergeUnifiedVideos(new ManualAggregationController.ManualUnifiedMergeRequest(
                List.of(rootId, victimId)));

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void mapsIllegalArgumentToBadRequest() {
        IllegalArgumentException cause = new IllegalArgumentException("ids must contain at least two distinct IDs");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.handleBadRequest(cause));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
