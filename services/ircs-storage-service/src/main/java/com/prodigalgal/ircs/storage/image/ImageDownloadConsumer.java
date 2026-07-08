package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ImageDownloadConsumer {

    private final StorageUuidMessageParser messageParser;
    private final CoverImageDownloadService downloadService;
    private final WorkerJobAuditWriter auditWriter;

    @RabbitListener(queues = QueueTopic.Names.STORAGE_Q_IMAGE)
    public void onMessage(Message message) {
        Instant startedAt = Instant.now();
        UUID imageId = null;
        try {
            imageId = messageParser.parse(message);
            log.info("Processing cover image download: {}", imageId);
            downloadService.process(imageId);
            recordSucceeded(startedAt, imageId);
        } catch (RuntimeException ex) {
            recordFailed(startedAt, imageId, ex);
            throw ex;
        }
    }

    private void recordSucceeded(Instant startedAt, UUID imageId) {
        recordAudit(WorkerJobAuditEvent.succeeded(
                "queue-consumer",
                "storage.cover-download",
                correlationId(imageId),
                elapsedSince(startedAt)));
    }

    private void recordFailed(Instant startedAt, UUID imageId, RuntimeException error) {
        recordAudit(WorkerJobAuditEvent.failed(
                "queue-consumer",
                "storage.cover-download",
                correlationId(imageId),
                elapsedSince(startedAt),
                error));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Cover image download audit write failed: {}", ex.getMessage());
        }
    }

    private static String correlationId(UUID id) {
        return id == null ? null : id.toString();
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }
}
