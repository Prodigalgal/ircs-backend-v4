package com.prodigalgal.ircs.magnet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class YtsBzMagnetProviderSearchRunner implements MagnetProviderSearchRunner {

    private static final String HTTP_ERROR = "YTS_BZ_HTTP_ERROR";
    private static final String PARSE_FAILURE = "YTS_BZ_PARSE_FAILURE";
    private static final String TIMEOUT = "YTS_BZ_TIMEOUT";
    private static final String UNSUPPORTED_QUERY = "YTS_BZ_UNSUPPORTED_QUERY";

    private final ObjectMapper objectMapper;
    private final YtsBzHttpClient httpClient;
    private final MagnetProviderTrafficLimiter trafficLimiter;
    YtsBzMagnetProviderSearchRunner(
            ObjectMapper objectMapper,
            MagnetProviderTrafficLimiter trafficLimiter,
            ObjectProvider<YtsBzHttpClient> httpClientProvider) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient(httpClientProvider);
        this.trafficLimiter = trafficLimiter;
    }

    @Override
    public MagnetProviderSearchResult search(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            UUID unifiedVideoId) {
        if (!isSupportedQuery(query)) {
            throw new MagnetProviderRunnerException(UNSUPPORTED_QUERY, null, null);
        }

        String requestUrl = buildRequestUrl(provider, query);
        YtsBzHttpResponse response;
        try {
            trafficLimiter.acquireProviderSlot(provider);
            response = httpClient.get(URI.create(requestUrl), timeout(provider));
        } catch (HttpTimeoutException ex) {
            throw new MagnetProviderRunnerException(TIMEOUT, requestUrl, null);
        } catch (IOException ex) {
            throw new MagnetProviderRunnerException(HTTP_ERROR, requestUrl, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MagnetProviderRunnerException(TIMEOUT, requestUrl, null);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MagnetProviderRunnerException(HTTP_ERROR, requestUrl, response.statusCode());
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            return new MagnetProviderSearchResult(
                    requestUrl,
                    response.statusCode(),
                    parseCandidates(provider, query, root));
        } catch (Exception ex) {
            throw new MagnetProviderRunnerException(PARSE_FAILURE, requestUrl, response.statusCode());
        }
    }

    private List<MagnetProviderCandidate> parseCandidates(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            JsonNode root) {
        JsonNode movies = root.path("data").path("movies");
        if (!movies.isArray()) {
            return List.of();
        }

        List<MagnetProviderCandidate> candidates = new ArrayList<>();
        for (JsonNode movie : movies) {
            String imdbCode = text(movie, "imdb_code");
            if (requiresExactImdbMatch(query) && !query.value().equalsIgnoreCase(imdbCode)) {
                continue;
            }
            String movieTitle = firstText(movie, "title_long", "title").orElse("YTS Torrent");
            JsonNode torrents = movie.path("torrents");
            if (!torrents.isArray()) {
                continue;
            }
            for (JsonNode torrent : torrents) {
                toCandidate(provider, query, movieTitle, movie, torrent).ifPresent(candidates::add);
            }
        }
        return candidates;
    }

    private Optional<MagnetProviderCandidate> toCandidate(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            String movieTitle,
            JsonNode movie,
            JsonNode torrent) {
        Optional<String> infoHash = MagnetUriUtils.normalizeInfoHash(text(torrent, "hash"));
        if (infoHash.isEmpty()) {
            return Optional.empty();
        }

        String quality = text(torrent, "quality");
        String type = text(torrent, "type");
        String title = buildTitle(movieTitle, quality, type);
        List<String> tags = tags(quality, type, text(torrent, "video_codec"));

        return Optional.of(new MagnetProviderCandidate(
                infoHash.get(),
                MagnetUriUtils.buildMagnetUri(infoHash.get(), title),
                title,
                longValue(torrent, "size_bytes"),
                text(torrent, "size"),
                parseInstant(text(torrent, "date_uploaded")),
                intValue(torrent, "seeds"),
                intValue(torrent, "peers"),
                quality,
                quality,
                query.type(),
                query.value(),
                matchScore(query),
                text(movie, "url"),
                tags,
                providerEvidence(provider, query, movie, torrent)));
    }

    private Map<String, Object> providerEvidence(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            JsonNode movie,
            JsonNode torrent) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("providerType", "YTS_BZ");
        evidence.put("providerCode", provider == null ? null : provider.code());
        evidence.put("queryType", query == null ? null : query.type());
        evidence.put("queryValue", query == null ? null : query.value());
        evidence.put("movieId", text(movie, "id"));
        evidence.put("imdbCode", text(movie, "imdb_code"));
        evidence.put("torrentHash", text(torrent, "hash"));
        return evidence;
    }

    private boolean isSupportedQuery(MagnetExternalIdQuery query) {
        if (query == null || query.value() == null || query.value().isBlank()) {
            return false;
        }
        return "IMDB".equalsIgnoreCase(query.type())
                || "TITLE".equalsIgnoreCase(query.type())
                || "TITLE_YEAR".equalsIgnoreCase(query.type());
    }

    private boolean requiresExactImdbMatch(MagnetExternalIdQuery query) {
        return query != null && "IMDB".equalsIgnoreCase(query.type());
    }

    private int matchScore(MagnetExternalIdQuery query) {
        if (query == null) {
            return 0;
        }
        if ("IMDB".equalsIgnoreCase(query.type())) {
            return 100;
        }
        if ("TITLE_YEAR".equalsIgnoreCase(query.type())) {
            return 82;
        }
        return 72;
    }

    private String buildRequestUrl(MagnetProviderSummary provider, MagnetExternalIdQuery query) {
        String base = provider == null ? "" : provider.baseUrl();
        if (base == null || base.isBlank()) {
            throw new MagnetProviderRunnerException(HTTP_ERROR, null, null);
        }
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalizedBase
                + "/list_movies.json?query_term="
                + MagnetUriUtils.encodeQueryValue(query.value())
                + "&limit="
                + safeLimit(provider);
    }

    private int safeLimit(MagnetProviderSummary provider) {
        int limit = provider == null || provider.resultLimit() == null ? 20 : provider.resultLimit();
        return Math.max(1, Math.min(limit, 50));
    }

    private Duration timeout(MagnetProviderSummary provider) {
        int timeoutMs = provider == null || provider.timeoutMs() == null ? 10000 : provider.timeoutMs();
        return Duration.ofMillis(Math.max(1, timeoutMs));
    }

    private Optional<String> firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private String buildTitle(String movieTitle, String quality, String type) {
        List<String> parts = new ArrayList<>();
        parts.add(movieTitle == null || movieTitle.isBlank() ? "YTS Torrent" : movieTitle);
        addTag(parts, quality);
        addTag(parts, type);
        return String.join(" ", parts);
    }

    private List<String> tags(String... values) {
        List<String> tags = new ArrayList<>();
        for (String value : values) {
            addTag(tags, value);
        }
        return tags;
    }

    private void addTag(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value != null && value.canConvertToLong() ? value.asLong() : null;
    }

    private Integer intValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException ignored) {
            // 兼容 YTS 常见的 "yyyy-MM-dd HH:mm:ss" UTC 时间格式。
        }
        try {
            return OffsetDateTime.parse(normalized.replace(" ", "T") + "Z").toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地日期时间格式。
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static YtsBzHttpClient httpClient(ObjectProvider<YtsBzHttpClient> httpClientProvider) {
        if (httpClientProvider != null) {
            YtsBzHttpClient provided = httpClientProvider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new SharedOutboundYtsBzHttpClient();
    }

    interface YtsBzHttpClient {
        YtsBzHttpResponse get(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    record YtsBzHttpResponse(int statusCode, String body) {
    }

    static class SharedOutboundYtsBzHttpClient implements YtsBzHttpClient {

        private final OutboundHttpClient httpClient;

        SharedOutboundYtsBzHttpClient() {
            this(new OutboundHttpClient(
                    new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                    new JdkOutboundTransport()));
        }

        SharedOutboundYtsBzHttpClient(OutboundHttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public YtsBzHttpResponse get(URI uri, Duration timeout) throws IOException, InterruptedException {
            OutboundHttpResponse response = httpClient.execute(OutboundHttpRequest.get(
                    uri.toString(),
                    OutboundHttpPolicy.publicFetch(timeout)));
            return new YtsBzHttpResponse(response.statusCode(), response.bodyAsUtf8());
        }
    }
}
