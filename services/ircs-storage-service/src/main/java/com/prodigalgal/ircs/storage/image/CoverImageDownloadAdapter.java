package com.prodigalgal.ircs.storage.image;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

interface CoverImageDownloadAdapter {

    CoverImageDownloadResponse download(URI uri) throws IOException, InterruptedException;

    record CoverImageDownloadResponse(
            int statusCode,
            Map<String, List<String>> headers,
            InputStream body) implements AutoCloseable {

        String firstHeader(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }

        Long firstHeaderAsLong(String name) {
            String value = firstHeader(name);
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
