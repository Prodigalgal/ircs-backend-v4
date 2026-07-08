package com.prodigalgal.ircs.ops.audit.request;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/request-audit")
public class RequestAuditController {

    private final RequestAuditQueryService requestAuditQueryService;

    @GetMapping
    public ResponseEntity<PageEnvelope<RequestAuditLogResponse>> getAll(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "requestSource", required = false) String requestSource,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "statusCode", required = false) Integer statusCode,
            @RequestParam(name = "statusClass", required = false) String statusClass,
            @RequestParam(name = "clientIp", required = false) String clientIp,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(PageEnvelope.from(requestAuditQueryService.findAll(
                pageable,
                username,
                requestSource,
                method,
                path,
                statusCode,
                statusClass,
                clientIp,
                from,
                to)));
    }

    @GetMapping("/summary")
    public ResponseEntity<RequestAuditSummaryResponse> summarize() {
        return ResponseEntity.ok(requestAuditQueryService.summarize());
    }
}
