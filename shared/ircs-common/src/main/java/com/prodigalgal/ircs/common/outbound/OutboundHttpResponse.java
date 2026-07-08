package com.prodigalgal.ircs.common.outbound;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public record OutboundHttpResponse(
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body) {

    public String bodyAsUtf8() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public String firstHeader(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }
}
