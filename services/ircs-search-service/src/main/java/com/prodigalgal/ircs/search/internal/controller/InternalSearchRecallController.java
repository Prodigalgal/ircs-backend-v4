package com.prodigalgal.ircs.search.internal.controller;

import com.prodigalgal.ircs.search.portal.application.PortalSearchQueryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/search")
@RequiredArgsConstructor
class InternalSearchRecallController {

    private final PortalSearchQueryService portalSearchQueryService;

    @GetMapping("/unified-context-candidates")
    ResponseEntity<List<UUID>> unifiedContextCandidates(
            @RequestParam(name = "title") String title,
            @RequestParam(name = "year", required = false) String year) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(portalSearchQueryService.findCandidateUnifiedVideoIds(title, year));
    }
}
