package com.prodigalgal.ircs.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicyType;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmCredentialResolverTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final NormalizationConfigValues configValues = org.mockito.Mockito.mock(NormalizationConfigValues.class);
    private String previousCredentialCircuitFailureThreshold;
    private String previousCredentialCircuitOpenDuration;

    @BeforeEach
    void setUp() {
        when(configValues.llmRuntimeBaseUrl()).thenReturn("https://runtime-llm.example.test/v1");
        when(configValues.llmModel()).thenReturn("runtime-model");
        when(configValues.llmProvider()).thenReturn("OPENAI");
        when(configValues.llmCredentialServiceBaseUrl()).thenReturn("http://credential-service:8080/");
        when(configValues.llmCredentialServiceToken()).thenReturn(" service-token ");
        when(configValues.llmCredentialServiceId()).thenReturn("normalization-worker");
        when(configValues.llmCredentialServiceScopes()).thenReturn("credential:lease");
        when(configValues.llmRequestTimeoutSeconds()).thenReturn(7L);
    }

    @AfterEach
    void restoreCircuitProperties() {
        restoreProperty(
                "ircs.outbound.circuit.normalization-llm-credential-service.failure-threshold",
                previousCredentialCircuitFailureThreshold);
        restoreProperty(
                "ircs.outbound.circuit.normalization-llm-credential-service.open-duration-ms",
                previousCredentialCircuitOpenDuration);
    }

    @Test
    void runtimeCredentialWinsBeforeCredentialServiceLease() {
        when(configValues.llmRuntimeApiKey()).thenReturn(" sk-runtime ");
        FakeTransport transport = new FakeTransport();
        LlmCredentialResolver resolver = resolver(transport);

        var credential = resolver.resolve();

        assertThat(credential).isPresent();
        assertThat(credential.orElseThrow().apiKey()).isEqualTo("sk-runtime");
        assertThat(credential.orElseThrow().baseUrl()).isEqualTo("https://runtime-llm.example.test/v1");
        assertThat(credential.orElseThrow().source()).isEqualTo("runtime");
        assertThat(transport.requests).isEmpty();
        verify(configValues, never()).llmCredentialServiceToken();
    }

    @Test
    void credentialServiceLeaseUsesInternalServicePolicyAndServiceIdentity() {
        when(configValues.llmRuntimeApiKey()).thenReturn("");
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(200, """
                [{"provider":"OPENAI","secretPayload":{"api_key":"sk-lease","base_url":"https://lease-llm.example.test/v1"}}]
                """));
        LlmCredentialResolver resolver = resolver(transport);

        var credential = resolver.resolve();

        assertThat(credential).isPresent();
        assertThat(credential.orElseThrow().apiKey()).isEqualTo("sk-lease");
        assertThat(credential.orElseThrow().baseUrl()).isEqualTo("https://lease-llm.example.test/v1");
        assertThat(credential.orElseThrow().model()).isEqualTo("runtime-model");
        assertThat(credential.orElseThrow().source()).isEqualTo("credential-service");

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.uri().toString())
                .isEqualTo("http://credential-service:8080/internal/credentials/providers/OPENAI/leases?requiredPayloadKey=api_key&limit=1");
        assertThat(sent.policy().type()).isEqualTo(OutboundHttpPolicyType.INTERNAL_SERVICE);
        assertThat(sent.policy().timeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("normalization-llm-credential-service");
        assertThat(sent.headers())
                .containsEntry("Accept", "application/json")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_ID, "normalization-worker")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_TOKEN, "service-token")
                .containsEntry(InternalServiceAuthHeaders.SERVICE_SCOPES, "credential:lease");
    }

    @Test
    void non2xxCredentialServiceResponseKeepsStableErrorSemantics() {
        when(configValues.llmRuntimeApiKey()).thenReturn("");
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(503, "unavailable"));
        transport.enqueue(response(503, "still unavailable"));
        LlmCredentialResolver resolver = resolver(transport);

        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(LlmCleaningException.ProviderError.class)
                .hasMessage("credential-service returned HTTP 503");
    }

    @Test
    void outboundCircuitOpenKeepsStableCredentialServiceError() {
        when(configValues.llmRuntimeApiKey()).thenReturn("");
        lowerCredentialCircuitThreshold();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(503, "unavailable"));
        transport.enqueue(response(503, "still unavailable"));
        LlmCredentialResolver resolver = resolver(transport);

        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(LlmCleaningException.ProviderError.class)
                .hasMessage("credential-service returned HTTP 503");
        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(LlmCleaningException.ProviderError.class)
                .hasMessage("credential-service outbound circuit open");
        assertThat(transport.requests).hasSize(2);
    }

    private LlmCredentialResolver resolver(FakeTransport transport) {
        return new LlmCredentialResolver(
                configValues,
                OBJECT_MAPPER,
                new OutboundHttpClient(new OutboundUrlPolicy(host -> {
                    throw new AssertionError("INTERNAL_SERVICE must not perform public DNS SSRF resolution");
                }), transport));
    }

    private static OutboundHttpResponse response(int status, String body) {
        return new OutboundHttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private void lowerCredentialCircuitThreshold() {
        previousCredentialCircuitFailureThreshold =
                System.getProperty("ircs.outbound.circuit.normalization-llm-credential-service.failure-threshold");
        previousCredentialCircuitOpenDuration =
                System.getProperty("ircs.outbound.circuit.normalization-llm-credential-service.open-duration-ms");
        System.setProperty("ircs.outbound.circuit.normalization-llm-credential-service.failure-threshold", "1");
        System.setProperty("ircs.outbound.circuit.normalization-llm-credential-service.open-duration-ms", "60000");
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
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
