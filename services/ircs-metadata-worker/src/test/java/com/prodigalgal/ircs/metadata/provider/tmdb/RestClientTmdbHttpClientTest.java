package com.prodigalgal.ircs.metadata.provider.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prodigalgal.ircs.common.outbound.OutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.metadata.provider.application.MetadataProviderTrafficLimiter;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderTerminalException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RestClientTmdbHttpClientTest {

    private final FakeResolver resolver = new FakeResolver();
    private final FakeTransport transport = new FakeTransport();
    private final RestClientTmdbHttpClient client = RestClientTmdbHttpClient.forTest(
            new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport),
            Duration.ofSeconds(10));

    @Test
    void mapsSuccessfulAndEmptyResponsesLikeExistingTmdbClient() {
        transport.enqueue(response(200, "{\"id\":603}"));
        transport.enqueue(response(200, " "));
        transport.enqueue(response(404, "{\"status_code\":34}"));

        assertThat(client.getJson(URI.create("https://api.themoviedb.org/3/movie/603")))
                .contains("{\"id\":603}");
        assertThat(client.getJson(URI.create("https://api.themoviedb.org/3/movie/empty"))).isEmpty();
        assertThat(client.getJson(URI.create("https://api.themoviedb.org/3/movie/missing"))).isEmpty();

        assertThat(transport.requests).hasSize(3);
        assertThat(transport.requests.getFirst().headers())
                .containsKey("User-Agent")
                .containsEntry("Accept-Encoding", "gzip, deflate");
    }

    @Test
    void mapsRetryableAndTerminalStatusesWithoutChangingWorkerSemantics() {
        transport.enqueue(response(429, "rate"));
        transport.enqueue(response(429, "rate"));
        transport.enqueue(response(429, "rate"));
        assertThatThrownBy(() -> client.getJson(URI.create("https://api.themoviedb.org/3/search/movie")))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .extracting("errorCode")
                .isEqualTo("RATE_LIMITED");

        transport.enqueue(response(503, "down"));
        transport.enqueue(response(503, "down"));
        transport.enqueue(response(503, "down"));
        assertThatThrownBy(() -> client.getJson(URI.create("https://api.themoviedb.org/3/movie/603")))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .extracting("errorCode")
                .isEqualTo("UPSTREAM_UNAVAILABLE");

        transport.enqueue(response(401, "unauthorized"));
        assertThatThrownBy(() -> client.getJson(URI.create("https://api.themoviedb.org/3/movie/603")))
                .isInstanceOf(MetadataProviderTerminalException.class)
                .extracting("errorCode")
                .isEqualTo("HTTP_401");
    }

    @Test
    void blocksPrivateTmdbUrlBeforeTransport() {
        resolver.address = "169.254.169.254";

        assertThatThrownBy(() -> client.getJson(URI.create("https://api.themoviedb.org/3/movie/603")))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .extracting("errorCode")
                .isEqualTo("TMDB_OUTBOUND_UNAVAILABLE");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void sharedOutboundRetriesTimeoutsAndDecompressesGzip() throws Exception {
        transport.enqueue(new HttpTimeoutException("timeout"));
        transport.enqueue(response(500, "first failure"));
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of("Content-Encoding", List.of("gzip")),
                gzip("{\"ok\":true}")));

        assertThat(client.getJson(URI.create("https://api.themoviedb.org/3/movie/603")))
                .contains("{\"ok\":true}");
        assertThat(transport.requests).hasSize(3);
    }

    @Test
    void doesNotFollowRedirectAndMapsItAsTerminalStatus() {
        transport.enqueue(new OutboundHttpResponse(
                302,
                Map.of("Location", List.of("https://api.themoviedb.org/3/next")),
                new byte[0]));

        assertThatThrownBy(() -> client.getJson(URI.create("https://api.themoviedb.org/3/movie/603")))
                .isInstanceOf(MetadataProviderTerminalException.class)
                .extracting("errorCode")
                .isEqualTo("HTTP_302");
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void reservesProviderTrafficSlotBeforeOutboundRequest() {
        MetadataProviderTrafficLimiter trafficLimiter = Mockito.mock(MetadataProviderTrafficLimiter.class);
        RestClientTmdbHttpClient limitedClient = RestClientTmdbHttpClient.forTest(
                new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport),
                Duration.ofSeconds(10),
                trafficLimiter);
        transport.enqueue(response(200, "{\"id\":603}"));

        assertThat(limitedClient.getJson(URI.create("https://api.themoviedb.org/3/movie/603")))
                .contains("{\"id\":603}");

        Mockito.verify(trafficLimiter).acquireProviderSlot("TMDB");
        assertThat(transport.requests).hasSize(1);
    }

    private OutboundHttpResponse response(int status, String body) {
        return new OutboundHttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] gzip(String body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
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
