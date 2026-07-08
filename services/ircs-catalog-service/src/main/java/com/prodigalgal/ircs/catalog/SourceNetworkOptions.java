package com.prodigalgal.ircs.catalog;

import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import java.time.Duration;
import java.util.Locale;
import org.springframework.util.StringUtils;

record SourceNetworkOptions(
        String transportMode,
        String httpProtocol,
        String ipVersionPolicy,
        String dnsResolverType,
        String dnsResolverEndpoint,
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        String userAgent) {

    static final String HTTP_AUTO = "AUTO";
    static final String TRANSPORT_AUTO = "AUTO";
    static final String IP_AUTO = "AUTO";
    static final String DNS_SYSTEM = "SYSTEM";
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int MAX_TIMEOUT_MS = 120_000;

    static SourceNetworkOptions from(DataSourceAdminRequest request) {
        return from(request, true);
    }

    static SourceNetworkOptions from(DataSourceAdminRequest request, boolean defaults) {
        return new SourceNetworkOptions(
                normalizeEnum(request.transportMode(), defaults ? TRANSPORT_AUTO : null, "AUTO", "JDK", "APACHE_HC5", "CURL_NATIVE"),
                normalizeEnum(request.httpProtocol(), defaults ? HTTP_AUTO : null, "AUTO", "HTTP_1_1", "HTTP_2", "HTTP_1_0"),
                normalizeEnum(request.ipVersionPolicy(), defaults ? IP_AUTO : null, "AUTO", "IPV4_ONLY", "IPV6_ONLY", "IPV4_PREFERRED", "IPV6_PREFERRED"),
                normalizeEnum(request.dnsResolverType(), defaults ? DNS_SYSTEM : null, "SYSTEM", "DOH", "DOT"),
                trimToNull(request.dnsResolverEndpoint()),
                normalizeTimeout(request.connectTimeoutMs()),
                normalizeTimeout(request.readTimeoutMs()),
                trimToNull(request.userAgent()));
    }

    static SourceNetworkOptions from(FetchSampleRequest request) {
        return new SourceNetworkOptions(
                normalizeEnum(request.transportMode(), TRANSPORT_AUTO, "AUTO", "JDK", "APACHE_HC5", "CURL_NATIVE"),
                normalizeEnum(request.httpProtocol(), HTTP_AUTO, "AUTO", "HTTP_1_1", "HTTP_2", "HTTP_1_0"),
                normalizeEnum(request.ipVersionPolicy(), IP_AUTO, "AUTO", "IPV4_ONLY", "IPV6_ONLY", "IPV4_PREFERRED", "IPV6_PREFERRED"),
                normalizeEnum(request.dnsResolverType(), DNS_SYSTEM, "SYSTEM", "DOH", "DOT"),
                trimToNull(request.dnsResolverEndpoint()),
                normalizeTimeout(request.connectTimeoutMs()),
                normalizeTimeout(request.readTimeoutMs()),
                trimToNull(request.userAgent()));
    }

    static SourceNetworkOptions from(DataSourceRead dataSource) {
        return new SourceNetworkOptions(
                normalizeEnum(dataSource.transportMode(), TRANSPORT_AUTO, "AUTO", "JDK", "APACHE_HC5", "CURL_NATIVE"),
                normalizeEnum(dataSource.httpProtocol(), HTTP_AUTO, "AUTO", "HTTP_1_1", "HTTP_2", "HTTP_1_0"),
                normalizeEnum(dataSource.ipVersionPolicy(), IP_AUTO, "AUTO", "IPV4_ONLY", "IPV6_ONLY", "IPV4_PREFERRED", "IPV6_PREFERRED"),
                normalizeEnum(dataSource.dnsResolverType(), DNS_SYSTEM, "SYSTEM", "DOH", "DOT"),
                trimToNull(dataSource.dnsResolverEndpoint()),
                normalizeTimeout(dataSource.connectTimeoutMs()),
                normalizeTimeout(dataSource.readTimeoutMs()),
                trimToNull(dataSource.userAgent()));
    }

    OutboundHttpPolicy toPolicy() {
        OutboundHttpPolicy policy = OutboundHttpPolicy.publicFetch(timeout())
                .withTransportMode(transportMode)
                .withHttpProtocol(httpProtocol)
                .withResolution(ipVersionPolicy, dnsResolverType, dnsResolverEndpoint);
        if (StringUtils.hasText(userAgent)) {
            policy = policy.withUserAgent(userAgent);
        }
        return policy;
    }

    Duration timeout() {
        int timeoutMs = readTimeoutMs == null ? DEFAULT_TIMEOUT_MS : readTimeoutMs;
        return Duration.ofMillis(timeoutMs);
    }

    private static Integer normalizeTimeout(Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("Source timeout must be between 1 and " + MAX_TIMEOUT_MS + " ms");
        }
        return value;
    }

    private static String normalizeEnum(String value, String fallback, String... allowed) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException("Unsupported source network option: " + value);
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
