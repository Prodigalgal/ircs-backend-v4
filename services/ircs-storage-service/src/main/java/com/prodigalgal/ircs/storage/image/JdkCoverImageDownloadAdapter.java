package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

class JdkCoverImageDownloadAdapter implements CoverImageDownloadAdapter {

    private final Duration timeout;

    JdkCoverImageDownloadAdapter(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public CoverImageDownloadResponse download(URI uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", OutboundHttpPolicy.imageDownloadStrict(timeout).userAgent())
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        return new CoverImageDownloadResponse(response.statusCode(), response.headers().map(), response.body());
    }
}
