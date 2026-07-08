package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SearchIndexMaintenanceResponse;
import com.prodigalgal.ircs.search.index.SearchIndexService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/search/index-maintenance")
class SearchIndexMaintenanceInternalController {

    private final SearchIndexService indexService;
    private final SearchInternalAccessPolicy accessPolicy;

    SearchIndexMaintenanceInternalController(
            SearchIndexService indexService,
            SearchInternalAccessPolicy accessPolicy) {
        this.indexService = indexService;
        this.accessPolicy = accessPolicy;
    }

    @PostMapping("/{entityType}/hard-reset")
    ResponseEntity<SearchIndexMaintenanceResponse> hardReset(
            @PathVariable SearchEntityType entityType,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        boolean recreated = indexService.hardReset(entityType);
        return ResponseEntity.accepted().body(new SearchIndexMaintenanceResponse(
                entityType,
                "hard-reset",
                0,
                recreated));
    }
}
