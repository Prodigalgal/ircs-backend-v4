package com.prodigalgal.ircs.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Slf4j
public class CatalogService {

    private final JdbcCatalogRepository catalogRepository;
    private final ObjectMapper objectMapper;
    private final CatalogFetchSampleClient fetchSampleClient;
    private final CatalogRemoteCategorySyncService remoteCategorySyncService;
    private final CatalogReadModelCache readModelCache;
    public CatalogService(
            JdbcCatalogRepository catalogRepository,
            ObjectMapper objectMapper,
            CatalogFetchSampleClient fetchSampleClient,
            CatalogRemoteCategorySyncService remoteCategorySyncService,
            CatalogReadModelCache readModelCache) {
        this.catalogRepository = catalogRepository;
        this.objectMapper = objectMapper;
        this.fetchSampleClient = fetchSampleClient;
        this.remoteCategorySyncService = remoteCategorySyncService;
        this.readModelCache = readModelCache;
    }

    public List<StandardCategorySummary> listStandardCategories() {
        return readModelCache.standardCategorySummaries(catalogRepository::listStandardCategories);
    }

    public List<StandardGenreSummary> listStandardGenres() {
        return readModelCache.standardGenreSummaries(catalogRepository::listStandardGenres);
    }

    public List<StandardAreaSummary> listStandardAreas() {
        return readModelCache.standardAreaSummaries(catalogRepository::listStandardAreas);
    }

    public List<StandardLanguageSummary> listStandardLanguages() {
        return readModelCache.standardLanguageSummaries(catalogRepository::listStandardLanguages);
    }

    public List<DataSourceSummary> listDataSources() {
        return catalogRepository.listDataSources();
    }

    public CatalogPage<DataSourceRead> pageDataSources(CatalogPageRequest pageRequest) {
        return catalogRepository.pageDataSources(pageRequest);
    }

    public Optional<DataSourceRead> findDataSource(UUID id) {
        return catalogRepository.findDataSource(id);
    }

    @Transactional
    public DataSourceRead createDataSource(DataSourceAdminRequest request) {
        if (request.id() != null) {
            throw badRequest("A new data source cannot already have an ID");
        }
        validateDataSource(request, true);
        try {
            DataSourceRead dataSource = catalogRepository.createDataSource(normalizeDataSource(request, true));
            syncCategoriesBestEffort(dataSource);
            return dataSource;
        } catch (DataIntegrityViolationException ex) {
            throw conflict("DataSource name already exists", ex);
        }
    }

    @Transactional
    public DataSourceRead updateDataSource(UUID id, DataSourceAdminRequest request) {
        validatePathId(id, request.id());
        validateDataSource(request, true);
        try {
            DataSourceRead dataSource = catalogRepository.updateDataSource(id, normalizeDataSource(request, true), false)
                    .orElseThrow(() -> notFound("DataSource not found"));
            syncCategoriesBestEffort(dataSource);
            return dataSource;
        } catch (DataIntegrityViolationException ex) {
            throw badRequest("Invalid data source payload", ex);
        }
    }

    @Transactional
    public Optional<DataSourceRead> patchDataSource(UUID id, DataSourceAdminRequest request) {
        validatePathId(id, request.id());
        validateDataSource(request, false);
        try {
            Optional<DataSourceRead> updated = catalogRepository.updateDataSource(id, normalizeDataSource(request, false), true);
            updated.ifPresent(this::syncCategoriesBestEffort);
            return updated;
        } catch (DataIntegrityViolationException ex) {
            throw badRequest("Invalid data source payload", ex);
        }
    }

    @Transactional
    public void deleteDataSource(UUID id) {
        catalogRepository.deleteById("data_sources", id);
    }

