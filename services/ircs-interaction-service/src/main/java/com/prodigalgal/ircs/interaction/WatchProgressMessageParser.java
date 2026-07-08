package com.prodigalgal.ircs.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.interaction.WatchProgressMessage;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WatchProgressMessageParser {

    private final ObjectMapper objectMapper;

    public WatchProgressMessage parse(Message amqpMessage) {
        try {
            return objectMapper.readValue(amqpMessage.getBody(), WatchProgressMessage.class);
        } catch (IOException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid watch progress message payload", ex);
        }
    }
}
