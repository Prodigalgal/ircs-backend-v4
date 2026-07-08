package com.prodigalgal.ircs.common.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JdkHttpClientResolverTest {

    @Test
    void reusesDirectClientForSameConnectTimeout() throws Exception {
        JdkHttpClientResolver resolver = new JdkHttpClientResolver(Duration.ofSeconds(2), null);
        OutboundHttpRequest request = request(Duration.ofSeconds(5));

        HttpClient first = resolver.resolve(request);
        HttpClient second = resolver.resolve(request);

        assertThat(second).isSameAs(first);
    }

    @Test
    void rebuildsDirectClientWhenRequestTimeoutDrivesDifferentConnectTimeout() throws Exception {
        JdkHttpClientResolver resolver = new JdkHttpClientResolver(null, null);

        HttpClient first = resolver.resolve(request(Duration.ofSeconds(5)));
        HttpClient second = resolver.resolve(request(Duration.ofSeconds(6)));

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void fixedClientAlwaysWins() throws Exception {
        HttpClient fixed = HttpClient.newHttpClient();
        JdkHttpClientResolver resolver = new JdkHttpClientResolver(Duration.ofSeconds(2), fixed);

        assertThat(resolver.resolve(request(Duration.ofSeconds(5)))).isSameAs(fixed);
        assertThat(resolver.resolve(request(Duration.ofSeconds(6)))).isSameAs(fixed);
    }

    private static OutboundHttpRequest request(Duration timeout) {
        return new OutboundHttpRequest(
                URI.create("http://ircs-ops-service:8080/api/v1/dashboard/metrics"),
                "GET",
                java.util.Map.of(),
                OutboundHttpPolicy.apiGatewayProxy(timeout),
                new byte[0]);
    }
}
