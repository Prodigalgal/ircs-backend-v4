package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class StorageUuidMessageParserTest {

    private final StorageUuidMessageParser parser = new StorageUuidMessageParser();

    @Test
    void parsesQuotedUuidPayload() {
        UUID id = UUID.randomUUID();
        byte[] body = ("\"" + id + "\"").getBytes(StandardCharsets.UTF_8);

        UUID actual = parser.parse(new Message(body, new MessageProperties()));

        assertEquals(id, actual);
    }

    @Test
    void parsesRawUuidPayload() {
        UUID id = UUID.randomUUID();
        byte[] body = id.toString().getBytes(StandardCharsets.UTF_8);

        UUID actual = parser.parse(new Message(body, new MessageProperties()));

        assertEquals(id, actual);
    }

    @Test
    void rejectsInvalidPayloadWithoutRequeue() {
        byte[] body = "not-a-uuid".getBytes(StandardCharsets.UTF_8);

        assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> parser.parse(new Message(body, new MessageProperties())));
    }
}
