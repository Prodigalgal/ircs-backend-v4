package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.prodigalgal.ircs.contracts.ingestion.IngestionEpisodeDTO;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.contracts.ingestion.IngestionPlaylistDTO;
import com.prodigalgal.ircs.contracts.ingestion.IngestionVideoDTO;
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.DirectScrapeItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ScrapedVideoDraft;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class ScraperMappingService {

    private final ObjectMapper objectMapper;

    ScraperMappingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    IngestionItem directItem(DirectScrapeItem item, DataSourceRecord fallbackSource, boolean forceIngest) {
        UUID dataSourceId = item.dataSourceId() == null ? fallbackSource.id() : item.dataSourceId();
        ScrapedVideoDraft draft = new ScrapedVideoDraft(
                dataSourceId,
                item.sourceVid(),
                item.title(),
                item.aliasTitle(),
                item.description(),
                item.coverImageUrl(),
                item.year(),
                item.area(),
                item.language(),
                item.remarks(),
                item.score(),
                item.publishedAt(),
                item.totalEpisodes(),
                item.duration(),
                item.doubanId(),
                item.tmdbId(),
                item.imdbId(),
                item.rottenTomatoesId(),
                textFromRawMetadata(item.rawMetadata(), "rawTypeId", "raw_type_id", "typeId", "type_id", "categoryId", "category_id").orElse(null),
                textFromRawMetadata(item.rawMetadata(), "rawTypeName", "raw_type_name", "typeName", "type_name", "categoryName", "category_name").orElse(null),
                listFromRawMetadata(item.rawMetadata(), "genreNames", "genres", "genre"),
                listFromRawMetadata(item.rawMetadata(), "actorNames", "actors", "actor"),
                listFromRawMetadata(item.rawMetadata(), "directorNames", "directors", "director"),
                null,
                item.rawMetadata(),
                item.playlists() == null ? List.of() : item.playlists());
        return toItem(draft, forceIngest);
    }

    ScrapedVideoDraft mapDetail(String jsonBody, DataSourceRecord source) {
        try {
            JsonNode root = objectMapper.readTree(source.fieldMapping());
            JsonNode mapping = root.has("detail_mapping") ? root.get("detail_mapping") : root;
            Object document = Configuration.defaultConfiguration().jsonProvider().parse(jsonBody);
            String sourceVid = string(document, mapping, "source_vid", "sourceVid")
                    .orElseThrow(() -> new IllegalArgumentException("missing source_vid mapping"));
            String title = string(document, mapping, "title")
                    .orElseThrow(() -> new IllegalArgumentException("missing title mapping"));
            String playlistFrom = string(document, mapping, "playlist_from").orElse(null);
            String playlistUrl = string(document, mapping, "playlist_url").orElse(null);
            String coverImageUrl = string(document, mapping, "coverImageUrl", "cover_image_url", "cover")
                    .map(url -> normalizeUrl(url, source.baseUrl()))
                    .orElse(null);
            return new ScrapedVideoDraft(
                    source.id(),
                    sourceVid,
                    title,
                    string(document, mapping, "aliasTitle", "alias_title", "subTitle", "sub_title", "alias").orElse(null),
                    string(document, mapping, "description").orElse(null),
                    coverImageUrl,
                    string(document, mapping, "year").orElse(null),
                    string(document, mapping, "area").orElse(null),
                    string(document, mapping, "language", "lang").orElse(null),
                    string(document, mapping, "remarks").orElse(null),
                    decimal(document, mapping, "score").orElse(null),
                    date(document, mapping, "publishedAt").orElse(null),
                    string(document, mapping, "totalEpisodes").orElse(null),
                    string(document, mapping, "duration").orElse(null),
                    string(document, mapping, "doubanId").orElse(null),
                    string(document, mapping, "tmdbId").orElse(null),
                    string(document, mapping, "imdbId").orElse(null),
                    string(document, mapping, "rottenTomatoesId").orElse(null),
                    string(document, mapping, "rawTypeId", "raw_type_id", "typeId", "type_id").orElse(null),
                    string(document, mapping, "rawTypeName", "raw_type_name", "typeName", "type_name", "categoryName", "category_name").orElse(null),
                    list(document, mapping, "genreNames", "genres", "genre"),
                    list(document, mapping, "actorNames", "actors", "actor"),
                    list(document, mapping, "directorNames", "directors", "director"),
                    jsonBody,
                    null,
                    parsePlaylists(playlistFrom, playlistUrl));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to map detail JSON for " + source.name() + ": " + e.getMessage(), e);
        }
    }

    IngestionItem toItem(ScrapedVideoDraft draft, boolean forceIngest) {
        String rawMetadata = canonicalRawMetadata(draft);
        IngestionVideoDTO video = new IngestionVideoDTO(
                draft.sourceVid(),
                sha256(draft.dataSourceId() + ":" + draft.sourceVid()),
                sha256(String.join("|",
                        value(draft.title()),
                        value(draft.coverImageUrl()),
                        value(draft.year()),
                        value(draft.remarks()),
                        rawMetadata)),
                draft.title(),
                draft.aliasTitle(),
                draft.description(),
                draft.coverImageUrl(),
                draft.year(),
                draft.area(),
                draft.language(),
                draft.remarks(),
                draft.score(),
                draft.publishedAt(),
                draft.totalEpisodes(),
                draft.duration(),
                draft.doubanId(),
                draft.tmdbId(),
                draft.imdbId(),
                draft.rottenTomatoesId(),
                rawMetadata,
                "PENDING",
                draft.dataSourceId(),
                draft.playlists() == null ? List.of() : draft.playlists(),
                0);
        return new IngestionItem(video, forceIngest);
    }

    private String canonicalRawMetadata(ScrapedVideoDraft draft) {
        ObjectNode raw = objectMapper.createObjectNode();
        if (StringUtils.hasText(draft.rawMetadata())) {
            try {
                JsonNode provided = objectMapper.readTree(draft.rawMetadata());
                if (provided.isObject()) {
                    raw.setAll((ObjectNode) provided);
                } else {
                    raw.set("userRawMetadata", provided);
                }
            } catch (Exception ignored) {
                raw.put("userRawMetadata", draft.rawMetadata());
            }
        }

        putText(raw, "dataSourceId", draft.dataSourceId());
        putText(raw, "sourceVid", draft.sourceVid());
        putText(raw, "title", draft.title());
        putText(raw, "aliasTitle", draft.aliasTitle());
        putText(raw, "description", draft.description());
        putText(raw, "coverImageUrl", draft.coverImageUrl());
        putText(raw, "year", draft.year());
        putText(raw, "area", draft.area());
        putText(raw, "language", draft.language());
        putText(raw, "remarks", draft.remarks());
        putText(raw, "score", draft.score());
        putText(raw, "publishedAt", draft.publishedAt());
        putText(raw, "totalEpisodes", draft.totalEpisodes());
        putText(raw, "duration", draft.duration());
        putText(raw, "doubanId", draft.doubanId());
        putText(raw, "tmdbId", draft.tmdbId());
        putText(raw, "imdbId", draft.imdbId());
        putText(raw, "rottenTomatoesId", draft.rottenTomatoesId());
        putText(raw, "rawTypeId", draft.rawTypeId());
        putText(raw, "rawTypeName", draft.rawTypeName());
        putArray(raw, "genreNames", draft.genreNames());
        putArray(raw, "actorNames", draft.actorNames());
        putArray(raw, "directorNames", draft.directorNames());
        if (StringUtils.hasText(draft.sourcePayload())) {
            try {
                raw.set("sourcePayload", objectMapper.readTree(draft.sourcePayload()));
            } catch (Exception ignored) {
                raw.put("sourcePayload", draft.sourcePayload());
            }
        }

        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize canonical rawMetadata", e);
        }
    }

    private Optional<String> string(Object document, JsonNode mapping, String field) {
        return string(document, mapping, new String[] {field});
    }

    private Optional<String> string(Object document, JsonNode mapping, String... fields) {
        for (String field : fields) {
            Optional<String> value = stringFromRule(document, mapping, field);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Optional<String> stringFromRule(Object document, JsonNode mapping, String field) {
        JsonNode rule = mapping.path(field);
        if (!rule.isObject() || !rule.has("path")) {
            return Optional.empty();
        }
        String path = rule.get("path").asText();
        if (!StringUtils.hasText(path)) {
            return Optional.empty();
        }
        try {
            Object value = JsonPath.read(document, path);
            return Optional.ofNullable(value).map(Object::toString).filter(StringUtils::hasText);
        } catch (PathNotFoundException e) {
            return Optional.empty();
        }
    }

    private List<String> list(Object document, JsonNode mapping, String... fields) {
        Set<String> values = new LinkedHashSet<>();
        for (String field : fields) {
            JsonNode rule = mapping.path(field);
            if (!rule.isObject() || !rule.has("path")) {
                continue;
            }
            String path = rule.get("path").asText();
            if (!StringUtils.hasText(path)) {
                continue;
            }
            try {
                collectValues(JsonPath.read(document, path), values);
            } catch (PathNotFoundException ignored) {
            }
        }
        return List.copyOf(values);
    }

    private Optional<BigDecimal> decimal(Object document, JsonNode mapping, String field) {
        return string(document, mapping, field).flatMap(value -> {
            try {
                return Optional.of(new BigDecimal(value));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private Optional<LocalDate> date(Object document, JsonNode mapping, String field) {
        return string(document, mapping, field).flatMap(value -> {
            try {
                String candidate = value.contains(" ") ? value.split(" ")[0] : value;
                return Optional.of(LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    private List<IngestionPlaylistDTO> parsePlaylists(String playlistFrom, String playlistUrl) {
        if (!StringUtils.hasText(playlistFrom) || !StringUtils.hasText(playlistUrl)) {
            return List.of();
        }
        String[] names = playlistFrom.split("\\$\\$\\$");
        String[] groups = playlistUrl.split("\\$\\$\\$");
        List<IngestionPlaylistDTO> playlists = new ArrayList<>();
        for (int i = 0; i < Math.min(names.length, groups.length); i++) {
            List<IngestionEpisodeDTO> episodes = new ArrayList<>();
            for (String segment : groups[i].split("#")) {
                String[] parts = segment.split("\\$", 2);
                if (parts.length == 2 && StringUtils.hasText(parts[1])) {
                    episodes.add(new IngestionEpisodeDTO(
                            StringUtils.hasText(parts[0]) ? parts[0] : "Episode " + (episodes.size() + 1),
                            parts[1],
                            null));
                }
            }
            playlists.add(new IngestionPlaylistDTO(names[i], episodes));
        }
        return playlists;
    }

    private Optional<String> textFromRawMetadata(String rawMetadata, String... fields) {
        if (!StringUtils.hasText(rawMetadata)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(rawMetadata);
            for (String field : fields) {
                JsonNode value = root.path(field);
                if (!value.isMissingNode() && !value.isNull()) {
                    String text = value.isTextual() ? value.asText() : value.asText(value.toString());
                    if (StringUtils.hasText(text)) {
                        return Optional.of(text.trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private List<String> listFromRawMetadata(String rawMetadata, String... fields) {
        if (!StringUtils.hasText(rawMetadata)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(rawMetadata);
            Set<String> values = new LinkedHashSet<>();
            for (String field : fields) {
                collectJsonValues(root.path(field), values);
            }
            return List.copyOf(values);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void putText(ObjectNode node, String field, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString();
        if (StringUtils.hasText(text)) {
            node.put(field, text.trim());
        }
    }

    private void putArray(ObjectNode node, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ArrayNode array = objectMapper.createArrayNode();
        values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .forEach(array::add);
        if (!array.isEmpty()) {
            node.set(field, array);
        }
    }

    private void collectValues(Object value, Set<String> values) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectValues(item, values);
            }
            return;
        }
        collectTextValue(value.toString(), values);
    }

    private void collectJsonValues(JsonNode node, Set<String> values) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectJsonValues(item, values));
            return;
        }
        collectTextValue(node.isTextual() ? node.asText() : node.asText(node.toString()), values);
    }

    private void collectTextValue(String text, Set<String> values) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        for (String part : text.split("[,，、/|;；]+")) {
            if (StringUtils.hasText(part)) {
                values.add(part.trim());
            }
        }
    }

    private String normalizeUrl(String url, String baseUrl) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String clean = url.trim();
        if (clean.startsWith("//")) {
            return "https:" + clean;
        }
        if (clean.startsWith("http")) {
            return clean;
        }
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String cleanPath = clean.startsWith("/") ? clean.substring(1) : clean;
        return cleanBase + "/" + cleanPath;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
