package com.prodigalgal.ircs.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prodigalgal.ircs.common.outbound.OutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class CatalogFetchSampleClientTest {

    private final FakeResolver resolver = new FakeResolver();
    private final FakeTransport transport = new FakeTransport();
    private final CatalogFetchSampleClient client =
            CatalogFetchSampleClient.forTest(new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport));

    @Test
    void returnsBodyFor2xxAndNullForNon2xxWithoutRealNetwork() throws Exception {
        resolver.address = "93.184.216.34";
        transport.enqueue(response(200, "{\"ok\":true}"));
        transport.enqueue(response(200, "<!DOCTYPE html><html></html>"));
        transport.enqueue(response(404, "missing"));

        assertThat(client.get("https://example.test/api")).isEqualTo("{\"ok\":true}");
        assertThat(client.get("https://example.test/html")).isNull();
        assertThat(client.get("https://example.test/missing")).isNull();
        assertThat(transport.requests).hasSize(3);
    }

    @Test
    void blocksLoopbackBeforeTransport() {
        resolver.address = "127.0.0.1";

        assertThatThrownBy(() -> client.get("https://example.test/api"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("blocked");
        assertThat(transport.requests).isEmpty();
    }

    private OutboundHttpResponse response(int status, String body) {
        return new OutboundHttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeResolver implements OutboundAddressResolver {

        private String address;

        @Override
        public List<InetAddress> resolve(String host) throws java.net.UnknownHostException {
            return List.of(InetAddress.getByName(address));
        }
    }

    private static final class FakeTransport implements OutboundTransport {

        private final List<OutboundHttpRequest> requests = new ArrayList<>();
        private final Queue<OutboundHttpResponse> responses = new ArrayDeque<>();

        void enqueue(OutboundHttpResponse response) {
            responses.add(response);
        }

        @Override
        public OutboundHttpResponse send(OutboundHttpRequest request) {
            requests.add(request);
            return responses.remove();
        }
    }
}
