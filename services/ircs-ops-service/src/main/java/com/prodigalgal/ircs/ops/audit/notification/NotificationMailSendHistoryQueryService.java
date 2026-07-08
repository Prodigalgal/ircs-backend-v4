package com.prodigalgal.ircs.ops.audit.notification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class NotificationMailSendHistoryQueryService {

    private final NotificationMailSendHistoryRepository repository;

    public Page<NotificationMailSendHistoryResponse> findAll(
            Pageable pageable,
            String status,
            String deliveryMode,
            String templateCode,
            String type,
            String correlationId,
            String recipient,
            Instant from,
            Instant to) {
        String effectiveTemplateCode = StringUtils.hasText(templateCode) ? templateCode : type;
        return repository.findAll(
                pageable,
                status,
                deliveryMode,
                effectiveTemplateCode,
                correlationId,
                recipient,
                from,
                to);
    }

    public NotificationMailSendHistorySummaryResponse summarize() {
        return repository.summarize(Instant.now().minus(24, ChronoUnit.HOURS));
    }
}
