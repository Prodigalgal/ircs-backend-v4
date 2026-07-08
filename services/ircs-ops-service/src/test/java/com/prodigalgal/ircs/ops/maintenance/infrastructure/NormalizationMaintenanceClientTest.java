package com.prodigalgal.ircs.ops.maintenance.infrastructure;


import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

class NormalizationMaintenanceClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OpsConfigValues configValues = org.mockito.Mockito.mock(OpsConfigValues.class);

    @BeforeEach
    void setUp() {
        when(configValues.normalizationOwnerBaseUrl()).thenReturn("http://normalization-worker:8080/");
        when(configValues.normalizationOwnerRequestTimeout()).thenReturn(Duration.ofSeconds(3));
        when(configValues.normalizationOwnerServiceId()).thenReturn("ops-service");
        when(configValues.normalizationOwnerServiceToken()).thenReturn("service-token");
        when(configValues.normalizationOwnerScopes()).thenReturn("normalization:maintenance ops:maintenance");
    }

    @Test
    void postsResetAllNormalizationToNormalizationOwnerWithServiceIdentity() {
        UUID sampleId = UUID.randomUUID();
        when(configValues.normalizationMaintenanceDevLimit()).thenReturn(7);
        when(configValues.normalizationMaintenanceBatchSize()).thenReturn(300);
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, """
                {"taskName":"sanitize","rawVideoCount":12,"changedRows":9,"enqueuedRows":11,"sampleRawVideoIds":["%s"]}
                """.formatted(sampleId)));
        NormalizationMaintenanceClient client = client(transport);

        MaintenanceRunResult result = client.resetAllNormalization("corr-sanitize");

        assertThat(result.taskName()).isEqualTo("sanitize");
        assertThat(result.selectedCount()).isEqualTo(12);
        assertThat(result.publishedCount()).isEqualTo(11);
        assertThat(result.entityIds()).containsExactly(sampleId);

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString())
                .isEqualTo("http://normalization-worker:8080/internal/v1/normalization/maintenance/raw-videos/reset-normalization?sampleLimit=7&enqueue=true&batchSize=300");
        assertThat(sent.policy().type().name()).isEqualTo("INTERNAL_SERVICE");
        assertThat(sent.policy().timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("ops-normalization-maintenance");
        assertThat(sent.headers())
                .containsEntry("Accept", "application/json")
                .containsEntry("X-Correlation-Id", "corr-sanitize")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_ID, "ops-service")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_TOKEN, "service-token")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_SCOPES, "normalization:maintenance ops:maintenance");
        assertThat(sent.headers()).doesNotContainKey("Content-Type");
        assertThat(sent.body()).isEmpty();
    }

    @Test
    void non2xxResponseIsMappedToMaintenanceFailure() {
        when(configValues.normalizationMaintenanceDevLimit()).thenReturn(7);
        when(configValues.normalizationMaintenanceBatchSize()).thenReturn(300);
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(503, "unavailable"));
        transport.enqueue(response(503, "still unavailable"));
        NormalizationMaintenanceClient client = client(transport);

        assertThatThrownBy(() -> client.resetAllNormalization("corr-sanitize"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("normalization-worker maintenance failed: upstream status 503");
    }

    private NormalizationMaintenanceClient client(FakeTransport transport) {
        return new NormalizationMaintenanceClient(
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
