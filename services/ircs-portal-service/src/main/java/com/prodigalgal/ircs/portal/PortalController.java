package com.prodigalgal.ircs.portal;

import com.prodigalgal.ircs.common.security.IrcsAuthHeaders;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.common.web.PageEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/portal")
public class PortalController {

    private static final String CACHE_PUBLIC_HOME = "public, max-age=0, s-maxage=60";
    private static final String CACHE_PUBLIC_METADATA = "public, max-age=0, s-maxage=3600";
    private static final String CACHE_PUBLIC_DETAIL = "public, max-age=0, s-maxage=900";
    private static final String CACHE_PUBLIC_EXPLORE = "public, max-age=0, s-maxage=60";
    private static final String CACHE_PUBLIC_SITEMAP = "public, max-age=0, s-maxage=300";
    private static final String CACHE_NO_STORE = "no-store";
    private static final int MAX_EXPLORE_SIZE = 70;
    private static final int MAX_SITEMAP_SIZE = 1000;

    private final PortalQueryService portalQueryService;

    @GetMapping("/metadata")
    public ResponseEntity<PortalMetadataResponse> getMetadata(HttpServletRequest request) {
        IrcsRequestPrincipal principal = IrcsAuthHeaders.principalOrPublic(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, cacheFor(principal, CACHE_PUBLIC_METADATA))
                .body(portalQueryService.getMetadata(principal));
    }

    @GetMapping("/home")
    public ResponseEntity<PortalHomeResponse> getHome(HttpServletRequest request) {
        IrcsRequestPrincipal principal = IrcsAuthHeaders.principalOrPublic(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, cacheFor(principal, CACHE_PUBLIC_HOME))
                .body(portalQueryService.getHome(principal));
    }

    @GetMapping("/explore")
    public ResponseEntity<PageEnvelope<PortalMovieCard>> explore(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "genre", required = false) String genre,
            @RequestParam(name = "area", required = false) String area,
            @RequestParam(name = "year", required = false) String year,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "sort", required = false) String sort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size <= 0 ? 20 : size, 1), MAX_EXPLORE_SIZE);
        if ((long) safePage * safeSize > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pagination depth exceeded limit (10000)");
        }
        String safeKeyword = normalizeKeyword(keyword);
        IrcsRequestPrincipal principal = IrcsAuthHeaders.principalOrPublic(request);
        boolean cacheable = principal.isAnonymous() && safeKeyword == null && safePage == 0;
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, cacheable ? CACHE_PUBLIC_EXPLORE : CACHE_NO_STORE)
                .body(pageEnvelope(portalQueryService.explore(
                        principal,
                        safePage,
                        safeSize,
                        safeKeyword,
                        type,
                        genre,
                        area,
                        year,
                        language,
                        sort)));
    }

    @GetMapping("/sitemap/movies")
    public ResponseEntity<PageEnvelope<PortalSitemapMovie>> sitemapMovies(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "1000") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size <= 0 ? MAX_SITEMAP_SIZE : size, 1), MAX_SITEMAP_SIZE);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_PUBLIC_SITEMAP)
                .body(pageEnvelope(portalQueryService.sitemapMovies(safePage, safeSize)));
    }

    @GetMapping("/movies/{id}")
    public ResponseEntity<PortalMovieDetailResponse> getDetail(HttpServletRequest request, @PathVariable(name = "id") UUID id) {
        IrcsRequestPrincipal principal = IrcsAuthHeaders.principalOrPublic(request);
        Optional<PortalMovieDetailResponse> detail = portalQueryService.getMovieDetail(principal, id);
        return detail
                .map(value -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, cacheFor(principal, CACHE_PUBLIC_DETAIL))
                        .body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String normalized = keyword.length() > 50 ? keyword.substring(0, 50) : keyword;
        normalized = normalized.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String cacheFor(IrcsRequestPrincipal principal, String publicCache) {
        return principal.isAnonymous() ? publicCache : CACHE_NO_STORE;
    }

    private <T> PageEnvelope<T> pageEnvelope(PageResponse<T> page) {
        return PageEnvelope.of(page.content(), page.number(), page.size(), page.totalElements());
    }
}
