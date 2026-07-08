package com.prodigalgal.ircs.storage.image;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
public class StorageUuidMessageParser {

    public UUID parse(Message amqpMessage) {
        String body = new String(amqpMessage.getBody(), StandardCharsets.UTF_8)
                .replace("\"", "")
                .trim();
        try {
            return UUID.fromString(body);
        } catch (IllegalArgumentException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid storage UUID message payload", ex);
        }
    }
}
