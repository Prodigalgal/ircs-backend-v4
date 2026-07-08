package com.prodigalgal.ircs.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.adult.AdultAssessmentInput;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ContentSafetyAdultAssessmentClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void assessSendsCompactPayloadWithoutRawMetadata() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = captureServer(requestBody);
        server.start();
        try {
            UUID id = UUID.randomUUID();
            ContentSafetyAdultAssessmentClient client = new ContentSafetyAdultAssessmentClient(
                    objectMapper,
                    true,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "aggregation-test",
                    "token",
                    "content-safety:assess",
                    Duration.ofSeconds(3));

            client.assess(Map.of(id, new AdultAssessmentInput(
                    "title",
                    "alias",
                    "d".repeat(800),
                    "remarks",
                    "subtitle",
                    "movie",
                    "Movie",
                    List.of("actor"),
                    List.of("director"),
                    List.of("genre"),
                    List.of(new AdultAssessmentInput.SourceEvidence(
                            "source",
                            true,
                            "adult",
                            "Adult",
                            "example.test",
                            "raw-metadata-should-not-cross-service-boundary")))));

            JsonNode item = objectMapper.readTree(requestBody.get()).path("items").get(0);
            assertEquals(512, item.path("description").asText().length());
            assertTrue(item.path("sources").get(0).path("rawMetadata").isNull());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer captureServer(AtomicReference<String> requestBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/content-safety/adult-assessments:batch", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"engineVersion\":\"test\",\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }
}
