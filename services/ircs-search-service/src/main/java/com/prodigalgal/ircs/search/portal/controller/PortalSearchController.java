package com.prodigalgal.ircs.search.portal.controller;

import com.prodigalgal.ircs.common.security.IrcsAuthHeaders;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.search.portal.application.PortalSearchQueryService;
import com.prodigalgal.ircs.search.portal.dto.PortalMovieCardResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/portal/search")
public class PortalSearchController {

    private final PortalSearchQueryService portalSearchQueryService;

    @GetMapping("/suggest")
    public ResponseEntity<List<String>> suggest(HttpServletRequest request, @RequestParam String keyword) {
        IrcsRequestPrincipal principal = IrcsAuthHeaders.principalOrPublic(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(portalSearchQueryService.suggest(keyword, principal));
    }

    @GetMapping("/recommendations/{videoId}")
    public ResponseEntity<PageEnvelope<PortalMovieCardResponse>> recommend(
            HttpServletRequest request,
            @PathVariable UUID videoId,
            @PageableDefault(size = 10) Pageable pageable) {
        IrcsRequestPrincipal principal = IrcsAuthHeaders.principalOrPublic(request);
        return ResponseEntity.ok(PageEnvelope.from(portalSearchQueryService.recommend(videoId, pageable, principal)));
    }
}
