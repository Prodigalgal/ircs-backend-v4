package com.prodigalgal.ircs.metadata.provider.rt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prodigalgal.ircs.common.outbound.OutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderTerminalException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestClientRottenTomatoesHttpClientTest {

    private final FakeResolver resolver = new FakeResolver();
    private final FakeTransport transport = new FakeTransport();
    private final RottenTomatoesProviderProperties properties = new RottenTomatoesProviderProperties();
    private RestClientRottenTomatoesHttpClient client;

    @BeforeEach
    void setUp() {
        properties.setRequestTimeout(Duration.ofSeconds(4));
        properties.setDefaultUserAgent("ircs-test-rt");
        client = RestClientRottenTomatoesHttpClient.forTest(
                new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport),
                properties);
    }

    @Test
    void mapsSuccessfulEmptyAndMissingResponses() {
        transport.enqueue(response(200, "<html>ok</html>"));
        transport.enqueue(response(200, " "));
        transport.enqueue(response(404, "missing"));

        assertThat(client.getHtml(URI.create("https://www.rottentomatoes.com/search?search=The%20Matrix"), Optional.empty()))
                .contains("<html>ok</html>");
        assertThat(client.getHtml(URI.create("https://www.rottentomatoes.com/search?search=empty"), Optional.empty()))
                .isEmpty();
        assertThat(client.getHtml(URI.create("https://www.rottentomatoes.com/search?search=missing"), Optional.empty()))
                .isEmpty();
        assertThat(transport.requests).hasSize(3);
        assertThat(transport.requests.getFirst().headers())
                .containsEntry("User-Agent", "ircs-test-rt")
                .containsEntry("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .containsEntry("Accept-Encoding", "gzip, deflate");
    }

    @Test
    void appliesCredentialHeadersWithoutPrintingSecrets() {
        transport.enqueue(response(200, "<html></html>"));
        RottenTomatoesCredential credential = new RottenTomatoesCredential(
                UUID.randomUUID(),
                "rt=secret",
                "credential-ua",
                1,
                "MINUTE",
                0);

        assertThat(client.getHtml(
                URI.create("https://www.rottentomatoes.com/search?search=The%20Matrix"),
                Optional.of(credential))).contains("<html></html>");

        assertThat(transport.requests.getFirst().headers())
                .containsEntry("User-Agent", "credential-ua")
                .containsEntry("Cookie", "rt=secret");
    }

    @Test
    void mapsRetryableAndTerminalStatuses() {
        transport.enqueue(response(429, "rate"));
        transport.enqueue(response(429, "rate"));
        transport.enqueue(response(429, "rate"));
        assertThatThrownBy(() -> client.getHtml(URI.create("https://www.rottentomatoes.com/search?search=rate"), Optional.empty()))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .extracting("errorCode")
                .isEqualTo("RATE_LIMITED");

        transport.enqueue(response(503, "down"));
        transport.enqueue(response(503, "down"));
        transport.enqueue(response(503, "down"));
        assertThatThrownBy(() -> client.getHtml(URI.create("https://www.rottentomatoes.com/search?search=down"), Optional.empty()))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .extracting("errorCode")
                .isEqualTo("UPSTREAM_UNAVAILABLE");

        transport.enqueue(response(403, "forbidden"));
        assertThatThrownBy(() -> client.getHtml(URI.create("https://www.rottentomatoes.com/search?search=blocked"), Optional.empty()))
                .isInstanceOf(MetadataProviderTerminalException.class)
                .extracting("errorCode")
                .isEqualTo("RT_FORBIDDEN");
    }

    @Test
    void blocksPrivateRtUrlBeforeTransport() {
        resolver.address = "169.254.169.254";

        assertThatThrownBy(() -> client.getHtml(URI.create("https://www.rottentomatoes.com/search?search=blocked"), Optional.empty()))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .extracting("errorCode")
                .isEqualTo("RT_OUTBOUND_UNAVAILABLE");
        assertThat(transport.requests).isEmpty();
    }

    private OutboundHttpResponse response(int status, String body) {
        return new OutboundHttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeResolver implements OutboundAddressResolver {

        private String address = "93.184.216.34";

        @Override
        public List<InetAddress> resolve(String host) throws java.net.UnknownHostException {
            return List.of(InetAddress.getByName(address));
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
            Object response = responses.remove();
            if (response instanceof IOException ex) {
                throw ex;
            }
            return (OutboundHttpResponse) response;
        }
    }
}
