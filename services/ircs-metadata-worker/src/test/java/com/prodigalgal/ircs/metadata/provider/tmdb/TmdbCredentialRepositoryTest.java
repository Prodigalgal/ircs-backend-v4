package com.prodigalgal.ircs.metadata.provider.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import com.prodigalgal.ircs.metadata.provider.credential.MetadataCredentialServiceProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class TmdbCredentialRepositoryTest {

    private final FakeResolver resolver = new FakeResolver();
    private final FakeTransport transport = new FakeTransport();
    private final MetadataCredentialServiceProperties properties = new MetadataCredentialServiceProperties();
    private final TmdbCredentialRepository repository = TmdbCredentialRepository.forTest(
            new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport),
            new ObjectMapper(),
            properties);

    @Test
    void fetchesCredentialsFromCredentialService() {
        properties.setBaseUrl("http://credential-service");
        properties.setLeaseLimit(2);
        properties.setToken("internal-token");
        properties.setRequestTimeout(Duration.ofSeconds(4));
        transport.enqueue(response(200, """
                        [{
                          "id":"de0a6fd9-f07d-4201-bf92-279b6c9f099d",
                          "provider":"TMDB",
                          "name":"dev",
                          "secretPayload":{"api_key":"secret-key"},
                          "priority":9,
                          "rateLimit":30,
                          "rateLimitUnit":"MINUTE"
                        }]
                        """));

        List<TmdbCredential> credentials = repository.findEnabled();

        assertEquals(1, credentials.size());
        assertEquals("de0a6fd9-f07d-4201-bf92-279b6c9f099d", credentials.getFirst().id().toString());
        assertEquals("secret-key", credentials.getFirst().apiKey());
        assertEquals(30, credentials.getFirst().rateLimit());
        OutboundHttpRequest request = transport.requests.getFirst();
        assertThat(request.uri()).isEqualTo(URI.create(
                "http://credential-service/internal/credentials/providers/TMDB/leases?requiredPayloadKey=api_key&limit=2"));
        assertThat(request.headers())
                .containsEntry("X-IRCS-SERVICE-ID", "metadata-worker")
                .containsEntry("X-IRCS-SERVICE-TOKEN", "internal-token")
                .containsEntry("X-IRCS-SERVICE-SCOPES", "credential:lease")
                .doesNotContainKey("X-IRCS-INTERNAL-TOKEN");
        assertThat(request.policy().type().name()).isEqualTo("INTERNAL_SERVICE");
        assertThat(request.policy().timeout()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void mapsCredentialServiceFailuresToRetryableProviderFailure() {
        properties.setBaseUrl("http://credential-service");
        properties.setToken("internal-token");
        transport.enqueue(response(503, "temporary unavailable"));
        transport.enqueue(response(503, "temporary unavailable"));

        MetadataProviderRetryableException ex = assertThrows(
                MetadataProviderRetryableException.class,
                repository::findEnabled);

        assertEquals("CREDENTIAL_SERVICE_UNAVAILABLE", ex.getErrorCode());
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    void allowsInternalLocalhostBaseUrlButRejectsUserInfoBeforeTransport() {
        properties.setBaseUrl("http://localhost:8080");
        transport.enqueue(response(200, "[]"));

        assertThat(repository.findEnabled()).isEmpty();
        assertThat(transport.requests.getFirst().uri()).isEqualTo(URI.create(
                "http://localhost:8080/internal/credentials/providers/TMDB/leases?requiredPayloadKey=api_key&limit=20"));

        properties.setBaseUrl("http://token@credential-service");
        assertThatThrownBy(repository::findEnabled)
                .isInstanceOf(MetadataProviderRetryableException.class)
                .hasFieldOrPropertyWithValue("errorCode", "CREDENTIAL_SERVICE_UNAVAILABLE");
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void mapsIoFailureToRetryableProviderFailure() {
        properties.setBaseUrl("http://credential-service");
        transport.enqueue(new IOException("connection refused"));
        transport.enqueue(new IOException("connection refused"));

        MetadataProviderRetryableException ex = assertThrows(
                MetadataProviderRetryableException.class,
                repository::findEnabled);

        assertEquals("CREDENTIAL_SERVICE_UNAVAILABLE", ex.getErrorCode());
    }

    private OutboundHttpResponse response(int status, String body) {
        return new OutboundHttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeResolver implements OutboundAddressResolver {

        @Override
        public List<InetAddress> resolve(String host) {
            throw new AssertionError("INTERNAL_SERVICE must not perform public DNS SSRF resolution");
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
