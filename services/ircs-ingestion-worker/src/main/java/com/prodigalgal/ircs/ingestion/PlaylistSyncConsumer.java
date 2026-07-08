package com.prodigalgal.ircs.ingestion;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.ingestion.PlaylistSyncMessage;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.listener", name = "enabled", havingValue = "true")
public class PlaylistSyncConsumer {

    static final String JOB_TYPE = "queue-consumer";
    static final String JOB_NAME = "ingestion.playlist-sync";

    private final PlaylistSyncMessageParser messageParser;
    private final RawVideoIngestionService ingestionService;
    private final WorkerJobAuditWriter auditWriter;

    @RabbitListener(queues = QueueTopic.Names.PROCESS_Q_PLAYLIST)
    public void onMessage(Message message) {
        Instant startedAt = Instant.now();
        PlaylistSyncMessage syncMessage = null;
        try {
            syncMessage = messageParser.parse(message);
            log.info("Processing playlist sync: videoId={}, sourceHash={}",
                    syncMessage.videoId(), syncMessage.sourceHash());
            ingestionService.syncPlaylists(syncMessage);
            recordAudit(WorkerJobAuditEvent.succeeded(
                    JOB_TYPE,
                    JOB_NAME,
                    correlationId(syncMessage),
                    elapsedSince(startedAt)));
        } catch (RuntimeException ex) {
            recordAudit(WorkerJobAuditEvent.failed(
                    JOB_TYPE,
                    JOB_NAME,
                    correlationId(syncMessage),
                    elapsedSince(startedAt),
                    new PlaylistSyncAuditException("playlist sync failed")));
            throw ex;
        }
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Playlist sync audit write failed: {}", ex.getMessage());
        }
    }

    private static String correlationId(PlaylistSyncMessage message) {
        return message == null || message.videoId() == null ? null : message.videoId().toString();
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private static class PlaylistSyncAuditException extends RuntimeException {
        PlaylistSyncAuditException(String message) {
            super(message);
        }
    }
}
