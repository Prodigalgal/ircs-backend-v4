package com.prodigalgal.ircs.common.outbound;

import java.io.IOException;
import java.util.Locale;

public class OutboundTransportRouter implements OutboundTransport {

    private final OutboundTransport jdkTransport;
    private final OutboundTransport apacheTransport;

    public OutboundTransportRouter() {
        this(new JdkOutboundTransport(), new ApacheHttpClient5OutboundTransport());
    }

    public OutboundTransportRouter(OutboundTransport jdkTransport, OutboundTransport apacheTransport) {
        this.jdkTransport = jdkTransport;
        this.apacheTransport = apacheTransport;
    }

    @Override
    public OutboundHttpResponse send(OutboundHttpRequest request) throws IOException, InterruptedException {
        return select(request.policy()).send(request);
    }

    private OutboundTransport select(OutboundHttpPolicy policy) throws OutboundHttpException {
        String mode = normalized(policy.transportMode(), "AUTO");
        return switch (mode) {
            case "JDK" -> jdkTransport;
            case "APACHE_HC5" -> apacheTransport;
            case "CURL_NATIVE" -> throw new OutboundHttpException(
                    "CURL_NATIVE transport is configured but not implemented in this runtime");
            case "AUTO" -> needsApache(policy) ? apacheTransport : jdkTransport;
            default -> throw new OutboundHttpException("Unsupported outbound transport mode: " + mode);
        };
    }

    private boolean needsApache(OutboundHttpPolicy policy) {
        return "HTTP_1_0".equalsIgnoreCase(policy.httpProtocol())
                || !"SYSTEM".equalsIgnoreCase(policy.dnsResolverType())
                || !"AUTO".equalsIgnoreCase(policy.ipVersionPolicy());
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }
}
