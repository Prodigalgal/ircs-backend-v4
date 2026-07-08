package com.prodigalgal.ircs.metadata.provider.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class MetadataSearchContextMessageParserTest {

    private final MetadataSearchContextMessageParser parser = new MetadataSearchContextMessageParser(new ObjectMapper());

    @Test
    void parsesProviderContextJson() {
        UUID videoId = UUID.randomUUID();
        String json = """
                {"videoId":"%s","title":"The Matrix","categorySlug":"movie","year":"1999"}
                """.formatted(videoId);

        MetadataSearchContext context = parser.parse(message(json));

        assertEquals(videoId, context.getVideoId());
        assertEquals("The Matrix", context.getTitle());
        assertEquals("movie", context.getCategorySlug());
    }

    @Test
    void rejectsMissingVideoIdWithoutRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> parser.parse(message("{\"title\":\"x\"}")));
    }

    @Test
    void rejectsInvalidPayloadWithoutRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> parser.parse(message("bad")));
    }

    private Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
