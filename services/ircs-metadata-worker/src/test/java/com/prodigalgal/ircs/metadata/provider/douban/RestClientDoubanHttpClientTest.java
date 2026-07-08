package com.prodigalgal.ircs.metadata.provider.douban;

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

class RestClientDoubanHttpClientTest {

    private final FakeResolver resolver = new FakeResolver();
    private final FakeTransport transport = new FakeTransport();
    private final DoubanProviderProperties properties = new DoubanProviderProperties();
    private RestClientDoubanHttpClient client;

    @BeforeEach
    void setUp() {
        properties.setRequestTimeout(Duration.ofSeconds(4));
        properties.setDefaultUserAgent("ircs-test-douban");
        client = RestClientDoubanHttpClient.forTest(
                new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport),
                properties);
    }

    @Test
    void mapsSuccessfulEmptyAndMissingResponses() {
        transport.enqueue(response(200, "[{\"id\":\"1291843\"}]"));
        transport.enqueue(response(200, " "));
        transport.enqueue(response(404, "missing"));

        assertThat(client.getJson(URI.create("https://movie.douban.com/j/subject_suggest?q=The%20Matrix"), Optional.empty()))
                .contains("[{\"id\":\"1291843\"}]");
        assertThat(client.getJson(URI.create("https://movie.douban.com/j/subject_suggest?q=empty"), Optional.empty()))
                .isEmpty();
        assertThat(client.getJson(URI.create("https://movie.douban.com/j/subject_suggest?q=missing"), Optional.empty()))
                .isEmpty();
        assertThat(transport.requests).hasSize(3);
        assertThat(transport.requests.getFirst().headers())
                .containsEntry("User-Agent", "ircs-test-douban")
                .containsEntry("Accept-Encoding", "gzip, deflate");
    }

    @Test
    void appliesCredentialHeadersWithoutPrintingSecrets() {
        transport.enqueue(response(200, "[]"));
        DoubanCredential credential = new DoubanCredential(
                UUID.randomUUID(),
                "dbcl2=secret",
                "credential-ua",
                1,
                "MINUTE",
                0);

        assertThat(client.getJson(
                URI.create("https://movie.douban.com/j/subject_suggest?q=The%20Matrix"),
                Optional.of(credential))).contains("[]");

        assertThat(transport.requests.getFirst().headers())
                .containsEntry("User-Agent", "credential-ua")
                .containsEntry("Cookie", "dbcl2=secret");
    }

    @Test
    void mapsRetryableAndTerminalStatuses() {
        transport.enqueue(response(429, "rate"));
        transport.enqueue(response(429, "rate"));
        transport.enqueue(response(429, "rate"));
        assertThatThrownBy(() -> client.getJson(URI.create("https://movie.douban.com/j/subject_suggest?q=rate"), Optional.empty()))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .extracting("errorCode")
                .isEqualTo("RATE_LIMITED");

        transport.enqueue(response(503, "down"));
        transport.enqueue(response(503, "down"));
        transport.enqueue(response(503, "down"));
        assertThatThrownBy(() -> client.getJson(URI.create("https://movie.douban.com/j/subject_suggest?q=down"), Optional.empty()))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .extracting("errorCode")
                .isEqualTo("UPSTREAM_UNAVAILABLE");

        transport.enqueue(response(403, "forbidden"));
        assertThatThrownBy(() -> client.getJson(URI.create("https://movie.douban.com/j/subject_suggest?q=blocked"), Optional.empty()))
                .isInstanceOf(MetadataProviderTerminalException.class)
                .extracting("errorCode")
                .isEqualTo("DOUBAN_FORBIDDEN");
    }

    @Test
    void blocksPrivateDoubanUrlBeforeTransport() {
        resolver.address = "169.254.169.254";

        assertThatThrownBy(() -> client.getJson(URI.create("https://movie.douban.com/j/subject_suggest?q=blocked"), Optional.empty()))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .extracting("errorCode")
                .isEqualTo("DOUBAN_OUTBOUND_UNAVAILABLE");
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
