package com.prodigalgal.ircs.ops.infrastructure.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RabbitManagementQueueClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RabbitManagementQueueClient client = new RabbitManagementQueueClient(objectMapper, null);

    @Test
    void parsesNativeRabbitQueueCountersWithReadyUnackedAndTotal() throws Exception {
        List<RabbitManagementQueueSnapshot> snapshots = client.snapshots(objectMapper.readTree("""
                [
                  {
                    "name": "q.task.detail.dlq",
                    "messages": 12,
                    "messages_ready": 7,
                    "messages_unacknowledged": 5,
                    "consumers": 1
                  },
                  {
                    "name": "q.empty.dlq",
                    "messages_ready": 3,
                    "messages_unacknowledged": 2,
                    "consumers": 0
                  }
                ]
                """));

        assertThat(snapshots)
                .extracting(RabbitManagementQueueSnapshot::name)
                .containsExactly("q.task.detail.dlq", "q.empty.dlq");
        assertThat(snapshots.get(0).messagesReady()).isEqualTo(7);
        assertThat(snapshots.get(0).messagesUnacknowledged()).isEqualTo(5);
        assertThat(snapshots.get(0).messagesTotal()).isEqualTo(12);
        assertThat(snapshots.get(1).messagesTotal()).isEqualTo(5);
    }

    @Test
    void parsesRabbitMessageSamplesWithIrcsFailureHeaders() throws Exception {
        List<RabbitManagementMessageSample> samples = client.messageSamples(objectMapper.readTree("""
                [
                  {
                    "payload_bytes": 701,
                    "payload": "{\\"sourceVid\\":\\"1538515\\"}",
                    "properties": {
                      "message_id": "message-1",
                      "correlation_id": "correlation-1",
                      "headers": {
                        "x-ircs-retry-count": 3,
                        "x-ircs-disposition": "dlq",
                        "x-ircs-error-class": "java.lang.IllegalStateException",
                        "x-ircs-error-message": "HTTP fetch failed"
                      }
                    }
                  }
                ]
                """));

        assertThat(samples).hasSize(1);
        RabbitManagementMessageSample sample = samples.get(0);
        assertThat(sample.messageId()).isEqualTo("message-1");
        assertThat(sample.correlationId()).isEqualTo("correlation-1");
        assertThat(sample.retryCount()).isEqualTo(3);
        assertThat(sample.disposition()).isEqualTo("dlq");
        assertThat(sample.errorClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(sample.errorMessage()).isEqualTo("HTTP fetch failed");
        assertThat(sample.bodyBytes()).isEqualTo(701);
        assertThat(sample.bodyPreview()).isEqualTo("{\"sourceVid\":\"1538515\"}");
    }
}
