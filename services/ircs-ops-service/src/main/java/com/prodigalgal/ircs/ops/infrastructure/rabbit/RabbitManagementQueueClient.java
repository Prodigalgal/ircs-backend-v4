package com.prodigalgal.ircs.ops.infrastructure.rabbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
public class RabbitManagementQueueClient {

    private static final String DEFAULT_BASE_URL_PORT = "15672";
    private static final int SAMPLE_BODY_PREVIEW_LIMIT = 500;
    private static final int MAX_SAMPLE_LIMIT = 20;
    private static final String HEADER_RETRY_COUNT = "x-ircs-retry-count";
    private static final String HEADER_DISPOSITION = "x-ircs-disposition";
    private static final String HEADER_ERROR_CLASS = "x-ircs-error-class";
    private static final String HEADER_ERROR_MESSAGE = "x-ircs-error-message";

    private final ObjectMapper objectMapper;
    private final RuntimeConfigService runtimeConfig;
    private volatile HttpClient httpClient;
    private volatile Duration httpClientConnectTimeout;
    private volatile String lastError;

    @Value("${spring.rabbitmq.host:localhost}")
    private String fallbackRabbitHost = "localhost";

    @Value("${spring.rabbitmq.username:guest}")
    private String fallbackUsername = "guest";

    @Value("${spring.rabbitmq.password:guest}")
    private String fallbackPassword = "guest";

    @Value("${app.ops.rabbit-management.base-url:}")
    private String fallbackBaseUrl = "";

    @Value("${app.ops.rabbit-management.vhost:/}")
    private String fallbackVhost = "/";

    @Value("${app.ops.rabbit-management.connect-timeout:PT3S}")
    private String fallbackConnectTimeout = "PT3S";

    @Value("${app.ops.rabbit-management.request-timeout:PT5S}")
    private String fallbackRequestTimeout = "PT5S";

    public Optional<RabbitManagementQueues> fetchQueues() {
        if (!enabled()) {
            lastError = "disabled";
            return Optional.empty();
        }
        return fetchQueueSnapshots();
    }

    public Optional<RabbitManagementQueues> fetchQueueSnapshots() {
        try {
            HttpRequest request = HttpRequest.newBuilder(queueEndpoint())
                    .timeout(requestTimeout())
                    .header("Accept", "application/json")
                    .header("Authorization", "Basic " + basicAuth())
                    .GET()
                    .build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Rabbit management returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            RabbitManagementQueues queues = new RabbitManagementQueues(root, snapshots(root));
            lastError = null;
            return Optional.of(queues);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            markFailed(ex);
            return Optional.empty();
        } catch (Exception ex) {
            markFailed(ex);
            return Optional.empty();
        }
    }

    public List<RabbitManagementMessageSample> sampleMessages(String queueName, int limit) {
        if (!enabled() || !StringUtils.hasText(queueName) || limit <= 0) {
            return List.of();
        }
        try {
            String body = objectMapper.writeValueAsString(new MessageSampleRequest(
                    Math.min(limit, MAX_SAMPLE_LIMIT),
                    "ack_requeue_true",
                    "auto",
                    SAMPLE_BODY_PREVIEW_LIMIT));
            HttpRequest request = HttpRequest.newBuilder(queueMessagesEndpoint(queueName.trim()))
                    .timeout(requestTimeout())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + basicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Rabbit management returned HTTP " + response.statusCode());
            }
            lastError = null;
            return messageSamples(objectMapper.readTree(response.body()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            markFailed(ex);
            return List.of();
        } catch (Exception ex) {
            markFailed(ex);
            return List.of();
        }
    }

    public String lastError() {
        return lastError;
    }

    List<RabbitManagementQueueSnapshot> snapshots(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Rabbit management queues response is not an array");
        }
        List<RabbitManagementQueueSnapshot> snapshots = new ArrayList<>();
        for (JsonNode node : root) {
            String name = node.path("name").asText("");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            long ready = longValue(node, "messages_ready", 0L);
            long unacknowledged = longValue(node, "messages_unacknowledged", 0L);
            long total = longValue(node, "messages", ready + unacknowledged);
            snapshots.add(new RabbitManagementQueueSnapshot(
                    name,
                    safeInt(ready),
                    safeInt(unacknowledged),
                    safeInt(total),
                    safeInt(longValue(node, "consumers", 0L))));
        }
        return List.copyOf(snapshots);
    }

