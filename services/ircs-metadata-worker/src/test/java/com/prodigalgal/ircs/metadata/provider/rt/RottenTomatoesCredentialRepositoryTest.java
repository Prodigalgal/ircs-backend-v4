package com.prodigalgal.ircs.metadata.provider.rt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
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

class RottenTomatoesCredentialRepositoryTest {

    private final FakeResolver resolver = new FakeResolver();
    private final FakeTransport transport = new FakeTransport();
    private final MetadataCredentialServiceProperties properties = new MetadataCredentialServiceProperties();
    private final RottenTomatoesCredentialRepository repository = RottenTomatoesCredentialRepository.forTest(
            new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport),
            new ObjectMapper(),
            properties);

    @Test
    void fetchesOptionalCredentialFromCredentialService() {
        properties.setBaseUrl("http://credential-service");
        properties.setLeaseLimit(2);
        properties.setToken("internal-token");
        properties.setRequestTimeout(Duration.ofSeconds(4));
        transport.enqueue(response(200, """
                        [{
                          "id":"de0a6fd9-f07d-4201-bf92-279b6c9f099d",
                          "provider":"ROTTEN_TOMATOES",
                          "name":"dev",
                          "secretPayload":{"cookie":"rt=secret","user_agent":"ua"},
                          "priority":9,
                          "rateLimit":1,
                          "rateLimitUnit":"MINUTE"
                        }]
                        """));

        var credential = repository.findPreferred();

        assertThat(credential).isPresent();
        assertThat(credential.orElseThrow().cookie()).isEqualTo("rt=secret");
        OutboundHttpRequest request = transport.requests.getFirst();
        assertThat(request.uri()).isEqualTo(URI.create(
                "http://credential-service/internal/credentials/providers/ROTTEN_TOMATOES/leases?limit=2"));
        assertThat(request.headers())
                .containsEntry("X-IRCS-SERVICE-ID", "metadata-worker")
                .containsEntry("X-IRCS-SERVICE-TOKEN", "internal-token")
                .containsEntry("X-IRCS-SERVICE-SCOPES", "credential:lease")
                .doesNotContainKey("X-IRCS-INTERNAL-TOKEN");
        assertThat(request.policy().type().name()).isEqualTo("INTERNAL_SERVICE");
    }

    @Test
    void returnsEmptyWhenCredentialIsAbsentOrServiceUnavailable() {
        properties.setBaseUrl("http://credential-service");
        transport.enqueue(response(200, "[]"));
        assertThat(repository.findPreferred()).isEmpty();

        transport.enqueue(response(503, "temporary unavailable"));
        transport.enqueue(response(503, "temporary unavailable"));
        assertThat(repository.findPreferred()).isEmpty();

        transport.enqueue(new IOException("connection refused"));
        transport.enqueue(new IOException("connection refused"));
        assertThat(repository.findPreferred()).isEmpty();
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
