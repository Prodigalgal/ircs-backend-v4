package com.prodigalgal.ircs.metadata.provider.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetadataSearchContextMessageParser {

    private final ObjectMapper objectMapper;

    public MetadataSearchContext parse(Message message) {
        try {
            MetadataSearchContext context = objectMapper.readValue(message.getBody(), MetadataSearchContext.class);
            if (context.getVideoId() == null) {
                throw new AmqpRejectAndDontRequeueException("Metadata provider task missing videoId");
            }
            return context;
        } catch (AmqpRejectAndDontRequeueException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid metadata provider task payload", ex);
        }
    }
}
