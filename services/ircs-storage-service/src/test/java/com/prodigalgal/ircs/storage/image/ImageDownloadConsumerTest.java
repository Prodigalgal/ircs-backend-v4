package com.prodigalgal.ircs.storage.image;

import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class ImageDownloadConsumerTest {

    private final StorageUuidMessageParser messageParser = new StorageUuidMessageParser();
    private final CoverImageDownloadService downloadService = org.mockito.Mockito.mock(CoverImageDownloadService.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final ImageDownloadConsumer consumer = new ImageDownloadConsumer(messageParser, downloadService, auditWriter);

    @Test
    void consumesUuidPayload() {
        UUID imageId = UUID.randomUUID();
        Message message = new Message(imageId.toString().getBytes(StandardCharsets.UTF_8), new MessageProperties());

        consumer.onMessage(message);

        verify(downloadService).process(imageId);
        WorkerJobAuditEvent event = captureAuditEvent();
        org.assertj.core.api.Assertions.assertThat(event.jobName()).isEqualTo("storage.cover-download");
        org.assertj.core.api.Assertions.assertThat(event.correlationId()).isEqualTo(imageId.toString());
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("succeeded");
    }

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }
}
