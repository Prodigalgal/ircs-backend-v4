package com.prodigalgal.ircs.ops.audit.notification;

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
@RequestMapping("/api/v1/ops/notification-mail-send-history")
public class NotificationMailSendHistoryController {

    private final NotificationMailSendHistoryQueryService queryService;

    @GetMapping
    public ResponseEntity<PageEnvelope<NotificationMailSendHistoryResponse>> getAll(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deliveryMode,
            @RequestParam(required = false) String templateCode,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(PageEnvelope.from(queryService.findAll(
                pageable,
                status,
                deliveryMode,
                templateCode,
                type,
                correlationId,
                recipient,
                from,
                to)));
    }

    @GetMapping("/summary")
    public ResponseEntity<NotificationMailSendHistorySummaryResponse> summarize() {
        return ResponseEntity.ok(queryService.summarize());
    }
}
