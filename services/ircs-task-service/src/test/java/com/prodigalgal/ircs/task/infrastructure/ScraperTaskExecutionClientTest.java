package com.prodigalgal.ircs.task.infrastructure;


import com.prodigalgal.ircs.task.domain.TaskExecutionPlan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScraperTaskExecutionClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String previousFailureThreshold;
    private String previousOpenDuration;

    @BeforeEach
    void lowerCircuitThreshold() {
        previousFailureThreshold = System.getProperty("ircs.outbound.circuit.failure-threshold");
        previousOpenDuration = System.getProperty("ircs.outbound.circuit.open-duration-ms");
        System.setProperty("ircs.outbound.circuit.failure-threshold", "1");
        System.setProperty("ircs.outbound.circuit.open-duration-ms", "60000");
    }

    @AfterEach
    void restoreCircuitThreshold() {
        restoreProperty("ircs.outbound.circuit.failure-threshold", previousFailureThreshold);
        restoreProperty("ircs.outbound.circuit.open-duration-ms", previousOpenDuration);
    }

    @Test
    void postsJsonBodyAndMapsSuccessfulResponse() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(200, """
                {"status":"COMPLETED","publishedCount":2,"failedCount":0,
                 "logs":[{"timestamp":"2026-06-09T00:00:00Z","level":"INFO","sourceVid":"vid-1","message":"published"}]}
                """));
        ScraperTaskExecutionClient client = client(transport);
        TaskExecutionPlan plan = plan("""
                {"httpHeaders":{"X-Source":"codex"},"ircsTaskRunner":{"directItems":[{"sourceVid":"direct-1"}]}}
                """);

        ScraperTaskExecutionResult result = client.execute(plan, true);

        assertThat(result.successful()).isTrue();
        assertThat(result.published()).isEqualTo(2);
        assertThat(result.logs()).hasSize(1);
        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString())
                .isEqualTo("http://scraper-service:8080/internal/v1/scraper/task-executions");
        assertThat(sent.headers())
                .containsEntry("Content-Type", "application/json")
                .containsEntry("Accept", "application/json");
        assertThat(sent.policy().type().name()).isEqualTo("INTERNAL_SERVICE");
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("task-scraper-execution");

        JsonNode body = OBJECT_MAPPER.readTree(sent.body());
        assertThat(body.path("taskId").asText()).isEqualTo(plan.id().toString());
        assertThat(body.path("dataSourceId").asText()).isEqualTo(plan.dataSourceId().toString());
        assertThat(body.path("keyword").asText()).isEqualTo("matrix");
        assertThat(body.path("filterType").asText()).isEqualTo("movie");
        assertThat(body.path("filterHours").asInt()).isEqualTo(48);
        assertThat(body.path("startPage").asInt()).isEqualTo(3);
        assertThat(body.path("endPage").asInt()).isEqualTo(5);
        assertThat(body.path("userAgent").asText()).isEqualTo("task-ua");
        assertThat(body.path("enableRandomUa").asBoolean()).isTrue();
        assertThat(body.path("useCustomProxy").asBoolean()).isTrue();
        assertThat(body.path("proxyHost").asText()).isEqualTo("proxy.local");
        assertThat(body.path("proxyPassword").asText()).isEqualTo("secret");
        assertThat(body.path("headers").asText()).isEqualTo("{\"X-Source\":\"codex\"}");
        assertThat(body.path("directItems")).hasSize(1);
        assertThat(body.path("forceIngest").asBoolean()).isFalse();
    }

    @Test
    void maps4xxAnd5xxResponsesToTaskFailureCompatibleException() {
        FakeTransport fourHundredTransport = new FakeTransport();
        fourHundredTransport.enqueue(response(400, "{\"error\":\"bad request\"}"));
        ScraperTaskExecutionClient fourHundredClient = client(fourHundredTransport);

        assertThatThrownBy(() -> fourHundredClient.execute(plan("{}"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scraper-service task execution failed: upstream status 400");
        assertThat(fourHundredTransport.requests).hasSize(1);

        FakeTransport fiveHundredTransport = new FakeTransport();
        fiveHundredTransport.enqueue(response(503, "slow"));
        fiveHundredTransport.enqueue(response(503, "still slow"));
        ScraperTaskExecutionClient fiveHundredClient = client(fiveHundredTransport);

        assertThatThrownBy(() -> fiveHundredClient.execute(plan("{}"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scraper-service task execution failed: upstream status 503");
        assertThat(fiveHundredTransport.requests).hasSize(2);
    }

    @Test
    void mapsTimeoutAndConnectionRefusedToTaskFailureCompatibleException() {
        FakeTransport timeoutTransport = new FakeTransport();
        timeoutTransport.enqueue(new HttpTimeoutException("timeout"));
        timeoutTransport.enqueue(new HttpTimeoutException("timeout"));
        ScraperTaskExecutionClient timeoutClient = client(timeoutTransport);

        assertThatThrownBy(() -> timeoutClient.execute(plan("{}"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scraper-service task execution failed: timeout");
        assertThat(timeoutTransport.requests).hasSize(2);

        FakeTransport refusedTransport = new FakeTransport();
        refusedTransport.enqueue(new IOException("connection refused"));
        refusedTransport.enqueue(new IOException("connection refused"));
        ScraperTaskExecutionClient refusedClient = client(refusedTransport);

        assertThatThrownBy(() -> refusedClient.execute(plan("{}"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scraper-service task execution failed: connection refused");
        assertThat(refusedTransport.requests).hasSize(2);
    }

    @Test
    void openCircuitFastFailsWithoutExtraTransportCall() {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(503, "slow"));
        transport.enqueue(response(503, "still slow"));
        ScraperTaskExecutionClient client = client(transport);

        assertThatThrownBy(() -> client.execute(plan("{}"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scraper-service task execution failed: upstream status 503");
        assertThatThrownBy(() -> client.execute(plan("{}"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scraper-service task execution failed: outbound circuit open");

        assertThat(transport.requests).hasSize(2);
    }

    private ScraperTaskExecutionClient client(FakeTransport transport) {
        return new ScraperTaskExecutionClient(
                OBJECT_MAPPER,
                new OutboundHttpClient(new OutboundUrlPolicy(host -> {
                    throw new AssertionError("INTERNAL_SERVICE must not perform public DNS SSRF resolution");
                }), transport),
                "http://scraper-service:8080/",
                Duration.ofMillis(100));
    }

    private TaskExecutionPlan plan(String headers) {
        return new TaskExecutionPlan(
                UUID.randomUUID(),
                "Codex task",
                UUID.randomUUID(),
                "RUNNING",
                true,
                1,
                5,
                3,
                "movie",
                48,
                "matrix",
                25,
                "task-ua",
                true,
                true,
                "HTTP",
                "proxy.local",
                8080,
                "proxy-user",
                "secret",
                headers);
    }

    private static OutboundHttpResponse response(int status, String body) {
        return new OutboundHttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
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
        private final Queue<Object> responses = new ArrayDeque<>();

        void enqueue(Object response) {
            responses.add(response);
        }

        @Override
        public OutboundHttpResponse send(OutboundHttpRequest request) throws IOException {
            requests.add(request);
            Object next = responses.remove();
            if (next instanceof IOException ex) {
                throw ex;
            }
            return (OutboundHttpResponse) next;
        }
    }
}
