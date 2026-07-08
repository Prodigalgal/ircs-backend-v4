package com.prodigalgal.ircs.common.outbound;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

public class ApacheHttpClient5OutboundTransport implements OutboundTransport {

    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "upgrade");

    @Override
    public OutboundHttpResponse send(OutboundHttpRequest request) throws IOException {
        OutboundHttpPolicy policy = request.policy();
        Timeout timeout = Timeout.ofMilliseconds(Math.max(1, policy.timeout().toMillis()));
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PolicyAwareApacheDnsResolver(policy))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build())
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(4)
                .build();
        RequestConfig requestConfig = requestConfig(policy, timeout);
        HttpUriRequestBase apacheRequest = new HttpUriRequestBase(request.method(), request.uri());
        apacheRequest.setConfig(requestConfig);
        applyVersion(apacheRequest, policy.httpProtocol());
        request.headers().forEach((name, value) -> {
            if (!isRestrictedHeader(name)) {
                apacheRequest.addHeader(name, value);
            }
        });
        if (request.body().length > 0) {
            apacheRequest.setEntity(new ByteArrayEntity(request.body(), ContentType.APPLICATION_OCTET_STREAM));
        }

        var builder = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()
                .disableRedirectHandling();
        OutboundProxy proxy = policy.proxy();
        if (proxy.enabled() && proxy.hasCredentials()) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    new AuthScope(proxy.host(), proxy.port()),
                    new UsernamePasswordCredentials(
                            proxy.username(),
                            (proxy.password() == null ? "" : proxy.password()).toCharArray()));
            builder.setDefaultCredentialsProvider(credentialsProvider);
        }
        try (CloseableHttpClient client = builder.build()) {
            return client.execute(apacheRequest, this::toResponse);
        }
    }

    private RequestConfig requestConfig(OutboundHttpPolicy policy, Timeout timeout) throws OutboundHttpException {
        RequestConfig.Builder builder = RequestConfig.custom()
                .setConnectionRequestTimeout(timeout)
                .setConnectTimeout(timeout)
                .setResponseTimeout(timeout)
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(policy.decompressGzip());
        OutboundProxy proxy = policy.proxy();
        proxy.validate();
        if (proxy.enabled()) {
            builder.setProxy(new HttpHost("http", proxy.host(), proxy.port()));
        }
        return builder.build();
    }

    private void applyVersion(HttpUriRequestBase request, String protocol) {
        if ("HTTP_1_0".equalsIgnoreCase(protocol)) {
            request.setVersion(HttpVersion.HTTP_1_0);
        } else if ("HTTP_1_1".equalsIgnoreCase(protocol)) {
            request.setVersion(HttpVersion.HTTP_1_1);
        } else if ("HTTP_2".equalsIgnoreCase(protocol)) {
            request.setVersion(HttpVersion.HTTP_2);
        }
    }

    private OutboundHttpResponse toResponse(ClassicHttpResponse response) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Header header : response.getHeaders()) {
            headers.computeIfAbsent(header.getName(), ignored -> new java.util.ArrayList<>())
                    .add(header.getValue());
        }
        byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
        return new OutboundHttpResponse(response.getCode(), headers, body);
    }

    private boolean isRestrictedHeader(String name) {
        return name != null && RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }
}
