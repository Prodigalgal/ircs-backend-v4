package com.prodigalgal.ircs.normalization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundCircuitOpenException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class LlmCredentialResolver {

    private static final String CREDENTIAL_SERVICE_CIRCUIT_KEY = "normalization-llm-credential-service";

    private final NormalizationConfigValues configValues;
    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;

    Optional<LlmCredential> resolve() {
        Optional<LlmCredential> runtime = resolveRuntimeCredential();
        if (runtime.isPresent()) {
            return runtime;
        }
        return resolveCredentialServiceLease();
    }

    private Optional<LlmCredential> resolveRuntimeCredential() {
        String apiKey = configValues.llmRuntimeApiKey();
        if (!StringUtils.hasText(apiKey)) {
            return Optional.empty();
        }
        return Optional.of(new LlmCredential(
                apiKey.trim(),
                configValues.llmRuntimeBaseUrl(),
                configValues.llmModel(),
                "runtime"));
    }

    private Optional<LlmCredential> resolveCredentialServiceLease() {
        String baseUrl = configValues.llmCredentialServiceBaseUrl();
        String token = configValues.llmCredentialServiceToken();
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(token)) {
            return Optional.empty();
        }

        try {
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(
                            Duration.ofSeconds(configValues.llmRequestTimeoutSeconds()))
                    .withCallerCircuitBreakerKey(CREDENTIAL_SERVICE_CIRCUIT_KEY);
            OutboundHttpRequest request = OutboundHttpRequest.get(leaseUri(baseUrl), policy)
                    .withHeader("Accept", "application/json");
            request = InternalServiceAuthHeaders.apply(
                    request,
                    configValues.llmCredentialServiceId(),
                    token,
                    configValues.llmCredentialServiceScopes());
            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmCleaningException.ProviderError(
                        "credential-service returned HTTP " + response.statusCode());
            }
            List<ProviderCredentialLease> leases = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            return leases.stream()
                    .map(this::toCredential)
                    .flatMap(Optional::stream)
                    .findFirst();
        } catch (OutboundCircuitOpenException ex) {
            throw new LlmCleaningException.ProviderError("credential-service outbound circuit open", ex);
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new LlmCleaningException.ProviderTimeout("credential-service timed out", ex);
        } catch (IOException ex) {
            throw new LlmCleaningException.ProviderError("credential-service I/O error", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmCleaningException.ProviderError("credential-service call interrupted", ex);
        }
    }

    private URI leaseUri(String baseUrl) {
        String normalized = baseUrl.trim().endsWith("/") ? baseUrl.trim() : baseUrl.trim() + "/";
        String provider = URLEncoder.encode(configValues.llmProvider(), StandardCharsets.UTF_8);
        return URI.create(normalized)
                .resolve("internal/credentials/providers/%s/leases?requiredPayloadKey=api_key&limit=1".formatted(provider));
    }

    private Optional<LlmCredential> toCredential(ProviderCredentialLease lease) {
        if (lease == null) {
            return Optional.empty();
        }
        Map<String, String> payload = lease.getSecretPayload();
        if (payload == null || !StringUtils.hasText(payload.get("api_key"))) {
            return Optional.empty();
        }
        String baseUrl = StringUtils.hasText(payload.get("base_url"))
                ? payload.get("base_url").trim()
                : NormalizationConfigValues.DEFAULT_OPENAI_BASE_URL;
        return Optional.of(new LlmCredential(
                payload.get("api_key").trim(),
                baseUrl,
                configValues.llmModel(),
                "credential-service"));
    }
}
