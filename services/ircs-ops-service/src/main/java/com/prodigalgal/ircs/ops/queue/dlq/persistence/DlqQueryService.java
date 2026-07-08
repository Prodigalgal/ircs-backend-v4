package com.prodigalgal.ircs.ops.queue.dlq.persistence;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DlqQueryService {

    private final DlqRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public Page<FailedMessageResponse> findAll(Pageable pageable, String status, String queueName, String keyword) {
        return repository.findAll(pageable, status, queueName, keyword);
    }

    @Transactional
    public void retry(UUID id) {
        repository.findById(id).ifPresent(this::retryMessage);
    }

    @Transactional
    public void batchRetry(List<UUID> ids) {
        for (UUID id : safeIds(ids)) {
            retry(id);
        }
    }

    @Transactional
    public void discard(UUID id) {
        repository.markDiscarded(id);
    }

    @Transactional
    public void batchDiscard(List<UUID> ids) {
        for (UUID id : safeIds(ids)) {
            discard(id);
        }
    }

    private void retryMessage(FailedMessageResponse message) {
        if (!"PENDING".equalsIgnoreCase(message.status())) {
            return;
        }
        String routingKey = StringUtils.hasText(message.routingKey())
                ? message.routingKey()
                : message.queueName();
        String exchange = StringUtils.hasText(message.exchange()) ? message.exchange() : "";
        if (StringUtils.hasText(routingKey)) {
            rabbitTemplate.convertAndSend(exchange, routingKey, message.payload());
        }
        repository.markRetried(message.id());
    }

    private List<UUID> safeIds(List<UUID> ids) {
        return ids == null ? List.of() : ids;
    }
}
