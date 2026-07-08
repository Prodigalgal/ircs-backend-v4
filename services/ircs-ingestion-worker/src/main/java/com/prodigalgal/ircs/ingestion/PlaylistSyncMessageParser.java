package com.prodigalgal.ircs.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.ingestion.PlaylistSyncMessage;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlaylistSyncMessageParser {

    private final ObjectMapper objectMapper;

    public PlaylistSyncMessage parse(Message message) {
        try {
            PlaylistSyncMessage syncMessage = objectMapper.readValue(message.getBody(), PlaylistSyncMessage.class);
            if (syncMessage.videoId() == null || syncMessage.sourceHash() == null || syncMessage.dataHash() == null) {
                throw new AmqpRejectAndDontRequeueException("Playlist sync payload missing required fields");
            }
            return syncMessage;
        } catch (AmqpRejectAndDontRequeueException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid playlist sync payload", ex);
        }
    }
}
