package com.prodigalgal.ircs.common.outbound;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public record OutboundHttpPolicy(
        OutboundHttpPolicyType type,
        Duration timeout,
        int maxRetries,
        String userAgent,
        String httpProtocol,
        String transportMode,
        String ipVersionPolicy,
        String dnsResolverType,
        String dnsResolverEndpoint,
        boolean decompressGzip,
        Set<Integer> retryStatuses,
        List<String> metadataHosts,
        OutboundProxy proxy,
        boolean blockPrivateAddresses,
        OutboundCircuitBreakerConfig circuitBreaker,
        String circuitBreakerKey) {

    public OutboundHttpPolicy {
        if (type == null) {
            throw new IllegalArgumentException("Outbound policy type is required");
        }
        if (timeout == null) {
            throw new IllegalArgumentException("Outbound timeout is required");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Outbound maxRetries must be >= 0");
        }
        userAgent = hasText(userAgent) ? userAgent : OutboundUserAgents.defaultBrowser();
        httpProtocol = hasText(httpProtocol) ? httpProtocol.trim().toUpperCase(java.util.Locale.ROOT) : "AUTO";
        transportMode = hasText(transportMode) ? transportMode.trim().toUpperCase(java.util.Locale.ROOT) : "AUTO";
        ipVersionPolicy = hasText(ipVersionPolicy) ? ipVersionPolicy.trim().toUpperCase(java.util.Locale.ROOT) : "AUTO";
        dnsResolverType = hasText(dnsResolverType) ? dnsResolverType.trim().toUpperCase(java.util.Locale.ROOT) : "SYSTEM";
        dnsResolverEndpoint = hasText(dnsResolverEndpoint) ? dnsResolverEndpoint.trim() : null;
        retryStatuses = retryStatuses == null ? Set.of() : Set.copyOf(retryStatuses);
        metadataHosts = metadataHosts == null ? List.of() : List.copyOf(metadataHosts);
        proxy = proxy == null ? OutboundProxy.disabled() : proxy;
        circuitBreaker = circuitBreaker == null
                ? OutboundCircuitBreakerConfig.enabledFromEnvironment()
                : circuitBreaker;
        circuitBreakerKey = hasText(circuitBreakerKey) ? circuitBreakerKey : null;
    }

    public static OutboundHttpPolicy publicFetch(Duration timeout) {
        return new OutboundHttpPolicy(
                OutboundHttpPolicyType.PUBLIC_FETCH,
                timeout,
                2,
                OutboundUserAgents.defaultBrowser(),
                "AUTO",
                "AUTO",
                "AUTO",
                "SYSTEM",
                null,
                true,
                Set.of(429, 500, 502, 503, 504),
                List.of("metadata.google.internal"),
                OutboundProxy.disabled(),
                true,
                OutboundCircuitBreakerConfig.enabledFromEnvironment(),
                null);
    }

    public static OutboundHttpPolicy internalService(Duration timeout) {
        return new OutboundHttpPolicy(
                OutboundHttpPolicyType.INTERNAL_SERVICE,
                timeout,
                1,
                "IRCS-Internal-Service/0.1",
                "AUTO",
                "JDK",
                "AUTO",
                "SYSTEM",
                null,
                true,
                Set.of(500, 502, 503, 504),
                List.of(),
                OutboundProxy.disabled(),
                false,
                OutboundCircuitBreakerConfig.enabledFromEnvironment(),
                null);
    }

    public static OutboundHttpPolicy imageDownloadStrict(Duration timeout) {
        return imageDownloadStrict(timeout, false);
    }

    public static OutboundHttpPolicy imageDownloadStrict(Duration timeout, boolean allowLocalAddresses) {
        return new OutboundHttpPolicy(
                OutboundHttpPolicyType.IMAGE_DOWNLOAD_STRICT,
                timeout,
                0,
                "IRCS-Storage-Service/0.1",
                "AUTO",
                "JDK",
                "AUTO",
                "SYSTEM",
                null,
                false,
                Set.of(),
                List.of("metadata.google.internal"),
                OutboundProxy.disabled(),
                !allowLocalAddresses,
                OutboundCircuitBreakerConfig.enabledFromEnvironment(),
                null);
    }

    public static OutboundHttpPolicy apiGatewayProxy(Duration timeout) {
        return new OutboundHttpPolicy(
                OutboundHttpPolicyType.API_GATEWAY_PROXY,
                timeout,
                0,
                "IRCS-API-Gateway-Proxy/0.1",
                "AUTO",
                "JDK",
                "AUTO",
                "SYSTEM",
                null,
                false,
                Set.of(),
                List.of(),
                OutboundProxy.disabled(),
                false,
                OutboundCircuitBreakerConfig.enabledFromEnvironment(),
                null);
    }

    public OutboundHttpPolicy withUserAgent(String userAgent) {
        return new OutboundHttpPolicy(
                type,
                timeout,
                maxRetries,
                hasText(userAgent) ? userAgent : OutboundUserAgents.defaultBrowser(),
                httpProtocol,
                transportMode,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                decompressGzip,
                retryStatuses,
                metadataHosts,
                proxy == null ? OutboundProxy.disabled() : proxy,
                blockPrivateAddresses,
                circuitBreaker,
                circuitBreakerKey);
    }

    public OutboundHttpPolicy withMaxRetries(int maxRetries) {
        return new OutboundHttpPolicy(
                type,
                timeout,
                maxRetries,
                userAgent,
                httpProtocol,
                transportMode,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                decompressGzip,
                retryStatuses,
                metadataHosts,
                proxy,
                blockPrivateAddresses,
                circuitBreaker,
                circuitBreakerKey);
    }

    public OutboundHttpPolicy withProxy(OutboundProxy proxy) {
        return new OutboundHttpPolicy(
                type,
                timeout,
                maxRetries,
                userAgent,
                hasText(httpProtocol) ? httpProtocol : "AUTO",
                transportMode,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                decompressGzip,
                retryStatuses,
                metadataHosts,
                proxy,
                blockPrivateAddresses,
                circuitBreaker,
                circuitBreakerKey);
    }

    public OutboundHttpPolicy withHttpProtocol(String httpProtocol) {
        return new OutboundHttpPolicy(
                type,
                timeout,
                maxRetries,
                userAgent,
                httpProtocol,
                transportMode,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                decompressGzip,
                retryStatuses,
                metadataHosts,
                proxy,
                blockPrivateAddresses,
                circuitBreaker,
                circuitBreakerKey);
    }

    public OutboundHttpPolicy withTransportMode(String transportMode) {
        return new OutboundHttpPolicy(
                type,
                timeout,
                maxRetries,
                userAgent,
                httpProtocol,
                transportMode,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                decompressGzip,
                retryStatuses,
                metadataHosts,
                proxy,
                blockPrivateAddresses,
                circuitBreaker,
                circuitBreakerKey);
    }

    public OutboundHttpPolicy withResolution(String ipVersionPolicy, String dnsResolverType, String dnsResolverEndpoint) {
        return new OutboundHttpPolicy(
                type,
                timeout,
                maxRetries,
                userAgent,
                httpProtocol,
                transportMode,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                decompressGzip,
                retryStatuses,
                metadataHosts,
                proxy,
                blockPrivateAddresses,
                circuitBreaker,
                circuitBreakerKey);
    }

    public OutboundHttpPolicy withCircuitBreaker(OutboundCircuitBreakerConfig circuitBreaker) {
        return new OutboundHttpPolicy(
                type,
                timeout,
                maxRetries,
                userAgent,
                httpProtocol,
                transportMode,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                decompressGzip,
                retryStatuses,
                metadataHosts,
                proxy,
                blockPrivateAddresses,
                circuitBreaker,
                circuitBreakerKey);
    }

    public OutboundHttpPolicy withCircuitBreakerKey(String circuitBreakerKey) {
        return new OutboundHttpPolicy(
                type,
                timeout,
                maxRetries,
                userAgent,
                httpProtocol,
                transportMode,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                decompressGzip,
                retryStatuses,
                metadataHosts,
                proxy,
                blockPrivateAddresses,
                circuitBreaker,
                circuitBreakerKey);
    }

    public OutboundHttpPolicy withCallerCircuitBreakerKey(String circuitBreakerKey) {
        return new OutboundHttpPolicy(
                type,
                timeout,
                maxRetries,
                userAgent,
                httpProtocol,
                transportMode,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                decompressGzip,
                retryStatuses,
                metadataHosts,
                proxy,
                blockPrivateAddresses,
                OutboundCircuitBreakerConfig.enabledFromEnvironment(circuitBreakerKey),
                circuitBreakerKey);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
