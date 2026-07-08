package com.prodigalgal.ircs.common.outbound;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

final class JdkHttpClientResolver {

    private final Duration connectTimeout;
    private final HttpClient fixedHttpClient;
    private volatile HttpClient sharedDirectClient;
    private volatile Duration sharedDirectClientTimeout;

    JdkHttpClientResolver(Duration connectTimeout, HttpClient fixedHttpClient) {
        this.connectTimeout = connectTimeout;
        this.fixedHttpClient = fixedHttpClient;
    }

    HttpClient resolve(OutboundHttpRequest request) throws OutboundHttpException {
        if (fixedHttpClient != null) {
            return fixedHttpClient;
        }
        Duration timeout = connectTimeout == null ? request.policy().timeout() : connectTimeout;
        OutboundProxy proxy = request.policy().proxy();
        proxy.validate();
        if (!proxy.enabled()) {
            return sharedDirectClient(timeout);
        }
        return buildClient(timeout, proxy);
    }

    private HttpClient sharedDirectClient(Duration timeout) {
        HttpClient existing = sharedDirectClient;
        if (existing != null && timeout.equals(sharedDirectClientTimeout)) {
            return existing;
        }
        synchronized (this) {
            if (sharedDirectClient == null || !timeout.equals(sharedDirectClientTimeout)) {
                sharedDirectClient = buildClient(timeout, OutboundProxy.disabled());
                sharedDirectClientTimeout = timeout;
            }
            return sharedDirectClient;
        }
    }

    private HttpClient buildClient(Duration timeout, OutboundProxy proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout);
        if (proxy.enabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
            if (proxy.hasCredentials()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                                proxy.username(),
                                (proxy.password() == null ? "" : proxy.password()).toCharArray());
                    }
                });
            }
        }
        return builder.build();
    }
}
