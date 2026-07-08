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
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "deliveryMode", required = false) String deliveryMode,
            @RequestParam(name = "templateCode", required = false) String templateCode,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "correlationId", required = false) String correlationId,
            @RequestParam(name = "recipient", required = false) String recipient,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
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
