package com.prodigalgal.ircs.ops.dashboard.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class DashboardSearchIndexStatsClient {

    static final String RAW_INDEX = "ircs_raw_video";
    static final String UNIFIED_INDEX = "ircs_unified_video";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final Duration requestTimeout;

    public DashboardSearchIndexStatsClient(
            ObjectMapper objectMapper,
            @Value("${spring.elasticsearch.uris:${ELASTICSEARCH_URIS:http://localhost:9200}}") String elasticsearchUris,
            @Value("${spring.elasticsearch.username:${ELASTICSEARCH_USERNAME:}}") String username,
            @Value("${spring.elasticsearch.password:${ELASTICSEARCH_PASSWORD:}}") String password,
            @Value("${app.ops.dashboard.search-index.request-timeout:PT2S}") Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(normalizeTimeout(requestTimeout))
                .build();
        this.baseUrl = firstUri(elasticsearchUris);
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.requestTimeout = normalizeTimeout(requestTimeout);
    }

    public SearchIndexCounts currentCounts() {
        return new SearchIndexCounts(
                count(RAW_INDEX),
                count(UNIFIED_INDEX));
    }

    private OptionalLong count(String indexName) {
        if (!StringUtils.hasText(baseUrl)) {
            return OptionalLong.empty();
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(countUri(indexName))
                    .timeout(requestTimeout)
                    .GET()
                    .header("Accept", "application/json");
            basicAuthHeader().ifPresent(value -> builder.header("Authorization", value));
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.debug("Elasticsearch count request failed: index={}, status={}", indexName, response.statusCode());
                return OptionalLong.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode count = root.get("count");
            return count == null || !count.canConvertToLong()
                    ? OptionalLong.empty()
                    : OptionalLong.of(count.asLong());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("Elasticsearch count request interrupted: index={}, reason={}", indexName, ex.getMessage());
            return OptionalLong.empty();
        } catch (Exception ex) {
            log.debug("Elasticsearch count request failed: index={}, reason={}", indexName, ex.getMessage());
            return OptionalLong.empty();
        }
    }

    private URI countUri(String indexName) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + "/" + URLEncoder.encode(indexName, StandardCharsets.UTF_8) + "/_count");
    }

    private java.util.Optional<String> basicAuthHeader() {
        if (!StringUtils.hasText(username)) {
            return java.util.Optional.empty();
        }
        String token = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return java.util.Optional.of("Basic " + token);
    }

    private static String firstUri(String elasticsearchUris) {
        if (!StringUtils.hasText(elasticsearchUris)) {
            return "";
        }
        String[] parts = elasticsearchUris.split(",");
        return parts.length == 0 ? "" : parts[0].trim();
    }

    private static Duration normalizeTimeout(Duration value) {
        return value == null || !value.isPositive() ? Duration.ofSeconds(2) : value;
    }

    public record SearchIndexCounts(OptionalLong rawCount, OptionalLong unifiedCount) {
    }
}
