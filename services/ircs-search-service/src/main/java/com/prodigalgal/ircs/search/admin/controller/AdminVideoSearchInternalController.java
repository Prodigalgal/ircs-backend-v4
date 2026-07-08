package com.prodigalgal.ircs.search.admin.controller;

import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchRequest;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchResult;
import com.prodigalgal.ircs.search.admin.application.AdminVideoSearchService;
import com.prodigalgal.ircs.search.sync.SearchInternalAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/search/admin/videos")
@RequiredArgsConstructor
class AdminVideoSearchInternalController {

    private final AdminVideoSearchService searchService;
    private final SearchInternalAccessPolicy accessPolicy;

    @PostMapping("/raw/ids")
    ResponseEntity<AdminVideoSearchResult> rawIds(
            @RequestBody AdminVideoSearchRequest request,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(searchService.searchRawIds(request));
    }

    @PostMapping("/unified/ids")
    ResponseEntity<AdminVideoSearchResult> unifiedIds(
            @RequestBody AdminVideoSearchRequest request,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(searchService.searchUnifiedIds(request));
    }
}
