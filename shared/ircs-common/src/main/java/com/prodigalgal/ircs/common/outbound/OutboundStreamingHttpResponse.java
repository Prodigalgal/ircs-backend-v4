package com.prodigalgal.ircs.common.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public record OutboundStreamingHttpResponse(
        int statusCode,
        Map<String, List<String>> headers,
        InputStream body) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        body.close();
    }
}
