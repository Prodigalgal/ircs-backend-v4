package com.prodigalgal.ircs.interaction;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.interaction.WatchProgressMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class WatchProgressConsumerTest {

    private final WatchProgressMessageParser parser = org.mockito.Mockito.mock(WatchProgressMessageParser.class);
    private final WatchProgressBatchService batchService = org.mockito.Mockito.mock(WatchProgressBatchService.class);
    private final WatchProgressConsumer consumer = new WatchProgressConsumer(parser, batchService);

    @Test
    void onMessageParsesAndDelegatesSingleMessageAsBatch() {
        Message amqpMessage = new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        WatchProgressMessage parsed = new WatchProgressMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                "第1集",
                12,
                100,
                Instant.parse("2026-06-07T01:03:00Z"));
        when(parser.parse(amqpMessage)).thenReturn(parsed);

        consumer.onMessage(amqpMessage);

        verify(batchService).batchUpsert(argThat(messages -> messages.size() == 1 && messages.get(0) == parsed));
    }
}
