package com.prodigalgal.ircs.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Slf4j
@RequiredArgsConstructor
class CatalogRemoteCategorySyncService {

    private final JdbcCatalogRepository repository;
    private final ObjectMapper objectMapper;
    private final CatalogFetchSampleClient fetchClient;
    private final CatalogCategoryMappingPolicy mappingPolicy;

    CategorySyncResult syncDataSourceCategories(UUID dataSourceId) {
        return repository.findDataSource(dataSourceId)
                .map(this::syncDataSourceCategories)
                .orElseGet(() -> CategorySyncResult.failed(dataSourceId, null, "DATA_SOURCE_NOT_FOUND"));
    }

    CategorySyncResult syncDataSourceCategories(String dataSourceName) {
        return repository.findDataSourceByName(dataSourceName)
                .map(this::syncDataSourceCategories)
                .orElseGet(() -> CategorySyncResult.failed(null, null, "DATA_SOURCE_NOT_FOUND"));
    }

    private CategorySyncResult syncDataSourceCategories(DataSourceRead dataSource) {
        String url = buildListUrl(dataSource);
        if (!StringUtils.hasText(url)) {
            return CategorySyncResult.failed(dataSource.id(), null, "URL_NOT_BUILDABLE");
        }
        try {
            String response = fetchClient.get(url, SourceNetworkOptions.from(dataSource));
            if (!StringUtils.hasText(response)) {
                return CategorySyncResult.empty(dataSource.id(), url);
            }
            JsonNode root = objectMapper.readTree(response);
            JsonNode classNode = root.path("class");
            if (!classNode.isArray() || classNode.isEmpty()) {
                return CategorySyncResult.empty(dataSource.id(), url);
            }

            int checked = 0;
            int inserted = 0;
            int updated = 0;
            int skipped = 0;
            for (JsonNode item : classNode) {
                String typeId = item.path("type_id").asText(null);
                String typeName = item.path("type_name").asText(null);
                if (!StringUtils.hasText(typeId) || !StringUtils.hasText(typeName)) {
                    skipped++;
                    continue;
                }
                checked++;
                mappingPolicy.inferCategoryName(typeName);
            }
            return new CategorySyncResult(dataSource.id(), url, true, checked, inserted, updated, skipped, null);
        } catch (Exception ex) {
            log.warn("Remote category sync failed for data source [{}]: {}", dataSource.name(), ex.getMessage());
            return CategorySyncResult.failed(dataSource.id(), url, ex.getClass().getSimpleName());
        }
    }

    String buildListUrl(DataSourceRead dataSource) {
        if (dataSource == null
                || !StringUtils.hasText(dataSource.baseUrl())
                || !StringUtils.hasText(dataSource.listPath())) {
            return null;
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(stripTrailingSlash(dataSource.baseUrl()))
                .path(normalizePath(dataSource.listPath()))
                .queryParam("limit", "20")
                .queryParam("pagesize", "20");

        Map<String, String> params = parseJsonMap(dataSource.listParams());
        if (params.isEmpty()) {
            builder.queryParam("ac", "list").queryParam("pg", 1);
        } else {
            params.forEach((key, value) -> builder.queryParam(key, substituteListParam(value)));
        }
        return builder.toUriString();
    }

    private Map<String, String> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String substituteListParam(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("{page}", "1")
                .replace("{t}", "")
                .replace("{h}", "")
                .replace("{wd}", "");
    }

    private String stripTrailingSlash(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizePath(String value) {
        String normalized = value.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    record CategorySyncResult(
            UUID dataSourceId,
            String url,
            boolean success,
            int checked,
            int inserted,
            int updated,
            int skipped,
            String error) {

        private static CategorySyncResult empty(UUID dataSourceId, String url) {
            return new CategorySyncResult(dataSourceId, url, true, 0, 0, 0, 0, null);
        }

        private static CategorySyncResult failed(UUID dataSourceId, String url, String error) {
            return new CategorySyncResult(dataSourceId, url, false, 0, 0, 0, 0, error);
        }
    }
}
