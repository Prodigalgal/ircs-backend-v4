package com.prodigalgal.ircs.notification.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class WebhookNotificationChannelExecutor implements NotificationChannelExecutor {

    static final String ENABLED_KEY = "app.notification.webhook.enabled";
    static final String REQUEST_TIMEOUT_KEY = "app.notification.webhook.request-timeout";
    static final String MAX_RETRIES_KEY = "app.notification.webhook.max-retries";
    static final String MAX_PAYLOAD_BYTES_KEY = "app.notification.webhook.max-payload-bytes";
    static final String USER_AGENT_KEY = "app.notification.webhook.user-agent";
    static final String ALLOW_PRIVATE_ADDRESSES_KEY = "app.notification.webhook.allow-private-addresses";
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    static final int DEFAULT_MAX_RETRIES = 1;
    static final int DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024;
    static final String DEFAULT_USER_AGENT = "IRCS-Notification-Webhook/0.1";

    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "connection",
            "content-type",
            "content-length",
            "expect",
            "host",
            "x-ircs-correlation-id",
            "x-ircs-notification-command-id",
            "transfer-encoding",
            "upgrade");

    private final RuntimeConfigService runtimeConfig;
    private final ObjectMapper objectMapper;
    private final WebhookDeliveryClient deliveryClient;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WEBHOOK;
    }

    @Override
    public NotificationChannelExecution execute(NotificationCommand command, String recipient) {
        if (!runtimeConfig.booleanValue(ENABLED_KEY, false)) {
            return NotificationChannelExecution.skipped(channel().name(), recipient, "webhook channel disabled");
        }
        URI endpoint = parseEndpoint(recipient);
        if (endpoint == null) {
            return NotificationChannelExecution.skipped(channel().name(), recipient, "invalid webhook endpoint");
        }
        byte[] payload = serializePayload(command, recipient);
        int maxPayloadBytes = Math.max(1024, runtimeConfig.intValue(
                MAX_PAYLOAD_BYTES_KEY,
                DEFAULT_MAX_PAYLOAD_BYTES));
        if (payload.length > maxPayloadBytes) {
            return NotificationChannelExecution.skipped(
                    channel().name(),
                    recipient,
                    "webhook payload exceeds " + maxPayloadBytes + " bytes");
        }

        WebhookDeliveryResponse response = deliver(command, endpoint, payload);
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return NotificationChannelExecution.delivered(
                    channel().name(),
                    recipient,
                    "webhook accepted: HTTP " + statusCode);
        }
        if (statusCode == 429 || statusCode >= 500) {
            throw new IllegalStateException("Webhook endpoint returned retryable HTTP " + statusCode);
        }
        return NotificationChannelExecution.skipped(
                channel().name(),
                recipient,
                "webhook rejected: HTTP " + statusCode);
    }

    private WebhookDeliveryResponse deliver(NotificationCommand command, URI endpoint, byte[] payload) {
        try {
            return deliveryClient.post(new WebhookDeliveryRequest(
                    endpoint,
                    headers(command),
                    payload,
                    policy()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook delivery interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Webhook delivery failed", ex);
        }
    }

    private URI parseEndpoint(String recipient) {
        if (!StringUtils.hasText(recipient)) {
            return null;
        }
        try {
            URI uri = URI.create(recipient.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) ? uri : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private byte[] serializePayload(NotificationCommand command, String recipient) {
        try {
            return objectMapper.writeValueAsBytes(payload(command, recipient));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Webhook notification payload is not serializable", ex);
        }
    }

    private Map<String, Object> payload(NotificationCommand command, String recipient) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", command.getCommandId());
        payload.put("correlationId", command.getCorrelationId());
        payload.put("channel", channel().name());
        payload.put("recipient", recipient);
        payload.put("subject", command.getSubject());
        payload.put("content", command.getContent());
        payload.put("html", command.isHtml());
        payload.put("templateCode", command.getTemplateCode());
        payload.put("variables", command.getVariables() == null ? Map.of() : command.getVariables());
        return payload;
    }

    private Map<String, String> headers(NotificationCommand command) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        putIfPresent(headers, "X-IRCS-Notification-Command-Id", command.getCommandId());
        putIfPresent(headers, "X-IRCS-Correlation-Id", command.getCorrelationId());
        customHeaders(command).forEach((name, value) -> {
            if (isAllowedHeader(name) && StringUtils.hasText(value)) {
                headers.put(name.trim(), value.trim());
            }
        });
        return headers;
    }

    private Map<String, String> customHeaders(NotificationCommand command) {
        if (command.getChannelOptions() == null) {
            return Map.of();
        }
        Object headers = command.getChannelOptions().get("headers");
        if (!(headers instanceof Map<?, ?> rawHeaders)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        rawHeaders.forEach((name, value) -> {
            if (name != null && value != null) {
                result.put(name.toString(), value.toString());
            }
        });
        return result;
    }

    private OutboundHttpPolicy policy() {
        Duration timeout = runtimeConfig.durationValue(REQUEST_TIMEOUT_KEY, DEFAULT_REQUEST_TIMEOUT);
        int maxRetries = Math.max(0, runtimeConfig.intValue(MAX_RETRIES_KEY, DEFAULT_MAX_RETRIES));
        String userAgent = runtimeConfig.stringValue(USER_AGENT_KEY, DEFAULT_USER_AGENT);
        boolean allowPrivateAddresses = runtimeConfig.booleanValue(ALLOW_PRIVATE_ADDRESSES_KEY, false);
        OutboundHttpPolicy policy = allowPrivateAddresses
                ? OutboundHttpPolicy.internalService(timeout)
                : OutboundHttpPolicy.publicFetch(timeout);
        return policy
                .withMaxRetries(maxRetries)
                .withUserAgent(userAgent)
                .withCallerCircuitBreakerKey("notification-webhook");
    }

    private static boolean isAllowedHeader(String name) {
        return StringUtils.hasText(name)
                && !RESTRICTED_HEADERS.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    private static void putIfPresent(Map<String, String> headers, String name, String value) {
        if (StringUtils.hasText(value)) {
            headers.put(name, value.trim());
        }
    }
}
