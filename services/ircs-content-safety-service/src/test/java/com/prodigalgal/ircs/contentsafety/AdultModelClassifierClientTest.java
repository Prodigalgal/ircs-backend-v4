package com.prodigalgal.ircs.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentItem;
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

class AdultModelClassifierClientTest {

    @Test
    void sendsInternalServiceHeadersToClassifier() throws Exception {
        UUID id = UUID.randomUUID();
        AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/adult-classifier:classify", exchange -> {
            headers.set(exchange.getRequestHeaders());
            String body = """
                    {"items":[{"id":"%s","adultScore":0.92,"label":"ADULT","raw":{"source":"stub"}}]}
                    """
                    .formatted(id);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ContentSafetyProperties properties = new ContentSafetyProperties();
            ContentSafetyProperties.Model model = properties.adult().model();
            model.setEnabled(true);
            model.setEndpoint("http://127.0.0.1:%d/internal/v1/adult-classifier:classify"
                    .formatted(server.getAddress().getPort()));
            model.setRequestTimeout(Duration.ofSeconds(2));
            model.setServiceId("ircs-content-safety-service");
            model.setServiceToken("secret-token");
            model.setScopes("adult-classifier:classify");
            AdultModelClassifierClient client =
                    new AdultModelClassifierClient(JsonMapper.builder().build(), properties);

            Map<UUID, ?> results = client.classify(List.of(new AdultAssessmentItem(
                    id,
                    "普通标题",
                    null,
                    "普通别名",
                    null,
                    "普通子标题",
                    "other",
                    "其他",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of())));

            assertThat(results).containsKey(id);
            assertThat(firstHeader(headers.get(), InternalServiceAuthHeaders.SERVICE_ID))
                    .isEqualTo("ircs-content-safety-service");
            assertThat(firstHeader(headers.get(), InternalServiceAuthHeaders.SERVICE_TOKEN))
                    .isEqualTo("secret-token");
            assertThat(firstHeader(headers.get(), InternalServiceAuthHeaders.SERVICE_SCOPES))
                    .isEqualTo("adult-classifier:classify");
        } finally {
            server.stop(0);
        }
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) throws IOException {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            throw new IOException("Missing header " + name);
        }
        return values.getFirst();
    }
}
