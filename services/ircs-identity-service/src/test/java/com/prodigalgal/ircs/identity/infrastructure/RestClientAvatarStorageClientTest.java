package com.prodigalgal.ircs.identity.infrastructure;


import com.prodigalgal.ircs.identity.api.ApiException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicyType;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.identity.infrastructure.AvatarStorageClient.AvatarFile;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

class RestClientAvatarStorageClientTest {

    private final FakeTransport transport = new FakeTransport();
    private final RestClientAvatarStorageClient client = client(
            "http://ircs-storage-service:8080",
            transport);

    @Test
    void postsMultipartAvatarThroughSharedInternalServiceCaller() {
        transport.enqueue(json(200, """
                {"url":"/media/avatars/a.png","storageKey":"avatars/a.png","mimeType":"image/png","fileSize":3,"fileHash":"abc"}
                """));

        AvatarStorageClient.StoredAvatar stored =
                client.store(new AvatarFile("me.png", "image/png", new byte[] {1, 2, 3}));

        assertThat(stored.url()).isEqualTo("/media/avatars/a.png");
        assertThat(stored.storageKey()).isEqualTo("avatars/a.png");
        assertThat(transport.requests).hasSize(1);
        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri()).isEqualTo(URI.create("http://ircs-storage-service:8080/internal/storage/avatars"));
        assertThat(sent.policy().type()).isEqualTo(OutboundHttpPolicyType.INTERNAL_SERVICE);
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("identity-avatar-storage");
        assertThat(sent.headers().get("Content-Type")).startsWith("multipart/form-data; boundary=");
        String body = new String(sent.body(), StandardCharsets.ISO_8859_1);
        assertThat(body)
                .contains("Content-Disposition: form-data; name=\"file\"; filename=\"me.png\"")
                .contains("Content-Type: image/png");
        assertThat(sent.body()).containsSequence(new byte[] {1, 2, 3});
    }

    @Test
    void emptySuccessfulResponseMapsToExistingStorageSyncFailure() {
        transport.enqueue(new OutboundHttpResponse(200, Map.of(), new byte[0]));

        assertStorageSyncFailed(() -> client.store(new AvatarFile("me.png", "image/png", new byte[] {1})),
                "storage-service returned empty avatar response");
    }

    @Test
    void non2xxAndIoMapToExistingStorageSyncFailure() {
        transport.enqueue(json(503, "{\"error\":\"unavailable\"}"));
        assertStorageSyncFailed(() -> client.store(new AvatarFile("me.png", "image/png", new byte[] {1})),
                "storage-service avatar upload failed");

        transport.enqueue(new IOException("connection refused"));
        transport.enqueue(new IOException("connection refused"));
        assertStorageSyncFailed(() -> client.store(new AvatarFile("me.png", "image/png", new byte[] {1})),
                "storage-service avatar upload failed");
    }

    @Test
    void invalidInternalUrlFailsBeforeTransportSend() {
        FakeTransport invalidTransport = new FakeTransport();
        RestClientAvatarStorageClient invalidClient = client(
                "http://token@ircs-storage-service:8080",
                invalidTransport);

        assertStorageSyncFailed(() -> invalidClient.store(new AvatarFile("me.png", "image/png", new byte[] {1})),
                "storage-service avatar upload failed");
        assertThat(invalidTransport.requests).isEmpty();
    }

    @Test
    void openCircuitFastFailMapsToExistingStorageSyncFailureBeforeTransportSend() {
        for (int i = 0; i < 10; i++) {
            transport.enqueue(json(503, "{\"error\":\"unavailable\"}"));
        }
        for (int i = 0; i < 5; i++) {
            assertStorageSyncFailed(() -> client.store(new AvatarFile("me.png", "image/png", new byte[] {1})),
                    "storage-service avatar upload failed");
        }
        int sentBeforeOpen = transport.requests.size();

        assertStorageSyncFailed(() -> client.store(new AvatarFile("me.png", "image/png", new byte[] {1})),
                "storage-service avatar upload failed");
        assertThat(transport.requests).hasSize(sentBeforeOpen);
    }

    private OutboundHttpResponse json(int status, String body) {
        return new OutboundHttpResponse(
                status,
                Map.of("Content-Type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private RestClientAvatarStorageClient client(String baseUrl, FakeTransport transport) {
        return new RestClientAvatarStorageClient(
                baseUrl,
                "2s",
                "identity-service",
                "",
                "",
                provider(new OutboundHttpClient(new OutboundUrlPolicy(host -> List.of()), transport)),
                provider(new ObjectMapper()));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }

    private void assertStorageSyncFailed(ThrowingRunnable runnable, String message) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(api.errorKey()).isEqualTo("storage.sync.failed");
                    assertThat(api.entity()).isEqualTo("avatar");
                })
                .hasMessage(message);
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static final class FakeTransport implements OutboundTransport {

        private final List<OutboundHttpRequest> requests = new ArrayList<>();
        private final Queue<Object> responses = new ArrayDeque<>();

        void enqueue(Object response) {
            responses.add(response);
        }

        @Override
        public OutboundHttpResponse send(OutboundHttpRequest request) throws IOException {
            requests.add(request);
            Object next = responses.remove();
            if (next instanceof IOException ex) {
                throw ex;
            }
            return (OutboundHttpResponse) next;
        }
    }
}
