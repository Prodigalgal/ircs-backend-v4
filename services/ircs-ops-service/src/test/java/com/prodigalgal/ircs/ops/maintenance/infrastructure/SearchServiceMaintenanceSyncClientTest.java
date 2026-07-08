package com.prodigalgal.ircs.ops.maintenance.infrastructure;


import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
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

class SearchServiceMaintenanceSyncClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OpsConfigValues configValues = org.mockito.Mockito.mock(OpsConfigValues.class);

    @BeforeEach
    void setUp() {
        when(configValues.searchOwnerBaseUrl()).thenReturn("http://search-service:8080/");
        when(configValues.searchOwnerRequestTimeout()).thenReturn(Duration.ofMillis(100));
        when(configValues.searchOwnerServiceId()).thenReturn("ops-service");
        when(configValues.searchOwnerServiceToken()).thenReturn("service-token");
        when(configValues.searchOwnerScopes()).thenReturn("search:sync search:ops");
    }

    @Test
    void postsBatchToSearchOwnerWithServiceIdentityAndCorrelation() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, "{\"accepted\":2}"));
        SearchServiceMaintenanceSyncClient client = client(transport);

        int accepted = client.enqueueIndex(List.of(first, second), SearchEntityType.UNIFIED_VIDEO, "corr-1");

        assertThat(accepted).isEqualTo(2);
        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString())
                .isEqualTo("http://search-service:8080/internal/v1/search/sync-tasks/batch");
        assertThat(sent.policy().type().name()).isEqualTo("INTERNAL_SERVICE");
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("ops-search-maintenance");
        assertThat(sent.headers())
                .containsEntry("Content-Type", "application/json")
                .containsEntry("Accept", "application/json")
                .containsEntry("X-Correlation-Id", "corr-1")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_ID, "ops-service")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_TOKEN, "service-token")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_SCOPES, "search:sync search:ops");

        JsonNode body = OBJECT_MAPPER.readTree(sent.body());
        assertThat(body.path("entityIds")).hasSize(2);
        assertThat(body.path("entityType").asText()).isEqualTo("UNIFIED_VIDEO");
        assertThat(body.path("operation").asText()).isEqualTo("INDEX");
        assertThat(body.path("sourceService").asText()).isEqualTo("ops-service");
        assertThat(body.path("correlationId").asText()).isEqualTo("corr-1");
    }

    @Test
    void postsRawBatchToSearchOwner() throws Exception {
        UUID rawId = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, "{\"accepted\":1}"));
        SearchServiceMaintenanceSyncClient client = client(transport);

        int accepted = client.enqueueIndex(List.of(rawId), SearchEntityType.RAW_VIDEO, "corr-raw");

        assertThat(accepted).isEqualTo(1);
        JsonNode body = OBJECT_MAPPER.readTree(transport.requests.getFirst().body());
        assertThat(body.path("entityType").asText()).isEqualTo("RAW_VIDEO");
        assertThat(body.path("operation").asText()).isEqualTo("INDEX");
        assertThat(body.path("correlationId").asText()).isEqualTo("corr-raw");
    }

    @Test
    void postsHardResetToSearchOwnerWithServiceIdentityAndCorrelation() {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, """
                {"entityType":"UNIFIED_VIDEO","operation":"hard-reset","deletedSyncTasks":4,"recreated":true}
                """));
        SearchServiceMaintenanceSyncClient client = client(transport);

        int changed = client.hardResetIndex(SearchEntityType.UNIFIED_VIDEO, "corr-reset");

        assertThat(changed).isEqualTo(5);
        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString())
                .isEqualTo("http://search-service:8080/internal/v1/search/index-maintenance/UNIFIED_VIDEO/hard-reset");
        assertThat(sent.policy().type().name()).isEqualTo("INTERNAL_SERVICE");
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("ops-search-maintenance");
        assertThat(sent.headers())
                .containsEntry("Accept", "application/json")
                .containsEntry("X-Correlation-Id", "corr-reset")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_ID, "ops-service")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_TOKEN, "service-token")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_SCOPES, "search:sync search:ops");
    }

    @Test
    void emptyCandidateListDoesNotCallOwnerService() {
        FakeTransport transport = new FakeTransport();
        SearchServiceMaintenanceSyncClient client = client(transport);

        assertThat(client.enqueueIndex(List.of(), SearchEntityType.UNIFIED_VIDEO, "corr-1")).isZero();
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void non2xxResponseIsMappedToMaintenanceFailure() {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(503, "slow"));
        transport.enqueue(response(503, "still slow"));
        SearchServiceMaintenanceSyncClient client = client(transport);

        assertThatThrownBy(() -> client.enqueueIndex(List.of(UUID.randomUUID()), SearchEntityType.UNIFIED_VIDEO, "corr-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("search-service maintenance sync failed: upstream status 503");
    }

    private SearchServiceMaintenanceSyncClient client(FakeTransport transport) {
        return new SearchServiceMaintenanceSyncClient(
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
