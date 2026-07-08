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

class AggregationMaintenanceClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OpsConfigValues configValues = org.mockito.Mockito.mock(OpsConfigValues.class);

    @BeforeEach
    void setUp() {
        when(configValues.aggregationOwnerBaseUrl()).thenReturn("http://aggregation-worker:8080/");
        when(configValues.aggregationOwnerRequestTimeout()).thenReturn(Duration.ofSeconds(3));
        when(configValues.aggregationOwnerServiceId()).thenReturn("ops-service");
        when(configValues.aggregationOwnerServiceToken()).thenReturn("service-token");
        when(configValues.aggregationOwnerScopes()).thenReturn("aggregation:maintenance ops:maintenance");
        when(configValues.aggregationMaintenanceDevLimit()).thenReturn(7);
    }

    @Test
    void postsDirtyRecalculateToAggregationOwnerWithServiceIdentity() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, """
                {"taskName":"unified-recalculate","candidates":2,"processed":5,"entityIds":["%s","%s"]}
                """.formatted(first, second)));
        AggregationMaintenanceClient client = client(transport);

        MaintenanceRunResult result = client.recalculateDirtyUnified("corr-agg");

        assertThat(result.taskName()).isEqualTo("unified-recalculate");
        assertThat(result.selectedCount()).isEqualTo(2);
        assertThat(result.publishedCount()).isEqualTo(5);
        assertThat(result.entityIds()).containsExactly(first, second);

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString())
                .isEqualTo("http://aggregation-worker:8080/internal/v1/aggregation/unified-videos/recalculate-dirty?limit=7");
        assertThat(sent.policy().type().name()).isEqualTo("INTERNAL_SERVICE");
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("ops-aggregation-maintenance");
        assertThat(sent.headers())
                .containsEntry("Accept", "application/json")
                .containsEntry("X-Correlation-Id", "corr-agg")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_ID, "ops-service")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_TOKEN, "service-token")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_SCOPES, "aggregation:maintenance ops:maintenance");
    }

    @Test
    void postsAggregationResetPrepareToAggregationOwner() {
        UUID rawId = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, """
                {"taskName":"aggregation-reset","stepName":"prepare","rawVideoCount":8,"unifiedVideoCount":3,"bindingCount":5,"changedRows":21,"sampleRawVideoIds":["%s"]}
                """.formatted(rawId)));
        AggregationMaintenanceClient client = client(transport);

        MaintenanceRunResult result = client.prepareAggregationReset("corr-reset");

        assertThat(result.taskName()).isEqualTo("aggregation-reset.prepare");
        assertThat(result.selectedCount()).isEqualTo(8);
        assertThat(result.publishedCount()).isEqualTo(21);
        assertThat(result.entityIds()).containsExactly(rawId);

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString())
                .isEqualTo("http://aggregation-worker:8080/internal/v1/aggregation/reset/prepare?sampleLimit=7");
        assertThat(sent.headers())
                .containsEntry("Accept", "application/json")
                .containsEntry("X-Correlation-Id", "corr-reset")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_ID, "ops-service")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_TOKEN, "service-token")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_SCOPES, "aggregation:maintenance ops:maintenance");
    }

    @Test
    void postsPendingBackfillToAggregationOwner() {
        UUID rawId = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, """
                {"taskName":"aggregation-pending-backfill","candidates":1,"processed":1,"entityIds":["%s"]}
                """.formatted(rawId)));
        AggregationMaintenanceClient client = client(transport);

        MaintenanceRunResult result = client.enqueuePendingRawWork("corr-pending");

        assertThat(result.taskName()).isEqualTo("aggregation-pending-backfill");
        assertThat(result.selectedCount()).isEqualTo(1);
        assertThat(result.publishedCount()).isEqualTo(1);
        assertThat(result.entityIds()).containsExactly(rawId);
        assertThat(transport.requests.getFirst().uri().toString())
                .isEqualTo("http://aggregation-worker:8080/internal/v1/aggregation/raw-videos/enqueue-pending?limit=7");
    }

    @Test
    void postsCoverBackfillToAggregationOwner() {
        UUID unifiedId = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, """
                {"taskName":"aggregation-cover-backfill","candidates":1,"processed":1,"entityIds":["%s"]}
                """.formatted(unifiedId)));
        AggregationMaintenanceClient client = client(transport);

        MaintenanceRunResult result = client.backfillUnifiedCovers("corr-cover");

        assertThat(result.taskName()).isEqualTo("aggregation-cover-backfill");
        assertThat(result.selectedCount()).isEqualTo(1);
        assertThat(result.publishedCount()).isEqualTo(1);
        assertThat(result.entityIds()).containsExactly(unifiedId);
        assertThat(transport.requests.getFirst().uri().toString())
                .isEqualTo("http://aggregation-worker:8080/internal/v1/aggregation/unified-videos/backfill-covers?limit=7");
    }

    @Test
    void postsAdultAssessmentBackfillToAggregationOwner() {
        UUID unifiedId = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, """
                {"taskName":"aggregation-adult-assessment-backfill","candidates":1,"processed":1,"entityIds":["%s"]}
                """.formatted(unifiedId)));
        AggregationMaintenanceClient client = client(transport);

        MaintenanceRunResult result = client.backfillUnifiedAdultAssessments("corr-adult");

        assertThat(result.taskName()).isEqualTo("aggregation-adult-assessment-backfill");
        assertThat(result.selectedCount()).isEqualTo(1);
        assertThat(result.publishedCount()).isEqualTo(1);
        assertThat(result.entityIds()).containsExactly(unifiedId);

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString())
                .isEqualTo("http://aggregation-worker:8080/internal/v1/aggregation/unified-videos/backfill-adult-assessments?limit=7");
        assertThat(sent.headers())
                .containsEntry("Accept", "application/json")
                .containsEntry("X-Correlation-Id", "corr-adult")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_ID, "ops-service")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_TOKEN, "service-token")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_SCOPES, "aggregation:maintenance ops:maintenance");
    }

    @Test
    void postsAggregationResetMarkRawPendingToAggregationOwner() {
        UUID rawId = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(202, """
                {"taskName":"aggregation-reset","stepName":"mark-raw-pending","rawVideoCount":8,"unifiedVideoCount":0,"bindingCount":0,"changedRows":8,"sampleRawVideoIds":["%s"]}
                """.formatted(rawId)));
        AggregationMaintenanceClient client = client(transport);

        MaintenanceRunResult result = client.markAggregationResetRawPending("corr-reset");

        assertThat(result.taskName()).isEqualTo("aggregation-reset.mark-raw-pending");
        assertThat(result.selectedCount()).isEqualTo(8);
        assertThat(result.publishedCount()).isEqualTo(8);
        assertThat(result.entityIds()).containsExactly(rawId);

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString())
                .isEqualTo("http://aggregation-worker:8080/internal/v1/aggregation/reset/mark-raw-pending?sampleLimit=7");
    }

    @Test
    void non2xxResponseIsMappedToMaintenanceFailure() {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(503, "unavailable"));
        transport.enqueue(response(503, "still unavailable"));
        AggregationMaintenanceClient client = client(transport);

        assertThatThrownBy(() -> client.recalculateDirtyUnified("corr-agg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("aggregation-worker maintenance failed: upstream status 503");
    }

    private AggregationMaintenanceClient client(FakeTransport transport) {
        return new AggregationMaintenanceClient(
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
