package com.prodigalgal.ircs.content.image;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
public class ContentUuidMessageParser {

    public UUID parse(Message amqpMessage) {
        String body = new String(amqpMessage.getBody(), StandardCharsets.UTF_8)
                .replace("\"", "")
                .trim();
        try {
            return UUID.fromString(body);
        } catch (IllegalArgumentException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid content UUID message payload", ex);
        }
    }
}
