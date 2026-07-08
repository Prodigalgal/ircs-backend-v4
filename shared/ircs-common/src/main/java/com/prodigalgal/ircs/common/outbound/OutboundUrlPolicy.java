package com.prodigalgal.ircs.common.outbound;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class OutboundUrlPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final OutboundAddressResolver addressResolver;

    public OutboundUrlPolicy(OutboundAddressResolver addressResolver) {
        this.addressResolver = addressResolver;
    }

    public void validatePublicFetch(URI uri, OutboundHttpPolicy policy) throws OutboundHttpException {
        validateHttpUrlShape(uri);
        validateResolvedPublicHost(uri, policy);
    }

    public void validateImageDownloadStrict(URI uri, OutboundHttpPolicy policy) throws OutboundHttpException {
        validateHttpUrlShape(uri);
        validateResolvedPublicHost(uri, policy);
    }

    private void validateResolvedPublicHost(URI uri, OutboundHttpPolicy policy) throws OutboundHttpException {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (policy.metadataHosts().contains(host)
                || (policy.blockPrivateAddresses() && "localhost".equals(host))) {
            throw new OutboundHttpException("Outbound URL host is blocked: " + host);
        }

        try {
            boolean resolved = false;
            for (InetAddress address : resolve(host, policy)) {
                resolved = true;
                if (policy.blockPrivateAddresses() && isBlocked(address)) {
                    throw new OutboundHttpException("Outbound URL resolves to blocked address: " + address.getHostAddress());
                }
            }
            if (!resolved) {
                throw new OutboundHttpException("Outbound URL host cannot be resolved: " + host);
            }
        } catch (UnknownHostException ex) {
            throw new OutboundHttpException("Outbound URL host cannot be resolved: " + host, ex);
        }
    }

    private List<InetAddress> resolve(String host, OutboundHttpPolicy policy) throws UnknownHostException {
        if (!"SYSTEM".equalsIgnoreCase(policy.dnsResolverType())
                || !"AUTO".equalsIgnoreCase(policy.ipVersionPolicy())) {
            return Arrays.asList(new PolicyAwareApacheDnsResolver(policy).resolve(host));
        }
        return addressResolver.resolve(host);
    }

    public void validateInternalService(URI uri) throws OutboundHttpException {
        validateHttpUrlShape(uri);
    }

    public void validateApiGatewayProxyTarget(URI uri) throws OutboundHttpException {
        validateHttpUrlShape(uri);
    }

    private void validateHttpUrlShape(URI uri) throws OutboundHttpException {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new OutboundHttpException("Outbound URL must include scheme and host");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new OutboundHttpException("Outbound URL scheme is not allowed: " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new OutboundHttpException("Outbound URL userinfo is not allowed");
        }
    }

    private boolean isBlocked(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isBlockedIpv4(address)
                || isBlockedIpv6(address);
    }

    private boolean isBlockedIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] raw = address.getAddress();
        int first = raw[0] & 0xff;
        int second = raw[1] & 0xff;
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))
                || first >= 224;
    }

    private boolean isBlockedIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] raw = address.getAddress();
        int first = raw[0] & 0xff;
        int second = raw[1] & 0xff;
        return (first & 0xfe) == 0xfc
                || (first == 0xfe && (second & 0xc0) == 0x80);
    }
}
