package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundProxy;
import com.prodigalgal.ircs.common.outbound.OutboundTransportRouter;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundUserAgents;
import com.prodigalgal.ircs.messaging.RabbitTaskHttpStatusException;
import com.prodigalgal.ircs.messaging.TaskSourceTerminalException;
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListItem;
import com.prodigalgal.ircs.scraper.ScraperDtos.ListPage;
import com.prodigalgal.ircs.scraper.ScraperDtos.ManualScrapeConfigRequest;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class ListScraperClient {

    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final OutboundHttpClient httpClient;
    private final ScraperCharsetDetector charsetDetector;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final ScraperTrafficLimiter trafficLimiter;
    ListScraperClient(
            ObjectMapper objectMapper,
            @Value("${app.scraper.http-timeout-ms:20000}") int timeoutMs,
            ScraperTrafficLimiter trafficLimiter,
            ObjectProvider<OutboundHttpClient> httpClientProvider) {
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = httpClient(httpClientProvider);
        this.charsetDetector = new ScraperCharsetDetector();
        this.trafficLimiter = trafficLimiter;
    }

    List<ListItem> fetchList(DataSourceRecord source, ManualScrapeConfigRequest config, int page) {
        return fetchListPage(source, config, page).items();
    }

    ListPage fetchListPage(DataSourceRecord source, ManualScrapeConfigRequest config, int page) {
        FetchedBody response = fetchBody(
                source,
                buildUrl(source.baseUrl(), source.listPath(), source.listParams(), page, null, config),
                config);
        try {
            JsonNode root = objectMapper.readTree(source.fieldMapping());
            JsonNode listMapping = root.path("list_mapping");
            String itemsPath = listMapping.path("items_path").asText();
            String primaryPath = listMapping.path("primary_id_path").asText();
            String updatePath = listMapping.path("update_time_path").asText();
            String totalPagesPath = listMapping.path("pagination").path("total_pages_path").asText();
            String totalItemsPath = listMapping.path("pagination").path("total_items_path").asText();
            return parseListResponse(
                    response,
                    source.name(),
                    itemsPath,
                    primaryPath,
                    updatePath,
                    totalPagesPath,
                    totalItemsPath);
        } catch (TaskSourceTerminalException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse list response from " + source.name() + ": " + e.getMessage(), e);
        }
    }

    String fetchDetail(DataSourceRecord source, ManualScrapeConfigRequest config, String sourceVid) {
        return decodeBody(fetchBody(
                source,
                buildUrl(source.baseUrl(), source.detailPath(), source.detailParams(), null, sourceVid, config),
                config));
    }

    private FetchedBody fetchBody(DataSourceRecord source, String url, ManualScrapeConfigRequest config) {
        try {
            trafficLimiter.acquireDataSourceSlot(source, config);
            OutboundHttpPolicy policy = OutboundHttpPolicy.publicFetch(sourceTimeout(source))
                    .withUserAgent(userAgent(source, config))
                    .withHttpProtocol(source.httpProtocol())
                    .withTransportMode(source.transportMode())
                    .withResolution(source.ipVersionPolicy(), source.dnsResolverType(), source.dnsResolverEndpoint())
                    .withProxy(proxy(config));
            OutboundHttpRequest request = OutboundHttpRequest.get(url, policy);
            for (Map.Entry<String, String> header : parseHeaders(config.headers()).entrySet()) {
                request = request.withHeader(header.getKey(), header.getValue());
            }
            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RabbitTaskHttpStatusException(response.statusCode(), source.name(), url);
            }
            return new FetchedBody(response.body(), response.firstHeader("Content-Type"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP fetch failed: " + url + " - " + e.getMessage(), e);
        } catch (RabbitTaskHttpStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("HTTP fetch failed: " + url + " - " + e.getMessage(), e);
        }
    }

    private ListPage parseListResponse(
            FetchedBody response,
            String sourceName,
            String itemsPath,
            String primaryPath,
            String updatePath,
            String totalPagesPath,
            String totalItemsPath)
            throws java.io.IOException {
        List<String> targetListPath = parseJsonPathToSegments(itemsPath);
        String targetIdField = leaf(primaryPath);
        String targetTimeField = leaf(updatePath);
        List<String> targetTotalPagesPath = parseJsonPathToSegments(totalPagesPath);
        List<String> targetTotalItemsPath = parseJsonPathToSegments(totalItemsPath);
        if (targetListPath.isEmpty() || !StringUtils.hasText(targetIdField)) {
            return new ListPage(List.of(), null, null);
        }

        try (JsonParser parser = jsonFactory.createParser(new StringReader(decodeBody(response)))) {
            List<ListItem> results = new ArrayList<>();
            List<String> currentPath = new ArrayList<>();
            String currentFieldName = null;
            Integer totalPages = null;
            Integer totalItems = null;

            while (!parser.isClosed()) {
                JsonToken token = parser.nextToken();
                if (token == null) {
                    break;
                }
                switch (token) {
                    case FIELD_NAME -> currentFieldName = parser.currentName();
                    case START_OBJECT -> {
                        if (currentFieldName != null) {
                            currentPath.add(currentFieldName);
                            currentFieldName = null;
                        }
                        if (currentPath.equals(targetListPath)) {
                            ListItem item = extractListItem(parser, targetIdField, targetTimeField);
                            if (item.id() != null) {
                                results.add(item);
                            }
                        }
                    }
                    case END_OBJECT -> {
                        if (!currentPath.isEmpty() && !currentPath.equals(targetListPath)) {
                            currentPath.removeLast();
                        }
                    }
                    case START_ARRAY -> {
                        if (currentFieldName != null) {
                            currentPath.add(currentFieldName);
                            currentFieldName = null;
                        }
                    }
                    case END_ARRAY -> {
                        if (!currentPath.isEmpty()) {
                            currentPath.removeLast();
                        }
                    }
                    case VALUE_NUMBER_INT, VALUE_STRING -> {
                        if (currentFieldName != null) {
                            List<String> fullPath = new ArrayList<>(currentPath);
                            fullPath.add(currentFieldName);
                            if (isPathMatch(fullPath, targetTotalPagesPath)) {
                                totalPages = intValue(parser);
                            } else if (isPathMatch(fullPath, targetTotalItemsPath)) {
                                totalItems = intValue(parser);
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            return new ListPage(results, totalPages, totalItems);
        } catch (JsonParseException ex) {
            throw new TaskSourceTerminalException(
                    sourceName,
                    "invalid JSON list response: " + ex.getOriginalMessage(),
                    ex);
        }
    }

    private String decodeBody(FetchedBody response) {
        Charset charset = charsetDetector.detect(response.body(), response.contentType());
        return stripLeadingBom(new String(response.body(), charset));
    }

    private String stripLeadingBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private ListItem extractListItem(JsonParser parser, String idField, String timeField) throws java.io.IOException {
        String foundId = null;
        String foundTime = null;
        Map<String, Object> raw = new LinkedHashMap<>();
        int depth = 1;

        while (depth > 0) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                break;
            }
            if (token == JsonToken.START_OBJECT) {
                depth++;
            } else if (token == JsonToken.END_OBJECT) {
                depth--;
            }
            if (depth == 1 && token == JsonToken.FIELD_NAME) {
                String fieldName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                Object value = readTopLevelValue(parser, valueToken);
                raw.put(fieldName, value);
                if (fieldName.equals(idField) && value != null) {
                    foundId = value.toString();
                } else if (fieldName.equals(timeField) && value != null) {
                    foundTime = value.toString();
                }
            }
        }
        return new ListItem(foundId, foundTime, raw);
    }

    private Object readTopLevelValue(JsonParser parser, JsonToken valueToken) throws java.io.IOException {
        if (valueToken == JsonToken.VALUE_STRING) {
            return parser.getText();
        }
        if (valueToken != null && valueToken.isNumeric()) {
            return parser.getNumberValue();
        }
        if (valueToken == JsonToken.VALUE_TRUE || valueToken == JsonToken.VALUE_FALSE) {
            return parser.getBooleanValue();
        }
        if (valueToken == JsonToken.VALUE_NULL) {
            return null;
        }
        if (valueToken == JsonToken.START_OBJECT || valueToken == JsonToken.START_ARRAY) {
            return objectMapper.readValue(parser, Object.class);
        }
        return parser.getValueAsString();
    }

    private List<String> parseJsonPathToSegments(String jsonPath) {
        if (!StringUtils.hasText(jsonPath)) {
            return Collections.emptyList();
        }
        String clean = jsonPath.replace("$.", "").replace("$", "");
        if (!StringUtils.hasText(clean)) {
            return Collections.emptyList();
        }
        String[] parts = clean.split("\\.");
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            String segment = part.replaceAll("\\[.*?\\]", "").trim();
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private boolean isPathMatch(List<String> currentPath, List<String> targetPath) {
        return !targetPath.isEmpty() && currentPath.equals(targetPath);
    }

    private Integer intValue(JsonParser parser) throws java.io.IOException {
        if (parser.currentToken() != null && parser.currentToken().isNumeric()) {
            return parser.getIntValue();
        }
        String text = parser.getValueAsString();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Duration sourceTimeout(DataSourceRecord source) {
        Integer value = source.readTimeoutMs();
        if (value == null || value < 1 || value > 120_000) {
            return timeout;
        }
        return Duration.ofMillis(value);
    }

    private String userAgent(DataSourceRecord source, ManualScrapeConfigRequest config) {
        if (config.enableRandomUa()) {
            return OutboundUserAgents.randomBrowser();
        }
        if (StringUtils.hasText(config.userAgent())) {
            return config.userAgent();
        }
        if (StringUtils.hasText(source.userAgent())) {
            return source.userAgent();
        }
        return OutboundUserAgents.defaultBrowser();
    }

    private OutboundProxy proxy(ManualScrapeConfigRequest config) {
        if (config.useCustomProxy() && StringUtils.hasText(config.proxyHost()) && config.proxyPort() != null) {
            return OutboundProxy.http(
                    config.proxyHost(),
                    config.proxyPort(),
                    config.proxyUsername(),
                    config.proxyPassword());
        }
        return OutboundProxy.disabled();
    }

    private String buildUrl(String baseUrl, String path, String paramsJson, Integer page, String sourceVid,
            ManualScrapeConfigRequest config) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(cleanBase(baseUrl)).path(path);
        Map<String, String> params = readParams(paramsJson);
        if (params.isEmpty() && page != null) {
            builder.queryParam("ac", "list").queryParam("pg", page);
        }
        params.forEach((key, value) -> builder.queryParam(
                key,
                replaceTokens(value, page, sourceVid, config.keyword(), config.filterType(), config.filterHours())));
        if (page != null && StringUtils.hasText(config.filterType()) && !params.containsKey("t")) {
            builder.queryParam("t", config.filterType());
        }
        if (page != null && config.filterHours() != null && config.filterHours() > 0 && !params.containsKey("h")) {
            builder.queryParam("h", config.filterHours());
        }
        if (page != null && StringUtils.hasText(config.keyword()) && !params.containsKey("wd")) {
            builder.queryParam("wd", config.keyword());
        }
        String url = builder.build(false).toUriString();
        if (sourceVid != null) {
            url = url.replace("{ids}", encode(sourceVid))
                    .replace("{id}", encode(sourceVid))
                    .replace("{sourceVid}", encode(sourceVid))
                    .replace("{vod_id}", encode(sourceVid));
        }
        return url;
    }

    private Map<String, String> readParams(String paramsJson) {
        if (!StringUtils.hasText(paramsJson) || "null".equals(paramsJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(paramsJson, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, String> parseHeaders(String headers) {
        if (!StringUtils.hasText(headers)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(headers, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String replaceTokens(
            String value,
            Integer page,
            String sourceVid,
            String keyword,
            String filterType,
            Integer filterHours) {
        String result = value;
        if (page != null) {
            result = result.replace("{page}", String.valueOf(page));
        }
        if (sourceVid != null) {
            result = result.replace("{ids}", sourceVid)
                    .replace("{id}", sourceVid)
                    .replace("{sourceVid}", sourceVid)
                    .replace("{vod_id}", sourceVid);
        }
        if (keyword != null) {
            result = result.replace("{keyword}", keyword);
        }
        if (filterType != null) {
            result = result.replace("{type}", filterType)
                    .replace("{filterType}", filterType);
        }
        if (filterHours != null) {
            String hours = String.valueOf(filterHours);
            result = result.replace("{hours}", hours)
                    .replace("{filterHours}", hours);
        }
        return result;
    }

    private String cleanBase(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String leaf(String jsonPath) {
        if (!StringUtils.hasText(jsonPath)) {
            return null;
        }
        String clean = jsonPath.replace("$.", "").replace("$", "");
        String[] parts = clean.split("\\.");
        String leaf = parts.length == 0 ? clean : parts[parts.length - 1];
        return leaf.replaceAll("\\[.*?\\]", "");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static OutboundHttpClient httpClient(ObjectProvider<OutboundHttpClient> httpClientProvider) {
        if (httpClientProvider != null) {
            OutboundHttpClient provided = httpClientProvider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new OutboundTransportRouter());
    }

    private record FetchedBody(byte[] body, String contentType) {
    }
}
