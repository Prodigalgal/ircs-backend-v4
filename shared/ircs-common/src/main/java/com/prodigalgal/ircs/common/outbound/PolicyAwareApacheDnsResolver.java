package com.prodigalgal.ircs.common.outbound;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;
import org.apache.hc.client5.http.DnsResolver;

class PolicyAwareApacheDnsResolver implements DnsResolver {

    private static final String DEFAULT_DOH_ENDPOINT = "https://cloudflare-dns.com/dns-query";
    private static final String DEFAULT_DOT_ENDPOINT = "one.one.one.one:853";
    private static final Pattern DOH_DATA_PATTERN =
            Pattern.compile("\"data\"\\s*:\\s*\"([0-9A-Fa-f:.]+)\"");

    private final OutboundHttpPolicy policy;

    PolicyAwareApacheDnsResolver(OutboundHttpPolicy policy) {
        this.policy = policy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        List<InetAddress> addresses = switch (normalized(policy.dnsResolverType(), "SYSTEM")) {
            case "DOH" -> resolveDoh(host);
            case "DOT" -> resolveDot(host);
            default -> List.of(InetAddress.getAllByName(host));
        };
        List<InetAddress> filtered = applyIpPolicy(addresses);
        if (filtered.isEmpty()) {
            throw new UnknownHostException(host);
        }
        return filtered.toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return InetAddress.getByName(host).getCanonicalHostName();
    }

    private List<InetAddress> resolveDoh(String host) throws UnknownHostException {
        try {
            Set<InetAddress> addresses = new LinkedHashSet<>();
            addresses.addAll(resolveDohType(host, "A"));
            addresses.addAll(resolveDohType(host, "AAAA"));
            return List.copyOf(addresses);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            UnknownHostException unknownHost = new UnknownHostException(host);
            unknownHost.initCause(ex);
            throw unknownHost;
        } catch (IOException ex) {
            UnknownHostException unknownHost = new UnknownHostException(host);
            unknownHost.initCause(ex);
            throw unknownHost;
        }
    }

