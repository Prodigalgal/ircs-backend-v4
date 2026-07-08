package com.prodigalgal.ircs.content.video.infrastructure;


import com.prodigalgal.ircs.content.video.api.ContentApiException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.content.config.ContentConfigValues;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InternalContentClientsTest {

    private HttpServer firstServer;
    private HttpServer secondServer;
    private static String previousFailureThreshold;
    private static String previousOpenDuration;

    @BeforeAll
    static void lowerCircuitThresholdForContentCallerTest() {
        previousFailureThreshold = System.getProperty("ircs.outbound.circuit.failure-threshold");
        previousOpenDuration = System.getProperty("ircs.outbound.circuit.open-duration-ms");
        System.setProperty("ircs.outbound.circuit.failure-threshold", "1");
        System.setProperty("ircs.outbound.circuit.open-duration-ms", "60000");
    }

    @AfterAll
    static void restoreCircuitProperties() {
        restoreProperty("ircs.outbound.circuit.failure-threshold", previousFailureThreshold);
        restoreProperty("ircs.outbound.circuit.open-duration-ms", previousOpenDuration);
    }

    @AfterEach
    void stopServers() {
        if (firstServer != null) {
            firstServer.stop(0);
        }
        if (secondServer != null) {
            secondServer.stop(0);
        }
    }

    @Test
    void refetchUsesCurrentScraperBaseUrlForEachCall() throws Exception {
        AtomicReference<String> firstPath = new AtomicReference<>();
        AtomicReference<String> secondPath = new AtomicReference<>();
        firstServer = startServer(firstPath);
        secondServer = startServer(secondPath);
        ContentConfigValues configValues = org.mockito.Mockito.mock(ContentConfigValues.class);
        when(configValues.scraperBaseUrl())
                .thenReturn(baseUrl(firstServer))
                .thenReturn(baseUrl(secondServer));
        InternalContentClients clients = InternalContentClients.forTest(configValues);
        UUID rawVideoId = UUID.randomUUID();

        clients.refetchRawVideo(rawVideoId);
        clients.refetchRawVideo(rawVideoId);

        assertEquals("/internal/v1/scraper/raw-videos/" + rawVideoId + "/refetch", firstPath.get());
        assertEquals("/internal/v1/scraper/raw-videos/" + rawVideoId + "/refetch", secondPath.get());
    }

    @Test
    void mapsUpstreamFailureAndOpenCircuitToBadGatewayWithoutExtraTransportCall() {
        FakeTransport transport = new FakeTransport();
        ContentConfigValues configValues = org.mockito.Mockito.mock(ContentConfigValues.class);
        when(configValues.scraperBaseUrl()).thenReturn("http://scraper-service:8080");
        InternalContentClients clients = InternalContentClients.forTest(
                configValues,
                new OutboundHttpClient(new OutboundUrlPolicy(host -> {
                    throw new AssertionError("INTERNAL_SERVICE must not perform public DNS SSRF resolution");
        }), transport),
                Duration.ofMillis(100));
        UUID rawVideoId = UUID.randomUUID();
        transport.responses.add(new OutboundHttpResponse(503, Map.of(), "slow".getBytes(StandardCharsets.UTF_8)));
        transport.responses.add(new OutboundHttpResponse(503, Map.of(), "still slow".getBytes(StandardCharsets.UTF_8)));

        ContentApiException first = assertThrows(ContentApiException.class, () -> clients.refetchRawVideo(rawVideoId));
        assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY, first.status());
        assertEquals("Failed to dispatch raw video refetch: upstream status 503", first.getMessage());

        ContentApiException second = assertThrows(ContentApiException.class, () -> clients.refetchRawVideo(rawVideoId));
        assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY, second.status());
        assertEquals("Failed to dispatch raw video refetch: outbound circuit open", second.getMessage());
        assertEquals(2, transport.requests.size());
        assertEquals("POST", transport.requests.getFirst().method());
        assertEquals("/internal/v1/scraper/raw-videos/" + rawVideoId + "/refetch",
                transport.requests.getFirst().uri().getPath());
        assertEquals("content-refetch-scraper:scraper-service:8080",
                transport.requests.getFirst().policy().circuitBreakerKey()
                        + ":" + transport.requests.getFirst().uri().getHost()
                        + ":" + transport.requests.getFirst().uri().getPort());
    }

    private HttpServer startServer(AtomicReference<String> path) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/scraper/raw-videos", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static final class FakeTransport implements OutboundTransport {

        private final List<OutboundHttpRequest> requests = new ArrayList<>();
        private final List<OutboundHttpResponse> responses = new ArrayList<>();

        @Override
        public OutboundHttpResponse send(OutboundHttpRequest request) {
            requests.add(request);
            return responses.remove(0);
        }
    }
}
