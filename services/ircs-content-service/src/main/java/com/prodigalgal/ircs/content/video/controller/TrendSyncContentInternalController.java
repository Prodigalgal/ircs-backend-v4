package com.prodigalgal.ircs.content.video.controller;


import com.prodigalgal.ircs.content.video.application.TrendSyncContentService;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.content.security.ContentInternalAccessPolicy;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyRequest;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/content/trends")
class TrendSyncContentInternalController {

    private final TrendSyncContentService trendSyncContentService;
    private final ContentInternalAccessPolicy accessPolicy;

    @PostMapping("/apply")
    ResponseEntity<TrendSyncApplyResponse> apply(
            @RequestBody(required = false) TrendSyncApplyRequest request,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.accepted().body(trendSyncContentService.apply(request));
    }
}