    public Optional<String> fetchSample(FetchSampleRequest request) {
        String targetUrl = buildSampleUrl(request);
        if (targetUrl == null) {
            throw badRequest("Cannot build URL. Missing required fields.");
        }
        try {
            return Optional.ofNullable(fetchSampleClient.get(targetUrl, SourceNetworkOptions.from(request)));
        } catch (IllegalArgumentException ex) {
            throw badRequest("Invalid source request option or URL", ex);
        } catch (IOException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public List<StandardCategoryRead> listStandardCategoryReads() {
        return readModelCache.standardCategoryReads(catalogRepository::listStandardCategoryReads);
    }

    public CatalogPage<StandardCategoryRead> pageStandardCategories(
            CatalogPageRequest pageRequest,
            String name,
            String slug) {
        return catalogRepository.pageStandardCategories(pageRequest, name, slug);
    }

    public Optional<StandardCategoryRead> findStandardCategory(UUID id) {
        return catalogRepository.findStandardCategory(id);
    }

    @Transactional
    public StandardCategoryRead createStandardCategory(StandardCategoryAdminRequest request) {
        if (request.id() != null) {
            throw badRequest("A new category cannot already have an ID");
        }
        requireText(request.name(), "Category name is required");
        requireText(request.slug(), "Category slug is required");
        StandardCategoryRead result = catalogRepository.createStandardCategory(request);
        readModelCache.evictStandardCategories();
        return result;
    }

    @Transactional
    public StandardCategoryRead updateStandardCategory(UUID id, StandardCategoryAdminRequest request) {
        validatePathId(id, request.id());
        requireText(request.name(), "Category name is required");
        requireText(request.slug(), "Category slug is required");
        StandardCategoryRead result = catalogRepository.updateStandardCategory(id, request, false)
                .orElseThrow(() -> notFound("StandardCategory not found"));
        readModelCache.evictStandardCategories();
        return result;
    }

    @Transactional
    public Optional<StandardCategoryRead> patchStandardCategory(UUID id, StandardCategoryAdminRequest request) {
        validatePathId(id, request.id());
        Optional<StandardCategoryRead> result = catalogRepository.updateStandardCategory(id, request, true);
        result.ifPresent(ignored -> readModelCache.evictStandardCategories());
        return result;
    }

    @Transactional
    public void deleteStandardCategory(UUID id) {
        catalogRepository.deleteById("standard_category", id);
        readModelCache.evictStandardCategories();
    }

    public List<StandardGenreRead> listStandardGenreReads() {
        return readModelCache.standardGenreReads(catalogRepository::listStandardGenreReads);
    }

    public CatalogPage<StandardGenreRead> pageStandardGenres(
            CatalogPageRequest pageRequest,
            String name,
            String code) {
        return catalogRepository.pageStandardGenres(pageRequest, name, code);
    }

    public Optional<StandardGenreRead> findStandardGenre(UUID id) {
        return catalogRepository.findStandardGenre(id);
    }

    @Transactional
    public StandardGenreRead createStandardGenre(StandardGenreAdminRequest request) {
        if (request.id() != null) {
            throw badRequest("A new genre cannot already have an ID");
        }
        requireText(request.name(), "Genre name is required");
        StandardGenreRead result = catalogRepository.createStandardGenre(request);
        readModelCache.evictStandardGenres();
        return result;
    }

    @Transactional
    public Optional<StandardGenreRead> updateStandardGenre(UUID id, StandardGenreAdminRequest request) {
        validatePathId(id, request.id());
        requireText(request.name(), "Genre name is required");
        Optional<StandardGenreRead> result = catalogRepository.updateStandardGenre(id, request);
        result.ifPresent(ignored -> readModelCache.evictStandardGenres());
        return result;
    }

    @Transactional
    public void deleteStandardGenre(UUID id) {
        catalogRepository.deleteById("standard_genre", id);
        readModelCache.evictStandardGenres();
    }

    public List<StandardAreaRead> listStandardAreaReads() {
        return readModelCache.standardAreaReads(catalogRepository::listStandardAreaReads);
    }

    @Transactional
    public StandardAreaRead createStandardArea(StandardAreaAdminRequest request) {
        if (request.id() != null) {
            throw badRequest("A new area cannot already have an ID");
        }
        requireText(request.name(), "Area name is required");
        requireText(request.code(), "Area code is required");
        StandardAreaRead result = catalogRepository.createStandardArea(request);
        readModelCache.evictStandardAreas();
        return result;
    }

    public CatalogPage<StandardAreaRead> pageStandardAreas(CatalogPageRequest pageRequest) {
        return catalogRepository.pageStandardAreas(pageRequest);
    }

    public List<StandardLanguageRead> listStandardLanguageReads() {
        return readModelCache.standardLanguageReads(catalogRepository::listStandardLanguageReads);
    }

    @Transactional
    public StandardLanguageRead createStandardLanguage(StandardLanguageAdminRequest request) {
        if (request.id() != null) {
            throw badRequest("A new language cannot already have an ID");
        }
        requireText(request.name(), "Language name is required");
        StandardLanguageRead result = catalogRepository.createStandardLanguage(request);
        readModelCache.evictStandardLanguages();
        return result;
    }

    private String buildDetailUrl(LanguageTraceSource source) {
        if (!StringUtils.hasText(source.baseUrl())
                || !StringUtils.hasText(source.detailPath())
                || !StringUtils.hasText(source.sourceVid())) {
            return null;
        }
        try {
            String path = substitute(source.detailPath(), source.sourceVid());
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString(source.baseUrl())
                    .path(path.startsWith("/") ? path : "/" + path);

            if (StringUtils.hasText(source.detailParams())) {
                Map<String, String> params = objectMapper.readValue(
                        source.detailParams(),
                        new TypeReference<>() {});
                params.forEach((key, value) -> builder.queryParam(key, substitute(value, source.sourceVid())));
            } else {
                builder.queryParam("ac", "detail").queryParam("ids", source.sourceVid());
            }

            return builder.toUriString();
        } catch (Exception e) {
            return "Error building URL: " + e.getMessage();
        }
    }

    private String substitute(String value, String sourceVid) {
        if (value == null) {
            return null;
        }
        return value
                .replace("{ids}", sourceVid)
                .replace("{id}", sourceVid)
                .replace("{sourceVid}", sourceVid)
                .replace("{vod_id}", sourceVid);
    }

    private DataSourceAdminRequest normalizeDataSource(DataSourceAdminRequest request, boolean defaults) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String listPath = normalizePath(request.listPath());
        String detailPath = normalizePath(request.detailPath());
        SourceNetworkOptions options = SourceNetworkOptions.from(request, defaults);
        return new DataSourceAdminRequest(
                request.id(),
                trimToNull(request.name()),
                baseUrl,
                listPath,
                normalizeJson(request.listParams(), "listParams"),
                detailPath,
                normalizeJson(request.detailParams(), "detailParams"),
                normalizeJson(request.fieldMapping(), "fieldMapping"),
                options.transportMode(),
                options.httpProtocol(),
                options.ipVersionPolicy(),
                options.dnsResolverType(),
                options.dnsResolverEndpoint(),
                options.connectTimeoutMs(),
                options.readTimeoutMs(),
                options.userAgent(),
                normalizeAdultRestricted(request, defaults));
    }

    private Boolean normalizeAdultRestricted(DataSourceAdminRequest request, boolean defaults) {
        if (defaults) {
            return Boolean.TRUE.equals(request.adultRestricted());
        }
        return request.adultRestricted();
    }

    private void syncCategoriesBestEffort(DataSourceRead dataSource) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSyncCategoriesBestEffort(dataSource);
                }
            });
            return;
        }
        doSyncCategoriesBestEffort(dataSource);
    }

    private void doSyncCategoriesBestEffort(DataSourceRead dataSource) {
        try {
            CatalogRemoteCategorySyncService.CategorySyncResult result =
                    remoteCategorySyncService.syncDataSourceCategories(dataSource.id());
            if (!result.success()) {
                log.debug("Remote category sync skipped for data source [{}]: {}", dataSource.name(), result.error());
            }
        } catch (RuntimeException ex) {
            log.warn("Remote category sync failed after data source write [{}]: {}", dataSource.name(), ex.getMessage());
        }
    }

    private void validateDataSource(DataSourceAdminRequest request, boolean full) {
        if (full || request.name() != null) {
            requireText(request.name(), "Data source name is required");
        }
        if (full || request.baseUrl() != null) {
            requireText(request.baseUrl(), "Base URL is required");
        }
        if (full || request.listPath() != null) {
            requireText(request.listPath(), "List path is required");
        }
        if (full || request.detailPath() != null) {
            requireText(request.detailPath(), "Detail path is required");
        }
    }

    private String buildSampleUrl(FetchSampleRequest request) {
        if (request == null || request.requestType() == null) {
            throw badRequest("Request type (LIST or DETAIL) must be specified.");
        }
        if (request.requestType() == FetchSampleRequestType.LIST) {
            return buildListUrl(request);
        }
        return buildDetailUrl(request);
    }

    private String buildListUrl(FetchSampleRequest request) {
        if (!StringUtils.hasText(request.baseUrl()) || !StringUtils.hasText(request.listPath())) {
            return null;
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(normalizeBaseUrl(request.baseUrl()))
                .path(normalizePath(request.listPath()))
                .queryParam("limit", "20")
                .queryParam("pagesize", "20");

        Map<String, String> params = parseJsonMap(request.listParams(), "listParams");
        if (params.isEmpty()) {
            builder.queryParam("ac", "list").queryParam("pg", 1);
        } else {
            params.forEach((key, value) -> builder.queryParam(key, substituteDataSourceParam(value, "1")));
        }
        return builder.toUriString();
    }

    private String buildDetailUrl(FetchSampleRequest request) {
        if (!StringUtils.hasText(request.baseUrl())
                || !StringUtils.hasText(request.detailPath())
                || !StringUtils.hasText(request.sampleId())) {
            return null;
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(normalizeBaseUrl(request.baseUrl()))
                .path(normalizePath(request.detailPath()));

        Map<String, String> params = parseJsonMap(request.detailParams(), "detailParams");
        if (params.isEmpty()) {
            builder.queryParam("ac", "detail").queryParam("ids", request.sampleId());
        } else {
            params.forEach((key, value) -> builder.queryParam(key, substitute(value, request.sampleId())));
        }
        return builder.toUriString();
    }

    private String substituteDataSourceParam(String value, String page) {
        if (value == null) {
            return null;
        }
        return value
                .replace("{page}", page)
                .replace("{t}", "")
                .replace("{h}", "")
                .replace("{wd}", "");
    }

    private Map<String, String> parseJsonMap(String json, String field) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            throw badRequest(field + " must be valid JSON object", ex);
        }
    }

    private String normalizeJson(String json, String field) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json).toString();
        } catch (Exception ex) {
            throw badRequest(field + " must be valid JSON", ex);
        }
    }

    private String normalizeBaseUrl(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizePath(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw badRequest(message);
        }
    }

    private void validatePathId(UUID pathId, UUID bodyId) {
        if (bodyId == null) {
            throw badRequest("Invalid id");
        }
        if (!Objects.equals(pathId, bodyId)) {
            throw badRequest("Invalid ID");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException badRequest(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message, cause);
    }

    private ResponseStatusException conflict(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message, cause);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
