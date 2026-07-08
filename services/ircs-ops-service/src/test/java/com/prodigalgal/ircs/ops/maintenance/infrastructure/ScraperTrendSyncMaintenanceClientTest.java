package com.prodigalgal.ircs.ops.maintenance.infrastructure;


import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScraperTrendSyncMaintenanceClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OpsConfigValues configValues = org.mockito.Mockito.mock(OpsConfigValues.class);

    @BeforeEach
    void setUp() {
        when(configValues.scraperOwnerBaseUrl()).thenReturn("http://scraper-service:8080/");
        when(configValues.scraperOwnerRequestTimeout()).thenReturn(Duration.ofSeconds(9));
        when(configValues.scraperOwnerServiceId()).thenReturn("ops-service");
        when(configValues.scraperOwnerServiceToken()).thenReturn("scraper-token");
        when(configValues.scraperOwnerScopes()).thenReturn("scraper:maintenance ops:maintenance");
    }

    @Test
    void postsTrendSyncToScraperOwnerWithServiceIdentity() {
        UUID updatedId = UUID.randomUUID();
        UUID createdId = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, """
                {
                  "taskName":"trend-sync",
                  "providerCount":2,
                  "fetchedItems":12,
                  "applyResult":{
                    "taskName":"trend-sync",
                    "candidates":12,
                    "updatedByExternalId":5,
                    "updatedByTitleMatch":1,
                    "createdGhosts":2,
                    "skippedDuplicates":1,
                    "updatedUnifiedVideoIds":["%s"],
                    "createdUnifiedVideoIds":["%s"],
                    "discoveryKeywords":["Codex"]
                  },
                  "providerErrors":[]
                }
                """.formatted(updatedId, createdId)));
        ScraperTrendSyncMaintenanceClient client = client(transport);

        MaintenanceRunResult result = client.syncTrends("corr-trend");

        assertThat(result.taskName()).isEqualTo("trend-sync");
        assertThat(result.selectedCount()).isEqualTo(12);
        assertThat(result.publishedCount()).isEqualTo(8);
        assertThat(result.entityIds()).containsExactly(updatedId, createdId);

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString())
                .isEqualTo("http://scraper-service:8080/internal/v1/scraper/trends/sync");
        assertThat(sent.policy().type().name()).isEqualTo("INTERNAL_SERVICE");
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("ops-scraper-trend-maintenance");
        assertThat(sent.headers())
                .containsEntry("Accept", "application/json")
                .containsEntry("X-Correlation-Id", "corr-trend")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_ID, "ops-service")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_TOKEN, "scraper-token")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_SCOPES, "scraper:maintenance ops:maintenance");
        assertThat(sent.body()).isEmpty();
    }

    private ScraperTrendSyncMaintenanceClient client(FakeTransport transport) {
        return new ScraperTrendSyncMaintenanceClient(
                OBJECT_MAPPER,
                new OutboundHttpClient(new OutboundUrlPolicy(host -> {
                    throw new AssertionError("INTERNAL_SERVICE must not perform public DNS SSRF resolution");
                }), transport),
                configValues);
    }

    private static OutboundHttpResponse response(int status, String body) {
        return new OutboundHttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
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
