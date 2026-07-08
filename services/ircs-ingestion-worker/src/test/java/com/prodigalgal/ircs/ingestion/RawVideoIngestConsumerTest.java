package com.prodigalgal.ircs.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.contracts.ingestion.IngestionVideoDTO;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class RawVideoIngestConsumerTest {

    private final IngestionMessageParser messageParser = org.mockito.Mockito.mock(IngestionMessageParser.class);
    private final RawVideoIngestionService ingestionService = org.mockito.Mockito.mock(RawVideoIngestionService.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final RawVideoIngestConsumer consumer =
            new RawVideoIngestConsumer(messageParser, ingestionService, auditWriter);

    @Test
    void writesSucceededAuditForRawVideoIngestion() {
        IngestionItem item = item("source-hash-1");
        UUID rawVideoId = UUID.randomUUID();
        when(messageParser.parse(any(Message.class))).thenReturn(item);
        when(ingestionService.ingest(item)).thenReturn(RawVideoIngestionService.IngestResult.persisted(rawVideoId));

        consumer.onMessage(message());

        verify(ingestionService).ingest(item);
        WorkerJobAuditEvent event = captureAuditEvent();
        assertThat(event.jobType()).isEqualTo(RawVideoIngestConsumer.JOB_TYPE);
        assertThat(event.jobName()).isEqualTo(RawVideoIngestConsumer.JOB_NAME);
        assertThat(event.correlationId()).isEqualTo("source-hash-1");
        assertThat(event.status()).isEqualTo("succeeded");
        assertThat(event.error()).isNull();
    }

    @Test
    void writesFailedAuditAndRethrowsIngestionFailure() {
        IngestionItem item = item("source-hash-2");
        RuntimeException failure = new RuntimeException("database rejected insert with payload details");
        when(messageParser.parse(any(Message.class))).thenReturn(item);
        doThrow(failure).when(ingestionService).ingest(item);

        assertThatThrownBy(() -> consumer.onMessage(message())).isSameAs(failure);

        WorkerJobAuditEvent event = captureAuditEvent();
        assertThat(event.jobType()).isEqualTo(RawVideoIngestConsumer.JOB_TYPE);
        assertThat(event.jobName()).isEqualTo(RawVideoIngestConsumer.JOB_NAME);
        assertThat(event.correlationId()).isEqualTo("source-hash-2");
        assertThat(event.status()).isEqualTo("failed");
        assertThat(event.error()).hasMessage("raw video ingestion failed");
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

    private static IngestionItem item(String sourceHash) {
        return new IngestionItem(new IngestionVideoDTO(
                "source-vid",
                sourceHash,
                "data-hash",
                "Codex Source",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null), false);
    }
}
