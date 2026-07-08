package com.prodigalgal.ircs.common.outbound;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public record OutboundHttpRequest(
        URI uri,
        String method,
        Map<String, String> headers,
        OutboundHttpPolicy policy,
        byte[] body) {

    public OutboundHttpRequest {
        headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    public static OutboundHttpRequest get(String url, OutboundHttpPolicy policy) {
        return get(URI.create(url), policy);
    }

    public static OutboundHttpRequest get(URI uri, OutboundHttpPolicy policy) {
        return new OutboundHttpRequest(uri, "GET", new LinkedHashMap<>(), policy, new byte[0]);
    }

    public static OutboundHttpRequest post(URI uri, OutboundHttpPolicy policy) {
        return new OutboundHttpRequest(uri, "POST", new LinkedHashMap<>(), policy, new byte[0]);
    }

    public static OutboundHttpRequest post(String url, OutboundHttpPolicy policy) {
        return post(URI.create(url), policy);
    }

    public OutboundHttpRequest withBody(byte[] body) {
        return new OutboundHttpRequest(uri, method, headers, policy, body);
    }

    public OutboundHttpRequest withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new OutboundHttpRequest(uri, method, copy, policy, body);
    }
}