    List<RabbitManagementMessageSample> messageSamples(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Rabbit management message sample response is not an array");
        }
        List<RabbitManagementMessageSample> samples = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode properties = node.path("properties");
            JsonNode headers = properties.path("headers");
            samples.add(new RabbitManagementMessageSample(
                    textValue(properties, "message_id"),
                    textValue(properties, "correlation_id"),
                    intHeader(headers, HEADER_RETRY_COUNT),
                    textValue(headers, HEADER_DISPOSITION),
                    textValue(headers, HEADER_ERROR_CLASS),
                    textValue(headers, HEADER_ERROR_MESSAGE),
                    safeInt(longValue(node, "payload_bytes", 0L)),
                    preview(textValue(node, "payload"))));
        }
        return List.copyOf(samples);
    }

    private URI queueEndpoint() {
        String baseUrl = baseUrl();
        return URI.create(managementRoot(baseUrl) + "/api/queues/" + encodeVhost());
    }

    private URI queueMessagesEndpoint(String queueName) {
        return URI.create(managementRoot(baseUrl()) + "/api/queues/" + encodeVhost()
                + "/" + encodeSegment(queueName) + "/get");
    }

    private String managementRoot(String baseUrl) {
        String root = StringUtils.hasText(baseUrl)
                ? baseUrl.trim()
                : "http://" + rabbitHost() + ":" + DEFAULT_BASE_URL_PORT;
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root;
    }

    private String encodeVhost() {
        String raw = StringUtils.hasText(vhost()) ? vhost().trim() : "/";
        return encodeSegment(raw);
    }

    private String encodeSegment(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String basicAuth() {
        String raw = (StringUtils.hasText(username()) ? username() : "guest") + ":"
                + (password() == null ? "" : password());
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private HttpClient client() {
        Duration desiredConnectTimeout = connectTimeout();
        HttpClient existing = httpClient;
        if (existing != null && Objects.equals(httpClientConnectTimeout, desiredConnectTimeout)) {
            return existing;
        }
        synchronized (this) {
            if (httpClient == null || !Objects.equals(httpClientConnectTimeout, desiredConnectTimeout)) {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(desiredConnectTimeout)
                        .build();
                httpClientConnectTimeout = desiredConnectTimeout;
            }
            return httpClient;
        }
    }

    private void markFailed(Exception ex) {
        lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        log.debug("Rabbit management queue snapshot failed: {}", lastError);
    }

    private boolean enabled() {
        return runtimeConfig == null
                || runtimeConfig.booleanValue("app.ops.rabbit-management.enabled", true);
    }

    private String baseUrl() {
        return runtimeStringValue("app.ops.rabbit-management.base-url", fallbackBaseUrl);
    }

    private String rabbitHost() {
        return fallbackRabbitHost;
    }

    private String username() {
        return fallbackUsername;
    }

    private String password() {
        return fallbackPassword;
    }

    private String vhost() {
        return runtimeStringValue("app.ops.rabbit-management.vhost", fallbackVhost);
    }

    private Duration connectTimeout() {
        Duration fallback = durationValue(fallbackConnectTimeout, Duration.ofSeconds(3));
        return runtimeConfig == null
                ? fallback
                : runtimeConfig.positiveDurationValue(
                        "app.ops.rabbit-management.connect-timeout",
                        fallback);
    }

    private Duration requestTimeout() {
        Duration fallback = durationValue(fallbackRequestTimeout, Duration.ofSeconds(5));
        return runtimeConfig == null
                ? fallback
                : runtimeConfig.positiveDurationValue(
                        "app.ops.rabbit-management.request-timeout",
                        fallback);
    }

    private String runtimeStringValue(String key, String fallback) {
        String value = runtimeConfig == null ? fallback : runtimeConfig.stringValue(key, fallback);
        return hasUnresolvedPlaceholder(value) ? fallback : value;
    }

    private boolean hasUnresolvedPlaceholder(String value) {
        return value != null && value.contains("${");
    }

    private Duration durationValue(String value, Duration fallback) {
        if (!StringUtils.hasText(value) || hasUnresolvedPlaceholder(value)) {
            return fallback;
        }
        try {
            Duration parsed = DurationStyle.detectAndParse(value.trim());
            return parsed == null || parsed.isZero() || parsed.isNegative() ? fallback : parsed;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private long longValue(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asLong(fallback);
    }

    private Integer intHeader(JsonNode headers, String field) {
        JsonNode value = headers.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private String preview(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= SAMPLE_BODY_PREVIEW_LIMIT
                ? value
                : value.substring(0, SAMPLE_BODY_PREVIEW_LIMIT);
    }

    private int safeInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private record MessageSampleRequest(
            int count,
            String ackmode,
            String encoding,
            int truncate
    ) {
    }
}
