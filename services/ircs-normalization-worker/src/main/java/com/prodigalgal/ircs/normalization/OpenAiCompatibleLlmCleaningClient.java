package com.prodigalgal.ircs.normalization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class OpenAiCompatibleLlmCleaningClient implements LlmCleaningClient {

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");

    private final ObjectMapper objectMapper;
    private final NormalizationConfigValues configValues;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public List<LlmCleaningDecision> analyzeAndMap(
            List<String> rawItems,
            Set<String> validStandardItems,
            LlmCleaningKind kind,
            LlmCredential credential) {
        String prompt = buildPrompt(rawItems, validStandardItems, kind);
        try {
            HttpRequest request = HttpRequest.newBuilder(chatCompletionsUri(credential.baseUrl()))
                    .timeout(Duration.ofSeconds(configValues.llmRequestTimeoutSeconds()))
                    .header("Authorization", "Bearer " + credential.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt, credential.model())))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmCleaningException.ProviderError(
                        "LLM provider returned HTTP " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new LlmCleaningException.ProviderTimeout("LLM provider timed out", ex);
        } catch (IOException ex) {
            throw new LlmCleaningException.ProviderError("LLM provider I/O error", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmCleaningException.ProviderError("LLM provider call interrupted", ex);
        }
    }

    private URI chatCompletionsUri(String baseUrl) {
        String safeBaseUrl = StringUtils.hasText(baseUrl) ? baseUrl.trim() : NormalizationConfigValues.DEFAULT_OPENAI_BASE_URL;
        String normalized = safeBaseUrl.endsWith("/") ? safeBaseUrl : safeBaseUrl + "/";
        return URI.create(normalized).resolve("chat/completions");
    }

    private String requestBody(String prompt, String model) throws IOException {
        return objectMapper.writeValueAsString(Map.of(
                "model", StringUtils.hasText(model) ? model : NormalizationConfigValues.DEFAULT_LLM_MODEL,
                "temperature", 0,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", prompt))));
    }

    private String buildPrompt(List<String> rawItems, Set<String> validStandardItems, LlmCleaningKind kind) {
        return """
                Map raw %s values to one of the valid standard values, or mark noise.
                Return only a JSON array of objects: [{"raw":"...","standard":"...","noise":false}].
                For noise use {"raw":"...","standard":null,"noise":true}.
                validStandardItems=%s
                rawItems=%s
                """.formatted(
                kind.name().toLowerCase(),
                toJson(validStandardItems),
                toJson(rawItems));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ignored) {
            return "[]";
        }
    }

    private List<LlmCleaningDecision> parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (!StringUtils.hasText(content)) {
                throw new LlmCleaningException.ProviderError("LLM provider returned empty content");
            }
            return objectMapper.readValue(extractJsonArray(content), new TypeReference<>() {
            });
        } catch (LlmCleaningException.ProviderError ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmCleaningException.ProviderError("LLM provider response parse error", ex);
        }
    }

    private String extractJsonArray(String responseContent) {
        String candidate = responseContent.trim();
        Matcher fencedBlockMatcher = CODE_FENCE_PATTERN.matcher(candidate);
        if (fencedBlockMatcher.find()) {
            candidate = fencedBlockMatcher.group(1).trim();
        }

        int arrayStart = candidate.indexOf('[');
        int arrayEnd = candidate.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd <= arrayStart) {
            throw new LlmCleaningException.ProviderError("No JSON array found in LLM response");
        }
        return candidate.substring(arrayStart, arrayEnd + 1).trim();
    }
}
