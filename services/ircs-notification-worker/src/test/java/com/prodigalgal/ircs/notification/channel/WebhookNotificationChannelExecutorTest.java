package com.prodigalgal.ircs.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WebhookNotificationChannelExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
    private final FakeWebhookDeliveryClient deliveryClient = new FakeWebhookDeliveryClient();
    private final WebhookNotificationChannelExecutor executor =
            new WebhookNotificationChannelExecutor(runtimeConfig, objectMapper, deliveryClient);

    @Test
    void skipsWebhookWhenChannelIsDisabled() {
        when(runtimeConfig.booleanValue(WebhookNotificationChannelExecutor.ENABLED_KEY, false)).thenReturn(false);

        NotificationChannelExecution execution = executor.execute(command(), "https://example.invalid/webhook");

        assertThat(execution.status()).isEqualTo("SKIPPED");
        assertThat(execution.detail()).contains("disabled");
        assertThat(deliveryClient.requests).isEmpty();
    }

    @Test
    void postsWebhookPayloadAndHeadersWhenEnabled() throws Exception {
        enableWebhook();
        deliveryClient.enqueue(new WebhookDeliveryResponse(204));

        NotificationChannelExecution execution = executor.execute(command(), "https://hooks.example.invalid/ircs");

        assertThat(execution.status()).isEqualTo("SENT");
        assertThat(execution.detail()).contains("HTTP 204");
        WebhookDeliveryRequest request = deliveryClient.requests.getFirst();
        assertThat(request.uri()).isEqualTo(URI.create("https://hooks.example.invalid/ircs"));
        assertThat(request.headers())
                .containsEntry("Content-Type", "application/json")
                .containsEntry("X-IRCS-Notification-Command-Id", "cmd-1")
                .containsEntry("X-IRCS-Correlation-Id", "incident-1")
                .containsEntry("X-Webhook-Signature", "sig-1")
                .doesNotContainKey("Host");
        assertThat(request.policy().timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(request.policy().maxRetries()).isEqualTo(2);
        assertThat(request.policy().blockPrivateAddresses()).isTrue();
        JsonNode payload = objectMapper.readTree(request.body());
        assertThat(payload.get("commandId").asText()).isEqualTo("cmd-1");
        assertThat(payload.get("correlationId").asText()).isEqualTo("incident-1");
        assertThat(payload.get("channel").asText()).isEqualTo("WEBHOOK");
        assertThat(payload.get("subject").asText()).isEqualTo("IRCS alert");
        assertThat(payload.get("variables").get("severity").asText()).isEqualTo("HIGH");
        assertThat(payload.has("channelOptions")).isFalse();
    }

    @Test
    void skipsInvalidWebhookEndpointWithoutPoisoningRetryQueue() {
        enableWebhook();

        NotificationChannelExecution execution = executor.execute(command(), "ftp://example.invalid/hook");

        assertThat(execution.status()).isEqualTo("SKIPPED");
        assertThat(execution.detail()).contains("invalid webhook endpoint");
        assertThat(deliveryClient.requests).isEmpty();
    }

    @Test
    void skipsOversizedPayloadWithoutSending() {
        enableWebhook();
        when(runtimeConfig.intValue(
                WebhookNotificationChannelExecutor.MAX_PAYLOAD_BYTES_KEY,
                WebhookNotificationChannelExecutor.DEFAULT_MAX_PAYLOAD_BYTES))
                .thenReturn(1024);

        NotificationChannelExecution execution = executor.execute(
                NotificationCommand.builder()
                        .channel(NotificationChannel.WEBHOOK)
                        .recipients(List.of("https://example.invalid/hook"))
                        .content("x".repeat(2_000))
                        .build(),
                "https://example.invalid/hook");

        assertThat(execution.status()).isEqualTo("SKIPPED");
        assertThat(execution.detail()).contains("payload exceeds");
        assertThat(deliveryClient.requests).isEmpty();
    }

    @Test
    void skipsNonRetryableHttpRejection() {
        enableWebhook();
        deliveryClient.enqueue(new WebhookDeliveryResponse(400));

        NotificationChannelExecution execution = executor.execute(command(), "https://example.invalid/hook");

        assertThat(execution.status()).isEqualTo("SKIPPED");
        assertThat(execution.detail()).contains("HTTP 400");
    }

    @Test
    void throwsRetryableHttpFailureForRabbitRetry() {
        enableWebhook();
        deliveryClient.enqueue(new WebhookDeliveryResponse(503));

        assertThatThrownBy(() -> executor.execute(command(), "https://example.invalid/hook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retryable HTTP 503");
    }

    @Test
    void throwsTransportFailureForRabbitRetry() {
        enableWebhook();
        deliveryClient.enqueue(new IOException("connect timeout"));

        assertThatThrownBy(() -> executor.execute(command(), "https://example.invalid/hook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Webhook delivery failed");
    }

    @Test
    void deliveryClientPostsThroughOutboundHttpClient() throws Exception {
        FakeOutboundTransport transport = new FakeOutboundTransport(new WebhookDeliveryResponse(202));
        DefaultWebhookDeliveryClient client = new DefaultWebhookDeliveryClient(transport);
        WebhookDeliveryRequest request = new WebhookDeliveryRequest(
                URI.create("https://example.invalid/hook"),
                Map.of("Content-Type", "application/json"),
                "{}".getBytes(StandardCharsets.UTF_8),
                com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy.internalService(Duration.ofSeconds(1)));

        WebhookDeliveryResponse response = client.post(request);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(transport.requests).hasSize(1);
        assertThat(transport.requests.getFirst().headers()).containsEntry("Content-Type", "application/json");
    }

    @Test
    void springConfigurationCreatesWebhookDeliveryClient() {
        new ApplicationContextRunner()
                .withUserConfiguration(NotificationWebhookConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(WebhookDeliveryClient.class));
    }

    @Test
    void disabledWebhookDoesNotTouchRuntimeTransport() {
        WebhookDeliveryClient client = mock(WebhookDeliveryClient.class);
        WebhookNotificationChannelExecutor disabledExecutor =
                new WebhookNotificationChannelExecutor(runtimeConfig, objectMapper, client);
        when(runtimeConfig.booleanValue(WebhookNotificationChannelExecutor.ENABLED_KEY, false)).thenReturn(false);

        disabledExecutor.execute(command(), "https://example.invalid/hook");

        verifyNoInteractions(client);
    }

    private void enableWebhook() {
        when(runtimeConfig.booleanValue(WebhookNotificationChannelExecutor.ENABLED_KEY, false)).thenReturn(true);
        when(runtimeConfig.durationValue(
                WebhookNotificationChannelExecutor.REQUEST_TIMEOUT_KEY,
                WebhookNotificationChannelExecutor.DEFAULT_REQUEST_TIMEOUT))
                .thenReturn(Duration.ofSeconds(3));
        when(runtimeConfig.intValue(
                WebhookNotificationChannelExecutor.MAX_RETRIES_KEY,
                WebhookNotificationChannelExecutor.DEFAULT_MAX_RETRIES))
                .thenReturn(2);
        when(runtimeConfig.intValue(
                WebhookNotificationChannelExecutor.MAX_PAYLOAD_BYTES_KEY,
                WebhookNotificationChannelExecutor.DEFAULT_MAX_PAYLOAD_BYTES))
                .thenReturn(4096);
        when(runtimeConfig.stringValue(
                WebhookNotificationChannelExecutor.USER_AGENT_KEY,
                WebhookNotificationChannelExecutor.DEFAULT_USER_AGENT))
                .thenReturn("IRCS-Test-Webhook/1.0");
        when(runtimeConfig.booleanValue(
                WebhookNotificationChannelExecutor.ALLOW_PRIVATE_ADDRESSES_KEY,
                false))
                .thenReturn(false);
    }

    private static NotificationCommand command() {
        return NotificationCommand.builder()
                .commandId("cmd-1")
                .correlationId("incident-1")
                .channel(NotificationChannel.WEBHOOK)
                .recipients(List.of("https://example.invalid/hook"))
                .subject("IRCS alert")
                .content("incident opened")
                .variables(Map.of("severity", "HIGH"))
                .channelOptions(Map.of("headers", Map.of(
                        "X-Webhook-Signature", "sig-1",
                        "Host", "evil.example.invalid")))
                .build();
    }

    private static final class FakeWebhookDeliveryClient implements WebhookDeliveryClient {

        private final List<WebhookDeliveryRequest> requests = new ArrayList<>();
        private final Queue<Object> responses = new ArrayDeque<>();

        void enqueue(Object response) {
            responses.add(response);
        }

        @Override
        public WebhookDeliveryResponse post(WebhookDeliveryRequest request) throws IOException {
            requests.add(request);
            Object response = responses.remove();
            if (response instanceof IOException ex) {
                throw ex;
            }
            return (WebhookDeliveryResponse) response;
        }
    }

    private static final class FakeOutboundTransport extends com.prodigalgal.ircs.common.outbound.OutboundHttpClient {

        private final List<com.prodigalgal.ircs.common.outbound.OutboundHttpRequest> requests = new ArrayList<>();
        private final WebhookDeliveryResponse response;

        FakeOutboundTransport(WebhookDeliveryResponse response) {
            super(
                    new com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy(host -> List.of()),
                    request -> new com.prodigalgal.ircs.common.outbound.OutboundHttpResponse(
                            response.statusCode(),
                            Map.of(),
                            new byte[0]));
            this.response = response;
        }

        @Override
        public com.prodigalgal.ircs.common.outbound.OutboundHttpResponse execute(
                com.prodigalgal.ircs.common.outbound.OutboundHttpRequest request) {
            requests.add(request);
            return new com.prodigalgal.ircs.common.outbound.OutboundHttpResponse(
                    response.statusCode(),
                    Map.of(),
                    new byte[0]);
        }
    }
}
