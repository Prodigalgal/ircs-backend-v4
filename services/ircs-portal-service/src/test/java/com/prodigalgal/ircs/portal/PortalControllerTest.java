package com.prodigalgal.ircs.portal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class PortalControllerTest {

    private final PortalQueryService portalQueryService = org.mockito.Mockito.mock(PortalQueryService.class);
    private final PortalController controller = new PortalController(portalQueryService);
    private final IrcsRequestPrincipal principal = IrcsRequestPrincipal.publicPrincipal();

    @Test
    void returnsMetadataWithPublicCacheHeader() {
        PortalMetadataResponse metadata = new PortalMetadataResponse(
                List.of(new CategoryItem("电影", "movies")),
                List.of("剧情"),
                List.of("中国大陆"),
                List.of("国语"),
                List.of("2026"));
        when(portalQueryService.getMetadata(principal)).thenReturn(metadata);

        var response = controller.getMetadata(new MockHttpServletRequest());

        assertEquals(metadata, response.getBody());
        assertEquals("public, max-age=0, s-maxage=3600",
                response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(portalQueryService).getMetadata(principal);
    }

    @Test
    void returnsHomeWithPublicCacheHeader() {
        PortalMovieCard card = sampleCard();
        PortalHomeResponse home = new PortalHomeResponse(
                List.of(card),
                List.of(card),
                List.of(new PortalCategorySection("movies", "电影精选", List.of(card))));
        when(portalQueryService.getHome(principal)).thenReturn(home);

        var response = controller.getHome(new MockHttpServletRequest());

        assertEquals(home, response.getBody());
        assertEquals("public, max-age=0, s-maxage=60",
                response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(portalQueryService).getHome(principal);
    }

    @Test
    void returnsExploreWithPublicCacheHeaderForFirstPageWithoutKeyword() {
        PortalMovieCard card = sampleCard();
        PageResponse<PortalMovieCard> page = PageResponse.of(List.of(card), 1, 0, 20);
        when(portalQueryService.explore(principal, 0, 20, null, "movies", null, null, null, null, "latest"))
                .thenReturn(page);

        var response = controller.explore(new MockHttpServletRequest(), 0, 20, null, "movies", null, null, null, null, "latest");

        assertPageEquals(page, response.getBody());
        assertEquals("public, max-age=0, s-maxage=60",
                response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(portalQueryService).explore(principal, 0, 20, null, "movies", null, null, null, null, "latest");
    }

    @Test
    void returnsExploreWithNoStoreForKeywordAndSafeBounds() {
        PortalMovieCard card = sampleCard();
        String longKeyword = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String safeKeyword = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWX";
        PageResponse<PortalMovieCard> page = PageResponse.of(List.of(card), 1, 2, 70);
        when(portalQueryService.explore(principal, 2, 70, safeKeyword, null, "剧情", null, null, null, null))
                .thenReturn(page);

        var response = controller.explore(new MockHttpServletRequest(), 2, 999, longKeyword, null, "剧情", null, null, null, null);

        assertPageEquals(page, response.getBody());
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(portalQueryService).explore(principal, 2, 70, safeKeyword, null, "剧情", null, null, null, null);
    }

    @Test
    void rejectsExplorePaginationDepthOverLimit() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.explore(new MockHttpServletRequest(), 200, 70, null, null, null, null, null, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void returnsSitemapMoviesWithPublicCacheHeaderAndSafeBounds() {
        PortalSitemapMovie movie = sampleSitemapMovie();
        PageResponse<PortalSitemapMovie> page = PageResponse.of(List.of(movie), 1, 2, 1000);
        when(portalQueryService.sitemapMovies(2, 1000)).thenReturn(page);

        var response = controller.sitemapMovies(2, 5000);

        assertPageEquals(page, response.getBody());
        assertEquals("public, max-age=0, s-maxage=300",
                response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(portalQueryService).sitemapMovies(2, 1000);
    }

    @Test
    void returnsMovieDetailWithPublicCacheHeader() {
        UUID movieId = UUID.randomUUID();
        PortalMovieDetailResponse detail = sampleDetail(movieId);
        when(portalQueryService.getMovieDetail(principal, movieId)).thenReturn(Optional.of(detail));

        var response = controller.getDetail(new MockHttpServletRequest(), movieId);

        assertEquals(detail, response.getBody());
        assertEquals("public, max-age=0, s-maxage=900",
                response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(portalQueryService).getMovieDetail(principal, movieId);
    }

    @Test
    void returnsNotFoundWhenMovieDetailMissing() {
        UUID movieId = UUID.randomUUID();
        when(portalQueryService.getMovieDetail(principal, movieId)).thenReturn(Optional.empty());

        var response = controller.getDetail(new MockHttpServletRequest(), movieId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(portalQueryService).getMovieDetail(principal, movieId);
    }

    private PortalMovieCard sampleCard() {
        return new PortalMovieCard(
                UUID.randomUUID(),
                "Codex Movie",
                "Codex Alias",
                1,
                "第一季",
                "https://example.invalid/covers/a.webp",
                BigDecimal.valueOf(8.6),
                "2026",
                "电影",
                "12",
                "90分钟",
                "完结",
                "中国大陆",
                "简介",
                List.of("剧情"),
                Instant.parse("2026-06-04T00:00:00Z"));
    }

    private PortalSitemapMovie sampleSitemapMovie() {
        return new PortalSitemapMovie(
                UUID.randomUUID(),
                "Codex Movie",
                "https://example.invalid/covers/a.webp",
                Instant.parse("2026-06-04T00:00:00Z"));
    }

    private PortalMovieDetailResponse sampleDetail(UUID movieId) {
        return new PortalMovieDetailResponse(
                movieId,
                "Codex Movie",
                "Codex Alias",
                null,
                "简介",
                BigDecimal.valueOf(8.6),
                "2026",
                "12",
                "完结",
                "90分钟",
                "https://example.invalid/covers/a.webp",
                "中国大陆",
                "国语",
                "电影",
                "douban-1",
                null,
                null,
                Instant.parse("2026-06-04T00:00:00Z"),
                List.of("剧情"),
                List.of(new PortalMovieDetailResponse.CastMember(UUID.randomUUID().toString(), "演员", "演员")),
                List.of(new PortalMovieDetailResponse.VideoSource(
                        UUID.randomUUID().toString(),
                        "来源",
                        List.of(new PortalMovieDetailResponse.Episode(UUID.randomUUID().toString(), "第1集", "https://example.invalid/1.m3u8")))),
                List.of(),
                List.of("高清"),
                List.of());
    }

    private static <T> void assertPageEquals(PageResponse<T> expected, PageEnvelope<T> actual) {
        assertEquals(expected.content(), actual.content());
        assertEquals(expected.number(), actual.page().number());
        assertEquals(expected.size(), actual.page().size());
        assertEquals(expected.totalElements(), actual.page().totalElements());
        assertEquals(expected.totalPages(), actual.page().totalPages());
        assertEquals(expected.first(), actual.page().first());
        assertEquals(expected.last(), actual.page().last());
        assertEquals(expected.content().size(), actual.page().numberOfElements());
        assertEquals(expected.empty(), actual.page().empty());
    }
}
