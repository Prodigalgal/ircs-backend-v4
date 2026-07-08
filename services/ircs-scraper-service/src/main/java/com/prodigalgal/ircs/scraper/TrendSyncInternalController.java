package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.trend.TrendSyncRunResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/scraper/trends")
class TrendSyncInternalController {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final TrendSyncService trendSyncService;
    private final ScraperInternalAccessPolicy accessPolicy;

    @PostMapping("/sync")
    ResponseEntity<TrendSyncRunResponse> sync(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.accepted().body(trendSyncService.syncTrends(correlationId));
    }
}
