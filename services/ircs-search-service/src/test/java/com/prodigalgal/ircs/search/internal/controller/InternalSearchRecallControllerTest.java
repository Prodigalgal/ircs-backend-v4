package com.prodigalgal.ircs.search.internal.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.search.portal.application.PortalSearchQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class InternalSearchRecallControllerTest {

    private final PortalSearchQueryService portalSearchQueryService =
            org.mockito.Mockito.mock(PortalSearchQueryService.class);
    private final InternalSearchRecallController controller =
            new InternalSearchRecallController(portalSearchQueryService);

    @Test
    void returnsUuidOnlyContextCandidatesWithNoStoreHeader() {
        UUID id = UUID.randomUUID();
        when(portalSearchQueryService.findCandidateUnifiedVideoIds("Codex Signal", "2026"))
                .thenReturn(List.of(id));

        var response = controller.unifiedContextCandidates("Codex Signal", "2026");

        assertEquals(List.of(id), response.getBody());
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(portalSearchQueryService).findCandidateUnifiedVideoIds("Codex Signal", "2026");
    }
}
