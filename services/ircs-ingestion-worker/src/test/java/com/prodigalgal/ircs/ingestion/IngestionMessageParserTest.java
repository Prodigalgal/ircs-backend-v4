package com.prodigalgal.ircs.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class IngestionMessageParserTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();
    private final IngestionMessageParser parser = new IngestionMessageParser(objectMapper);

    @Test
    void parsesValidIngestionPayload() throws Exception {
        String json = """
                {
                  "video": {
                    "sourceVid": "codex-source-1",
                    "sourceHash": "codex-source-hash",
                    "dataHash": "codex-data-hash",
                    "title": "Codex Smoke",
                    "publishedAt": "2026-06-03",
                    "playlists": []
                  },
                  "forceIngest": false
                }
                """;

        IngestionItem item = parser.parse(message(json));

        assertEquals("codex-source-hash", item.video().sourceHash());
        assertEquals("Codex Smoke", item.video().title());
    }

    @Test
    void rejectsPayloadWithoutSourceHash() {
        String json = """
                {
                  "video": {
                    "sourceVid": "codex-source-1",
                    "title": "Codex Smoke"
                  },
                  "forceIngest": false
                }
                """;

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> parser.parse(message(json)));
    }

    private Message message(String json) {
        return new Message(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties());
    }
}
