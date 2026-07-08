package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.common.search.SearchSyncWorkPublisher;
import com.prodigalgal.ircs.contracts.search.SearchSyncTaskBatchEnqueueRequest;
import com.prodigalgal.ircs.contracts.search.SearchSyncTaskEnqueueRequest;
import com.prodigalgal.ircs.contracts.search.SearchSyncTaskEnqueueResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/search/sync-tasks")
class SearchSyncTaskInternalController {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final SearchSyncWorkPublisher publisher;
    private final SearchInternalAccessPolicy accessPolicy;
    private final int maxBatchSize;

    SearchSyncTaskInternalController(
            SearchSyncWorkPublisher publisher,
            SearchInternalAccessPolicy accessPolicy,
            @Value("${app.search.internal-access.max-batch-size:1000}") int maxBatchSize) {
        this.publisher = publisher;
        this.accessPolicy = accessPolicy;
        this.maxBatchSize = Math.max(1, maxBatchSize);
    }

    @PostMapping
    ResponseEntity<SearchSyncTaskEnqueueResponse> enqueue(
            @RequestBody SearchSyncTaskEnqueueRequest request,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationHeader) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        validate(request);
        int accepted = publisher.enqueue(
                request.entityId(),
                request.entityType(),
                request.operation(),
                source(request.sourceService(), serviceId),
                correlation(request.correlationId(), correlationHeader));
        return ResponseEntity.accepted().body(new SearchSyncTaskEnqueueResponse(accepted));
    }

    @PostMapping("/batch")
    ResponseEntity<SearchSyncTaskEnqueueResponse> enqueueBatch(
            @RequestBody SearchSyncTaskBatchEnqueueRequest request,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationHeader) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        validate(request);
        int accepted = publisher.enqueueBatch(
                request.entityIds(),
                request.entityType(),
                request.operation(),
                source(request.sourceService(), serviceId),
                correlation(request.correlationId(), correlationHeader));
        return ResponseEntity.accepted().body(new SearchSyncTaskEnqueueResponse(accepted));
    }

    private void validate(SearchSyncTaskEnqueueRequest request) {
        if (request == null
                || request.entityId() == null
                || request.entityType() == null
                || request.operation() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search sync task request");
        }
    }

    private void validate(SearchSyncTaskBatchEnqueueRequest request) {
        if (request == null
                || CollectionUtils.isEmpty(request.entityIds())
                || request.entityType() == null
                || request.operation() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search sync task batch request");
        }
        List<UUID> ids = request.entityIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search sync task batch contains no entity ids");
        }
        if (ids.size() > maxBatchSize) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Search sync task batch is too large");
        }
    }

    private String source(String bodyValue, String headerValue) {
        return StringUtils.hasText(bodyValue) ? bodyValue : headerValue;
    }

    private String correlation(String bodyValue, String headerValue) {
        return StringUtils.hasText(bodyValue) ? bodyValue : headerValue;
    }
}
