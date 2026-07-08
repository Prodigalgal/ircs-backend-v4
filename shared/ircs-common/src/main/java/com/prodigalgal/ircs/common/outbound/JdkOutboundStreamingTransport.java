package com.prodigalgal.ircs.common.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class JdkOutboundStreamingTransport implements OutboundStreamingTransport {

    private static final Set<String> JDK_RESTRICTED_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "upgrade");

    private final JdkHttpClientResolver httpClientResolver;

    public JdkOutboundStreamingTransport() {
        this(null, null);
    }

    public JdkOutboundStreamingTransport(Duration connectTimeout) {
        this(connectTimeout, null);
    }

    public JdkOutboundStreamingTransport(HttpClient httpClient) {
        this(null, httpClient);
    }

    private JdkOutboundStreamingTransport(Duration connectTimeout, HttpClient fixedHttpClient) {
        this.httpClientResolver = new JdkHttpClientResolver(connectTimeout, fixedHttpClient);
    }

    @Override
    public OutboundStreamingHttpResponse send(OutboundHttpRequest request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.policy().timeout());
        request.headers().forEach((name, value) -> {
            if (!isRestrictedHeader(name)) {
                builder.header(name, value);
            }
        });
        byte[] body = request.body();
        builder.method(request.method(), body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<InputStream> response = httpClient(request)
                .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        Map<String, List<String>> headers = response.headers().map();
        return new OutboundStreamingHttpResponse(response.statusCode(), headers, response.body());
    }

    private HttpClient httpClient(OutboundHttpRequest request) throws OutboundHttpException {
        return httpClientResolver.resolve(request);
    }

    private boolean isRestrictedHeader(String name) {
        return name != null && JDK_RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }
}
