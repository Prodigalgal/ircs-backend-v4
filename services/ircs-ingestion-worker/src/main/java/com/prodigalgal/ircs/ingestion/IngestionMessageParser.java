package com.prodigalgal.ircs.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class IngestionMessageParser {

    private final ObjectMapper objectMapper;

    public IngestionItem parse(Message message) {
        try {
            IngestionItem item = objectMapper.readValue(message.getBody(), IngestionItem.class);
            validate(item);
            return item;
        } catch (AmqpRejectAndDontRequeueException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid ingestion payload", ex);
        }
    }

    private void validate(IngestionItem item) {
        if (item == null || item.video() == null) {
            throw new AmqpRejectAndDontRequeueException("Ingestion payload missing video");
        }
        if (!StringUtils.hasText(item.video().sourceVid())) {
            throw new AmqpRejectAndDontRequeueException("Ingestion payload missing sourceVid");
        }
        if (!StringUtils.hasText(item.video().sourceHash())) {
            throw new AmqpRejectAndDontRequeueException("Ingestion payload missing sourceHash");
        }
        if (!StringUtils.hasText(item.video().title())) {
            throw new AmqpRejectAndDontRequeueException("Ingestion payload missing title");
        }
    }
}
