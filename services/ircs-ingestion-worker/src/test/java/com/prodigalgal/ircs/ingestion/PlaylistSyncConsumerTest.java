package com.prodigalgal.ircs.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.ingestion.PlaylistSyncMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class PlaylistSyncConsumerTest {

    private final PlaylistSyncMessageParser messageParser = org.mockito.Mockito.mock(PlaylistSyncMessageParser.class);
    private final RawVideoIngestionService ingestionService = org.mockito.Mockito.mock(RawVideoIngestionService.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final PlaylistSyncConsumer consumer =
            new PlaylistSyncConsumer(messageParser, ingestionService, auditWriter);

    @Test
    void writesSucceededAuditForPlaylistSync() {
        UUID rawVideoId = UUID.randomUUID();
        PlaylistSyncMessage syncMessage = message(rawVideoId);
        when(messageParser.parse(any(Message.class))).thenReturn(syncMessage);
        when(ingestionService.syncPlaylists(syncMessage))
                .thenReturn(RawVideoIngestionService.PlaylistSyncResult.synced(rawVideoId, 2));

        consumer.onMessage(message());

        verify(ingestionService).syncPlaylists(syncMessage);
        WorkerJobAuditEvent event = captureAuditEvent();
        assertThat(event.jobType()).isEqualTo(PlaylistSyncConsumer.JOB_TYPE);
        assertThat(event.jobName()).isEqualTo(PlaylistSyncConsumer.JOB_NAME);
        assertThat(event.correlationId()).isEqualTo(rawVideoId.toString());
        assertThat(event.status()).isEqualTo("succeeded");
        assertThat(event.error()).isNull();
    }

    @Test
    void writesFailedAuditAndRethrowsPlaylistFailure() {
        UUID rawVideoId = UUID.randomUUID();
        PlaylistSyncMessage syncMessage = message(rawVideoId);
        RuntimeException failure = new RuntimeException("playlist replacement failed with row details");
        when(messageParser.parse(any(Message.class))).thenReturn(syncMessage);
        doThrow(failure).when(ingestionService).syncPlaylists(syncMessage);

        assertThatThrownBy(() -> consumer.onMessage(message())).isSameAs(failure);

        WorkerJobAuditEvent event = captureAuditEvent();
        assertThat(event.jobType()).isEqualTo(PlaylistSyncConsumer.JOB_TYPE);
        assertThat(event.jobName()).isEqualTo(PlaylistSyncConsumer.JOB_NAME);
        assertThat(event.correlationId()).isEqualTo(rawVideoId.toString());
        assertThat(event.status()).isEqualTo("failed");
        assertThat(event.error()).hasMessage("playlist sync failed");
        assertThat(event.error()).isNotSameAs(failure);
    }

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }

    private static Message message() {
        return new Message(new byte[0], new MessageProperties());
    }

    private static PlaylistSyncMessage message(UUID rawVideoId) {
        return new PlaylistSyncMessage(rawVideoId, "source-hash", "data-hash", List.of());
    }
}