    private List<InetAddress> resolveDohType(String host, String type) throws IOException, InterruptedException {
        String endpoint = hasText(policy.dnsResolverEndpoint()) ? policy.dnsResolverEndpoint() : DEFAULT_DOH_ENDPOINT;
        String separator = endpoint.contains("?") ? "&" : "?";
        URI uri = URI.create(endpoint + separator
                + "name=" + URLEncoder.encode(host, StandardCharsets.UTF_8)
                + "&type=" + type);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout())
                .header("Accept", "application/dns-json")
                .GET()
                .build();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        Matcher matcher = DOH_DATA_PATTERN.matcher(body);
        List<InetAddress> addresses = new ArrayList<>();
        while (matcher.find()) {
            String value = matcher.group(1);
            InetAddress address = InetAddress.getByName(value);
            if (("A".equals(type) && address instanceof Inet4Address)
                    || ("AAAA".equals(type) && address instanceof Inet6Address)) {
                addresses.add(address);
            }
        }
        return addresses;
    }

    private List<InetAddress> resolveDot(String host) throws UnknownHostException {
        try {
            DotEndpoint endpoint = dotEndpoint();
            Set<InetAddress> addresses = new LinkedHashSet<>();
            addresses.addAll(resolveDotType(endpoint, host, 1));
            addresses.addAll(resolveDotType(endpoint, host, 28));
            return List.copyOf(addresses);
        } catch (IOException ex) {
            UnknownHostException unknownHost = new UnknownHostException(host);
            unknownHost.initCause(ex);
            throw unknownHost;
        }
    }

    private List<InetAddress> resolveDotType(DotEndpoint endpoint, String host, int type) throws IOException {
        byte[] query = dnsQuery(host, type);
        Socket rawSocket = new Socket();
        rawSocket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), timeoutMillis());
        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) sslSocketFactory
                        .createSocket(rawSocket, endpoint.host(), endpoint.port(), true);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                DataInputStream input = new DataInputStream(socket.getInputStream())) {
            socket.setSoTimeout(timeoutMillis());
            socket.startHandshake();
            output.writeShort(query.length);
            output.write(query);
            output.flush();
            int length = input.readUnsignedShort();
            byte[] response = input.readNBytes(length);
            return parseDnsResponse(response);
        }
    }

    private byte[] dnsQuery(String host, int type) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeShort(0x4d52);
        output.writeShort(0x0100);
        output.writeShort(1);
        output.writeShort(0);
        output.writeShort(0);
        output.writeShort(0);
        for (String label : host.split("\\.")) {
            byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
            output.writeByte(labelBytes.length);
            output.write(labelBytes);
        }
        output.writeByte(0);
        output.writeShort(type);
        output.writeShort(1);
        return bytes.toByteArray();
    }

    private List<InetAddress> parseDnsResponse(byte[] response) throws IOException {
        if (response.length < 12) {
            return List.of();
        }
        int questionCount = unsignedShort(response, 4);
        int answerCount = unsignedShort(response, 6);
        int offset = 12;
        for (int i = 0; i < questionCount; i++) {
            offset = skipName(response, offset) + 4;
        }
        List<InetAddress> addresses = new ArrayList<>();
        for (int i = 0; i < answerCount && offset < response.length; i++) {
            offset = skipName(response, offset);
            if (offset + 10 > response.length) {
                break;
            }
            int type = unsignedShort(response, offset);
            int recordClass = unsignedShort(response, offset + 2);
            int length = unsignedShort(response, offset + 8);
            offset += 10;
            if (offset + length > response.length) {
                break;
            }
            if (recordClass == 1 && ((type == 1 && length == 4) || (type == 28 && length == 16))) {
                byte[] address = new byte[length];
                System.arraycopy(response, offset, address, 0, length);
                addresses.add(InetAddress.getByAddress(address));
            }
            offset += length;
        }
        return addresses;
    }

    private int skipName(byte[] response, int offset) throws IOException {
        int current = offset;
        while (current < response.length) {
            int length = response[current] & 0xff;
            if ((length & 0xc0) == 0xc0) {
                return current + 2;
            }
            current++;
            if (length == 0) {
                return current;
            }
            current += length;
        }
        throw new IOException("Invalid DNS name");
    }

    private int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private List<InetAddress> applyIpPolicy(List<InetAddress> addresses) {
        String ipPolicy = normalized(policy.ipVersionPolicy(), "AUTO");
        List<InetAddress> filtered = new ArrayList<>(addresses);
        if ("IPV4_ONLY".equals(ipPolicy)) {
            filtered.removeIf(address -> !(address instanceof Inet4Address));
        } else if ("IPV6_ONLY".equals(ipPolicy)) {
            filtered.removeIf(address -> !(address instanceof Inet6Address));
        } else if ("IPV4_PREFERRED".equals(ipPolicy)) {
            filtered.sort(Comparator.comparing(address -> address instanceof Inet4Address ? 0 : 1));
        } else if ("IPV6_PREFERRED".equals(ipPolicy)) {
            filtered.sort(Comparator.comparing(address -> address instanceof Inet6Address ? 0 : 1));
        }
        return filtered;
    }

    private DotEndpoint dotEndpoint() {
        String endpoint = hasText(policy.dnsResolverEndpoint()) ? policy.dnsResolverEndpoint() : DEFAULT_DOT_ENDPOINT;
        String value = endpoint.replaceFirst("^dot://", "").replaceFirst("^tls://", "");
        if (value.contains("://")) {
            URI uri = URI.create(value);
            return new DotEndpoint(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 853);
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0 && colon < value.length() - 1) {
            return new DotEndpoint(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        }
        return new DotEndpoint(value, 853);
    }

    private Duration timeout() {
        return policy.timeout() == null ? Duration.ofSeconds(10) : policy.timeout();
    }

    private int timeoutMillis() {
        long millis = timeout().toMillis();
        return (int) Math.max(1, Math.min(millis, Integer.MAX_VALUE));
    }

    private String normalized(String value, String fallback) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DotEndpoint(String host, int port) {
    }
}
