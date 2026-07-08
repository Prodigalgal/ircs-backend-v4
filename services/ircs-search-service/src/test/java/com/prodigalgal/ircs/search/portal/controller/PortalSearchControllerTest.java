package com.prodigalgal.ircs.search.portal.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.search.portal.application.PortalSearchQueryService;
import com.prodigalgal.ircs.search.portal.dto.PortalMovieCardResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

class PortalSearchControllerTest {

    private final PortalSearchQueryService portalSearchQueryService =
            org.mockito.Mockito.mock(PortalSearchQueryService.class);
    private final PortalSearchController controller = new PortalSearchController(portalSearchQueryService);
    private final IrcsRequestPrincipal principal = IrcsRequestPrincipal.publicPrincipal();

    @Test
    void returnsSuggestionsWithNoStoreHeader() {
        when(portalSearchQueryService.suggest("codex", principal)).thenReturn(List.of("Codex Movie"));

        var response = controller.suggest(new MockHttpServletRequest(), "codex");

        assertEquals(List.of("Codex Movie"), response.getBody());
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(portalSearchQueryService).suggest("codex", principal);
    }

    @Test
    void returnsRecommendationsPage() {
        UUID videoId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        PortalMovieCardResponse card = new PortalMovieCardResponse(
                videoId,
                "Codex Movie",
                "Codex Alias",
                null,
                null,
                null,
                null,
                "2026",
                "电影",
                null,
                null,
                null,
                "",
                null,
                List.of("剧情"),
                null);
        Page<PortalMovieCardResponse> page = new org.springframework.data.domain.PageImpl<>(List.of(card), pageable, 1);
        when(portalSearchQueryService.recommend(videoId, pageable, principal)).thenReturn(page);

        var response = controller.recommend(new MockHttpServletRequest(), videoId, pageable);

        assertEquals(page.getContent(), response.getBody().content());
        assertEquals(1, response.getBody().page().totalElements());
        assertEquals(10, response.getBody().page().size());
        assertEquals(0, response.getBody().page().number());
        verify(portalSearchQueryService).recommend(videoId, pageable, principal);
    }
}
