package com.prodigalgal.ircs.contentsafety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentItem;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentModelResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
class AdultModelClassifierClient {

    private static final String CONTENT_TYPE_JSON = "application/json";

    private final ObjectMapper objectMapper;
    private final ContentSafetyProperties properties;

    Map<UUID, AdultAssessmentModelResult> classify(List<AdultAssessmentItem> items) {
        ContentSafetyProperties.Model model = properties.adult().model();
        if (!model.enabled() || !StringUtils.hasText(model.endpoint()) || items == null || items.isEmpty()) {
            return Map.of();
        }
        try {
            ModelBatchRequest request = new ModelBatchRequest(items.stream()
                    .map(item -> new ModelTextItem(
                            item.id(),
                            adultText(item, properties.adult().maxTextLength())))
                    .toList());
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(model.endpoint().trim()))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(timeout(model.requestTimeout()))
                    .header("Accept", CONTENT_TYPE_JSON)
                    .header("Content-Type", CONTENT_TYPE_JSON)
                    .header(InternalServiceAuthHeaders.SERVICE_ID, safe(model.serviceId()))
                    .header(InternalServiceAuthHeaders.SERVICE_TOKEN, safe(model.serviceToken()))
                    .header(InternalServiceAuthHeaders.SERVICE_SCOPES, safe(model.scopes()))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(timeout(model.requestTimeout()))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("adult model classifier returned status={}", response.statusCode());
                return Map.of();
            }
            ModelBatchResponse body = objectMapper.readValue(response.body(), ModelBatchResponse.class);
            Map<UUID, AdultAssessmentModelResult> results = new HashMap<>();
            for (ModelTextResult result : body.items()) {
                if (result.id() == null) {
                    continue;
                }
                results.put(result.id(), new AdultAssessmentModelResult(
                        model.name(),
                        model.version(),
                        true,
                        clampScore(result.adultScore()),
                        StringUtils.hasText(result.label()) ? result.label() : label(result.adultScore(), model),
                        result.raw()));
            }
            return results;
        } catch (IOException | InterruptedException | IllegalArgumentException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("adult model classifier unavailable: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static String label(double adultScore, ContentSafetyProperties.Model model) {
        if (adultScore >= model.adultThreshold()) {
            return "ADULT";
        }
        return adultScore >= model.suspectThreshold() ? "SUSPECT" : "SAFE";
    }

    private static double clampScore(double score) {
        if (Double.isNaN(score) || score < 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, score);
    }

    private static Duration timeout(Duration value) {
        return value == null || value.isNegative() || value.isZero() ? Duration.ofSeconds(3) : value;
    }

    private static String adultText(AdultAssessmentItem item, int maxTextLength) {
        String joined = String.join("\n",
                safe(item.title()),
                safe(item.aliasTitle()),
                safe(item.subtitle()),
                safe(item.description()),
                safe(item.remarks()),
                String.join(" ", item.genreCodes()),
                String.join(" ", item.actorNames()),
                String.join(" ", item.directorNames()),
                item.sources().stream()
                        .map(source -> String.join(" ",
                                safe(source.dataSourceName()),
                                safe(source.sourceDomain()),
                                safe(source.sourceCategoryName()),
                                safe(source.rawMetadata())))
                        .reduce("", (left, right) -> left + "\n" + right));
        int max = maxTextLength <= 0 ? 4096 : maxTextLength;
        return joined.length() <= max ? joined : joined.substring(0, max);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    record ModelBatchRequest(List<ModelTextItem> items) {
    }

    record ModelTextItem(UUID id, String text) {
    }

    record ModelBatchResponse(List<ModelTextResult> items) {
        ModelBatchResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record ModelTextResult(UUID id, double adultScore, String label, Map<String, Object> raw) {
        ModelTextResult {
            raw = raw == null ? Map.of() : Map.copyOf(raw);
        }
    }
}
