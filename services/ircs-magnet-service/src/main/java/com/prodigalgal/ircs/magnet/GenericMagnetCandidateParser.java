package com.prodigalgal.ircs.magnet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.util.StringUtils;

class GenericMagnetCandidateParser {

    private static final Pattern MAGNET_PATTERN = Pattern.compile(
            "(?i)magnet:\\?xt=urn:btih:[a-z0-9]{32,40}(?:&(?:amp;)?[^\\s\"'<>)]*)*");
    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)?)\\s*(TiB|TB|GiB|GB|MiB|MB|KiB|KB)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("(?i)\\b(2160p|1080p|720p|480p|4k|uhd|fhd|hd)\\b");
    private static final Pattern SEED_PATTERN = Pattern.compile("(?i)\\b(?:seeders?|seeds?|seed|se|s)[:：\\s]*(\\d{1,7})\\b");
    private static final Pattern LEECH_PATTERN = Pattern.compile("(?i)\\b(?:leechers?|leeches|leech|peers?|le|l)[:：\\s]*(\\d{1,7})\\b");
    private static final Set<String> JSON_ARRAY_FIELDS = Set.of("data", "results", "items", "torrents", "list");

    private final ObjectMapper objectMapper;

    GenericMagnetCandidateParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    List<MagnetProviderCandidate> parse(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            String requestUrl,
            String body) throws IOException {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJsonCandidates(provider, query, requestUrl, trimmed);
        }
        return parseHtmlCandidates(provider, query, requestUrl, body);
    }

    private List<MagnetProviderCandidate> parseJsonCandidates(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            String requestUrl,
            String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        List<JsonNode> nodes = candidateNodes(root);
        Map<String, MagnetProviderCandidate> candidates = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            toJsonCandidate(provider, query, requestUrl, node).ifPresent(candidate -> candidates.putIfAbsent(
                    candidate.infoHash(),
                    candidate));
            if (candidates.size() >= safeLimit(provider)) {
                break;
            }
        }
        return List.copyOf(candidates.values());
    }

    private Optional<MagnetProviderCandidate> toJsonCandidate(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            String requestUrl,
            JsonNode node) {
        String magnetUri = firstText(
                node,
                "magnet",
                "magnet_url",
                "magnetUrl",
                "magnet_uri",
                "magnetUri",
                "magnetLink",
                "download",
                "downloadUrl");
        String rawHash = firstText(node, "info_hash", "infoHash", "hash", "torrent_hash", "btih");
        Optional<String> infoHash = MagnetUriUtils.normalizeInfoHash(firstNonBlank(magnetUri, rawHash));
        if (infoHash.isEmpty()) {
            return Optional.empty();
        }
        String title = firstNonBlank(
                magnetDisplayName(magnetUri),
                firstText(node, "name", "title", "filename", "file_name", "fileName", "dn"),
                query.value());
        String sizeLabel = firstText(node, "sizeLabel", "size_label", "size", "length");
        Long sizeBytes = longValue(node, "size_bytes", "sizeBytes", "bytes", "size");
        if (sizeBytes == null) {
            sizeBytes = parseSizeBytes(sizeLabel);
        }
        String sourceUrl = resolveUrl(requestUrl, firstText(node, "url", "detail_url", "detailUrl", "page", "source"));
        String resolution = extractResolution(title);
        return Optional.of(new MagnetProviderCandidate(
                infoHash.get(),
                normalizeMagnetUri(firstNonBlank(magnetUri, MagnetUriUtils.buildMagnetUri(infoHash.get(), title))),
                title,
                sizeBytes,
                sizeLabel,
                parsePublishedAt(firstText(
                        node,
                        "added",
                        "date",
                        "created_at",
                        "createdAt",
                        "uploaded",
                        "publishedAt",
                        "date_released_unix",
                        "dateReleasedUnix")),
                intValue(node, "seeders", "seeds", "seed", "seed_count"),
                intValue(node, "leechers", "leechs", "leeches", "leech", "peers", "peer_count"),
                resolution,
                resolution,
                query.type(),
                query.value(),
                matchScore(query),
                sourceUrl,
                tags(provider, resolution),
                providerEvidence(provider, query, "JSON", requestUrl, sourceUrl, sizeLabel)));
    }

    private List<MagnetProviderCandidate> parseHtmlCandidates(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            String requestUrl,
            String body) {
        Document document = Jsoup.parse(body, requestUrl);
        Map<String, MagnetProviderCandidate> candidates = new LinkedHashMap<>();
        for (Element element : document.select("a[href], [data-href], [data-magnet], [data-clipboard-text], [value]")) {
            for (String magnetUri : magnetAttributes(element)) {
                addHtmlCandidate(candidates, provider, query, requestUrl, document, element, magnetUri);
                if (candidates.size() >= safeLimit(provider)) {
                    return List.copyOf(candidates.values());
                }
            }
        }
        String unescaped = Parser.unescapeEntities(body, false);
        Matcher matcher = MAGNET_PATTERN.matcher(unescaped);
        while (matcher.find() && candidates.size() < safeLimit(provider)) {
            addHtmlCandidate(candidates, provider, query, requestUrl, document, document.body(), matcher.group());
        }
        return List.copyOf(candidates.values());
    }

    private void addHtmlCandidate(
            Map<String, MagnetProviderCandidate> candidates,
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            String requestUrl,
            Document document,
            Element element,
            String rawMagnetUri) {
        String magnetUri = normalizeMagnetUri(rawMagnetUri);
        Optional<String> infoHash = MagnetUriUtils.normalizeInfoHash(magnetUri);
        if (infoHash.isEmpty() || candidates.containsKey(infoHash.get())) {
            return;
        }
        Element context = contextElement(element);
        String contextText = compactText(context == null ? element.text() : context.text());
        String title = firstNonBlank(
                magnetDisplayName(magnetUri),
                cleanTitle(element.text()),
                contextTitle(context),
                document.title(),
                query.value());
        String sizeLabel = firstMatch(contextText, SIZE_PATTERN).orElse(null);
        String resolution = firstNonBlank(extractResolution(title), extractResolution(contextText));
        String sourceUrl = firstNonBlank(nonMagnetLink(context), requestUrl);
        candidates.put(infoHash.get(), new MagnetProviderCandidate(
                infoHash.get(),
                magnetUri,
                title,
                parseSizeBytes(sizeLabel),
                sizeLabel,
                null,
                firstInt(contextText, SEED_PATTERN),
                firstInt(contextText, LEECH_PATTERN),
                resolution,
                resolution,
                query.type(),
                query.value(),
                matchScore(query),
                sourceUrl,
                tags(provider, resolution),
                providerEvidence(provider, query, "HTML", requestUrl, sourceUrl, sizeLabel)));
    }

    private List<JsonNode> candidateNodes(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        if (root.isArray()) {
            return toList(root);
        }
        for (String field : JSON_ARRAY_FIELDS) {
            JsonNode value = root.path(field);
            if (value.isArray()) {
                return toList(value);
            }
            if (value.isObject()) {
                List<JsonNode> nested = candidateNodes(value);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
        }
        return List.of(root);
    }

    private List<JsonNode> toList(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values;
    }

    private List<String> magnetAttributes(Element element) {
        List<String> values = new ArrayList<>();
        for (String attr : List.of("href", "data-href", "data-magnet", "data-clipboard-text", "value")) {
            String value = element.attr(attr);
            if (value != null && value.toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
                values.add(value);
            }
        }
        return values;
    }

    private Element contextElement(Element element) {
        if (element == null) {
            return null;
        }
        for (String selector : List.of("tr", "li", "article", "section")) {
            Element match = element.closest(selector);
            if (match != null) {
                return match;
            }
        }
        return element.parent() == null ? element : element.parent();
    }

    private String contextTitle(Element context) {
        if (context == null) {
            return null;
        }
        Element heading = context.selectFirst("h1, h2, h3, h4, .title, .name");
        if (heading != null) {
            return cleanTitle(heading.text());
        }
        for (Element link : context.select("a[href]")) {
            if (!link.attr("href").toLowerCase(Locale.ROOT).startsWith("magnet:")) {
                String title = cleanTitle(link.text());
                if (StringUtils.hasText(title)) {
                    return title;
                }
            }
        }
        return cleanTitle(context.text());
    }

    private String nonMagnetLink(Element context) {
        if (context == null) {
            return null;
        }
        for (Element link : context.select("a[href]")) {
            if (!link.attr("href").toLowerCase(Locale.ROOT).startsWith("magnet:")) {
                String absUrl = link.absUrl("href");
                if (StringUtils.hasText(absUrl)) {
                    return absUrl;
                }
            }
        }
        return null;
    }

    private String normalizeMagnetUri(String raw) {
        String value = Parser.unescapeEntities(raw == null ? "" : raw, false).trim();
        while (!value.isEmpty() && ".,;\"'<>".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1);
        }
        return value.replace("&amp;", "&");
    }

    private String magnetDisplayName(String magnetUri) {
        if (!StringUtils.hasText(magnetUri) || !magnetUri.contains("?")) {
            return null;
        }
        String query = magnetUri.substring(magnetUri.indexOf('?') + 1);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "dn".equalsIgnoreCase(part.substring(0, eq))) {
                return cleanTitle(decodeQueryPart(part.substring(eq + 1)));
            }
        }
        return null;
    }

    private String decodeQueryPart(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private String cleanTitle(String value) {
        String compact = compactText(value);
        if (!StringUtils.hasText(compact)) {
            return null;
        }
        return compact.length() > 180 ? compact.substring(0, 180).trim() : compact;
    }

    private String compactText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private Optional<String> firstMatch(String value, Pattern pattern) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    private Integer firstInt(String value, Pattern pattern) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long parseSizeBytes(String sizeLabel) {
        if (!StringUtils.hasText(sizeLabel)) {
            return null;
        }
        Matcher matcher = SIZE_PATTERN.matcher(sizeLabel);
        if (!matcher.find()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(matcher.group(1).replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
        long multiplier = switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
            case "TIB" -> 1L << 40;
            case "TB" -> 1_000_000_000_000L;
            case "GIB" -> 1L << 30;
            case "GB" -> 1_000_000_000L;
            case "MIB" -> 1L << 20;
            case "MB" -> 1_000_000L;
            case "KIB" -> 1L << 10;
            default -> 1_000L;
        };
        return Math.round(value * multiplier);
    }

    private Instant parsePublishedAt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.matches("\\d{10,13}")) {
            long epoch = Long.parseLong(normalized);
            return Instant.ofEpochMilli(normalized.length() == 13 ? epoch : epoch * 1000);
        }
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException ignored) {
            // 继续尝试 yyyy-MM-dd。
        }
        try {
            return LocalDate.parse(normalized).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String extractResolution(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = RESOLUTION_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private List<String> tags(MagnetProviderSummary provider, String resolution) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (provider != null && StringUtils.hasText(provider.code())) {
            tags.add(provider.code());
        }
        if (StringUtils.hasText(resolution)) {
            tags.add(resolution);
        }
        return List.copyOf(tags);
    }

    private Map<String, Object> providerEvidence(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            String sourceFormat,
            String requestUrl,
            String sourceUrl,
            String sizeLabel) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("providerType", provider == null ? null : provider.providerType());
        evidence.put("providerCode", provider == null ? null : provider.code());
        evidence.put("queryType", query == null ? null : query.type());
        evidence.put("queryValue", query == null ? null : query.value());
        evidence.put("sourceFormat", sourceFormat);
        evidence.put("requestUrl", requestUrl);
        evidence.put("sourceUrl", sourceUrl);
        evidence.put("sizeLabel", sizeLabel);
        return evidence;
    }

    private int matchScore(MagnetExternalIdQuery query) {
        if (query == null) {
            return 0;
        }
        return switch (query.type().toUpperCase(Locale.ROOT)) {
            case "IMDB" -> 92;
            case "TMDB", "DOUBAN" -> 80;
            case "TITLE_YEAR" -> 76;
            default -> 68;
        };
    }

    private int safeLimit(MagnetProviderSummary provider) {
        int limit = provider == null || provider.resultLimit() == null ? 20 : provider.resultLimit();
        return Math.max(1, Math.min(limit, 50));
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText(null);
                if (StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private Long longValue(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.canConvertToLong()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 继续尝试后续字段。
                }
            }
        }
        return null;
    }

    private Integer intValue(JsonNode node, String... fields) {
        Long value = longValue(node, fields);
        return value == null ? null : Math.toIntExact(Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)));
    }

    private String resolveUrl(String base, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return URI.create(base).resolve(value.trim()).toString();
        } catch (IllegalArgumentException ex) {
            return value.trim();
        }
    }
}
