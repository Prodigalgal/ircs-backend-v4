package com.prodigalgal.ircs.content.video.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.content.config.ContentConfigValues;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchRequest;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AdminVideoSearchClientTest {

    @Test
    void postsRawDiscoveryRequestAndParsesResult() throws Exception {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        UUID id = UUID.randomUUID();
        AdminVideoSearchResult body = new AdminVideoSearchResult(
                List.of(id),
                10,
                AdminVideoSearchResult.TotalRelation.EXACT,
                3,
                "elasticsearch");
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new OutboundHttpResponse(200, Map.of(), mapper.writeValueAsBytes(body)));
        ContentConfigValues configValues = configValues(true);
        AdminVideoSearchClient client = client(mapper, transport, configValues);

        var result = client.searchRawIds(new AdminVideoSearchRequest(
                0,
                20,
                "updatedAt",
                "DESC",
                null,
                null,
                null,
                null,
                "PENDING",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        assertTrue(result.isPresent());
        assertEquals(List.of(id), result.get().ids());
        assertEquals("/internal/v1/search/admin/videos/raw/ids", transport.requests.getFirst().uri().getPath());
        assertEquals("application/json", transport.requests.getFirst().headers().get("Content-Type"));
        assertTrue(new String(transport.requests.getFirst().body(), StandardCharsets.UTF_8)
                .contains("\"normalizationStatus\":\"PENDING\""));
    }

    @Test
    void returnsEmptyWhenDisabledOrUpstreamFails() {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        FakeTransport disabledTransport = new FakeTransport();
        AdminVideoSearchClient disabledClient = client(mapper, disabledTransport, configValues(false));

        assertTrue(disabledClient.searchRawIds(emptyRequest()).isEmpty());
        assertEquals(0, disabledTransport.requests.size());

        FakeTransport failingTransport = new FakeTransport();
        failingTransport.responses.add(new OutboundHttpResponse(400, Map.of(), new byte[0]));
        AdminVideoSearchClient failingClient = client(mapper, failingTransport, configValues(true));

        assertTrue(failingClient.searchRawIds(emptyRequest()).isEmpty());
        assertEquals(1, failingTransport.requests.size());
    }

    private AdminVideoSearchRequest emptyRequest() {
        return new AdminVideoSearchRequest(
                0,
                20,
                "updatedAt",
                "DESC",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private AdminVideoSearchClient client(
            JsonMapper mapper,
            FakeTransport transport,
            ContentConfigValues configValues) {
        return new AdminVideoSearchClient(
                mapper,
                provider(new OutboundHttpClient(new OutboundUrlPolicy(host -> {
                    throw new AssertionError("internal service calls must not resolve public DNS");
                }), transport)),
                configValues,
                Duration.ofSeconds(1),
                "content-service",
                "token",
                "search:sync");
    }

    private ContentConfigValues configValues(boolean enabled) {
        ContentConfigValues configValues = org.mockito.Mockito.mock(ContentConfigValues.class);
        when(configValues.adminVideoSearchEsEnabled()).thenReturn(enabled);
        when(configValues.searchBaseUrl()).thenReturn("http://ircs-search-service:8080");
        return configValues;
    }

    private ObjectProvider<OutboundHttpClient> provider(OutboundHttpClient client) {
        return new ObjectProvider<>() {
            @Override
            public OutboundHttpClient getObject() {
                return client;
            }
        };
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
