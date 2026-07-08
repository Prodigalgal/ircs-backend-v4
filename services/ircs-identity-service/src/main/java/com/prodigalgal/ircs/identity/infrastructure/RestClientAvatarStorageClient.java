package com.prodigalgal.ircs.identity.infrastructure;


import com.prodigalgal.ircs.identity.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.identity.infrastructure.AvatarStorageClient.AvatarFile;
import com.prodigalgal.ircs.identity.infrastructure.AvatarStorageClient.StoredAvatar;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class RestClientAvatarStorageClient implements AvatarStorageClient {

    private static final String AVATAR_UPLOAD_PATH = "/internal/storage/avatars";
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

    private final URI avatarUploadUri;
    private final Duration requestTimeout;
    private final OutboundHttpClient outboundHttpClient;
    private final ObjectMapper objectMapper;
    private final String serviceId;
    private final String serviceToken;
    private final String serviceScopes;
    public RestClientAvatarStorageClient(
            @Value("${app.identity.storage-service.base-url:http://ircs-storage-service:8080}") String baseUrl,
            @Value("${app.identity.storage-service.request-timeout:10s}") String requestTimeout,
            @Value("${app.identity.storage-service.service-id:identity-service}") String serviceId,
            @Value("${app.identity.storage-service.service-token:${APP_IDENTITY_STORAGE_SERVICE_TOKEN:}}") String serviceToken,
            @Value("${app.identity.storage-service.scopes:storage:avatar}") String serviceScopes,
            ObjectProvider<OutboundHttpClient> outboundHttpClientProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        Duration timeout = parseDuration(requestTimeout);
        this.avatarUploadUri = uploadUri(baseUrl);
        this.requestTimeout = timeout;
        this.outboundHttpClient = outboundHttpClient(outboundHttpClientProvider, timeout);
        this.objectMapper = objectMapper(objectMapperProvider);
        this.serviceId = serviceId;
        this.serviceToken = serviceToken;
        this.serviceScopes = serviceScopes;
    }

    @Override
    public StoredAvatar store(AvatarFile avatar) {
        try {
            String boundary = "----IRCSAvatarBoundary" + UUID.randomUUID().toString().replace("-", "");
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(requestTimeout)
                    .withCallerCircuitBreakerKey("identity-avatar-storage");
            OutboundHttpRequest request = OutboundHttpRequest.post(avatarUploadUri, policy)
                    .withHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .withHeader("Accept", "application/json")
                    .withBody(multipartBody(avatar, boundary));
            request = InternalServiceAuthHeaders.apply(request, serviceId, serviceToken, serviceScopes);
            OutboundHttpResponse response = outboundHttpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw storageSyncFailed("storage-service avatar upload failed", null);
            }
            if (response.body().length == 0) {
                throw storageSyncFailed("storage-service returned empty avatar response", null);
            }
            StoredAvatar stored = objectMapper.readValue(response.body(), StoredAvatar.class);
            if (stored == null || !StringUtils.hasText(stored.url())) {
                throw storageSyncFailed("storage-service returned empty avatar response", null);
            }
            return stored;
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw storageSyncFailed("storage-service avatar upload failed", ex);
        } catch (IOException | RuntimeException ex) {
            throw storageSyncFailed("storage-service avatar upload failed", ex);
        }
    }

    private ApiException storageSyncFailed(String message, Exception ex) {
        if (ex != null) {
            log.warn("Avatar storage sync failed: {}", ex.getMessage());
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, message, "avatar", "storage.sync.failed");
    }

    private byte[] multipartBody(AvatarFile avatar, String boundary) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "--" + boundary);
        output.write(CRLF);
        writeAscii(output, "Content-Disposition: form-data; name=\"file\"; filename=\"" + quoted(filename(avatar)) + "\"");
        output.write(CRLF);
        writeAscii(output, "Content-Type: " + contentType(avatar));
        output.write(CRLF);
        output.write(CRLF);
        output.write(avatar.data() == null ? new byte[0] : avatar.data());
        output.write(CRLF);
        writeAscii(output, "--" + boundary + "--");
        output.write(CRLF);
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static URI uploadUri(String baseUrl) {
        return URI.create(stripTrailingSlash(baseUrl) + AVATAR_UPLOAD_PATH);
    }

    private static String stripTrailingSlash(String value) {
        String base = StringUtils.hasText(value) ? value.trim() : "http://ircs-storage-service:8080";
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String filename(AvatarFile avatar) {
        return StringUtils.hasText(avatar.filename()) ? avatar.filename() : "avatar";
    }

    private static String contentType(AvatarFile avatar) {
        String value = StringUtils.hasText(avatar.contentType()) ? avatar.contentType() : "application/octet-stream";
        return MediaType.parseMediaType(value).toString();
    }

    private static Duration parseDuration(String value) {
        if (!StringUtils.hasText(value)) {
            return Duration.ofSeconds(10);
        }
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (trimmed.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2)));
            }
            if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            }
            if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            }
            return Duration.parse(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid identity storage-service request timeout: " + value, ex);
        }
    }

    private static OutboundHttpClient outboundHttpClient(
            ObjectProvider<OutboundHttpClient> outboundHttpClientProvider,
            Duration requestTimeout) {
        if (outboundHttpClientProvider != null) {
            OutboundHttpClient provided = outboundHttpClientProvider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new JdkOutboundTransport(requestTimeout));
    }

    private static ObjectMapper objectMapper(ObjectProvider<ObjectMapper> objectMapperProvider) {
        if (objectMapperProvider != null) {
            ObjectMapper provided = objectMapperProvider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new ObjectMapper();
    }

    private static String quoted(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "_")
                .replace("\n", "_");
    }
}
