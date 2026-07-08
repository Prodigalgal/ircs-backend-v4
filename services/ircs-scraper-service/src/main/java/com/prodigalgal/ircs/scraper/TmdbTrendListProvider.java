package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.trend.TrendItemPayload;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
class TmdbTrendListProvider implements TrendListProvider {

    private static final String TMDB_API_BASE = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500";

    private final ScraperTrendConfigValues configValues;
    private final TrendProviderHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TmdbCredentialResolver credentialResolver;

    @Override
    public String name() {
        return "TMDB";
    }

    @Override
    public List<TrendItemPayload> fetchTrending() {
        if (!configValues.tmdbEnabled()) {
            return List.of();
        }
        String apiKey = credentialResolver.resolveApiKey().orElse(null);
        if (!StringUtils.hasText(apiKey)) {
            return List.of();
        }
        List<TrendItemPayload> items = new ArrayList<>();
        fetchEndpoint("movie", apiKey, items);
        fetchEndpoint("tv", apiKey, items);
        int max = configValues.maxProviderItems();
        return max <= 0 || items.size() <= max ? items : List.copyOf(items.subList(0, max));
    }

    private void fetchEndpoint(String type, String apiKey, List<TrendItemPayload> items) {
        String url = UriComponentsBuilder.fromUriString(TMDB_API_BASE + "/trending/" + type + "/week")
                .queryParam("api_key", apiKey)
                .queryParam("language", "zh-CN")
                .toUriString();
        try {
            JsonNode root = objectMapper.readTree(httpClient.get(url));
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return;
            }
            for (JsonNode node : results) {
                String title = "movie".equals(type) ? node.path("title").asText() : node.path("name").asText();
                String originalTitle = "movie".equals(type)
                        ? node.path("original_title").asText()
                        : node.path("original_name").asText();
                String dateText = "movie".equals(type)
                        ? node.path("release_date").asText()
                        : node.path("first_air_date").asText();
                LocalDate publishedAt = localDate(dateText);
                String posterPath = node.path("poster_path").asText();
                items.add(new TrendItemPayload(
                        title,
                        originalTitle,
                        node.path("overview").asText(),
                        publishedAt == null ? null : String.valueOf(publishedAt.getYear()),
                        publishedAt,
                        BigDecimal.valueOf(node.path("vote_average").asDouble()),
                        StringUtils.hasText(posterPath) ? IMAGE_BASE_URL + posterPath : null,
                        node.path("id").asText(),
                        null,
                        null,
                        type));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("TMDB trend parse failed for " + type + ": " + ex.getMessage(), ex);
        }
    }

    private LocalDate localDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
