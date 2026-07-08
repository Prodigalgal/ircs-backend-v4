package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.contracts.ingestion.IngestionPlaylistDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ScraperDtos {

    private ScraperDtos() {
    }

    public record ManualScrapeConfigRequest(
            @NotBlank(message = "Keyword cannot be empty") String keyword,
            String filterType,
            Integer filterHours,
            @Min(1) Integer startPage,
            @Min(1) Integer endPage,
            String userAgent,
            boolean enableRandomUa,
            boolean useCustomProxy,
            String proxyType,
            String proxyHost,
            Integer proxyPort,
            String proxyUsername,
            String proxyPassword,
            String headers,
            Integer fixedDelayMs,
            boolean forceIngest,
            @Valid List<DirectScrapeItem> directItems) {

        int effectiveStartPage() {
            return startPage == null ? 1 : startPage;
        }

        int effectiveEndPage() {
            return endPage == null ? effectiveStartPage() : endPage;
        }
    }

    public record DirectScrapeItem(
            UUID dataSourceId,
            @NotBlank(message = "sourceVid cannot be empty") String sourceVid,
            @NotBlank(message = "title cannot be empty") String title,
            String aliasTitle,
            String description,
            String coverImageUrl,
            String year,
            String area,
            String language,
            String remarks,
            BigDecimal score,
            LocalDate publishedAt,
            String totalEpisodes,
            String duration,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            String rawMetadata,
            List<IngestionPlaylistDTO> playlists) {
    }

    public record InitSessionResponse(String sessionId) {
    }

    public record TaskExecutionRequest(
            UUID taskId,
            UUID dataSourceId,
            String keyword,
            String filterType,
            Integer filterHours,
            Integer startPage,
            Integer endPage,
            String userAgent,
            boolean enableRandomUa,
            boolean useCustomProxy,
            String proxyType,
            String proxyHost,
            Integer proxyPort,
            String proxyUsername,
            String proxyPassword,
            String headers,
            Integer fixedDelayMs,
            boolean forceIngest,
            @Valid List<DirectScrapeItem> directItems) {

        ManualScrapeConfigRequest toManualRequest() {
            return new ManualScrapeConfigRequest(
                    keyword == null || keyword.isBlank() ? "task-runner" : keyword,
                    filterType,
                    filterHours,
                    startPage,
                    endPage,
                    userAgent,
                    enableRandomUa,
                    useCustomProxy,
                    proxyType,
                    proxyHost,
                    proxyPort,
                    proxyUsername,
                    proxyPassword,
                    headers,
                    fixedDelayMs,
                    forceIngest,
                    directItems);
        }
    }

    public record TaskExecutionResult(
            String status,
            int publishedCount,
            int failedCount,
            List<TaskExecutionLog> logs) {
    }

    public record TaskExecutionLog(
            String timestamp,
            String level,
            String sourceVid,
            String message) {
    }

    public record ScrapeEvent(String type, Object payload, long timestamp) {

        public static ScrapeEvent log(String message) {
            return new ScrapeEvent("LOG", message, System.currentTimeMillis());
        }

        public static ScrapeEvent card(ScrapedVideoCard card) {
            return new ScrapeEvent("CARD", card, System.currentTimeMillis());
        }

        public static ScrapeEvent done() {
            return new ScrapeEvent("DONE", null, System.currentTimeMillis());
        }

        public static ScrapeEvent error(String message) {
            return new ScrapeEvent("ERROR", message, System.currentTimeMillis());
        }
    }

    public record ScrapedVideoCard(
            String sourceName,
            String title,
            String coverImageUrl,
            String videoId,
            String sourceVid,
            String status,
            String errorMessage,
            String year,
            String category,
            String detailUrl) {
    }

    record DataSourceRecord(
            UUID id,
            String name,
            String baseUrl,
            String listPath,
            String listParams,
            String detailPath,
            String detailParams,
            String fieldMapping,
            String transportMode,
            String httpProtocol,
            String ipVersionPolicy,
            String dnsResolverType,
            String dnsResolverEndpoint,
            Integer connectTimeoutMs,
            Integer readTimeoutMs,
            String userAgent,
            boolean adultRestricted) {

        DataSourceRecord(
                UUID id,
                String name,
                String baseUrl,
                String listPath,
                String listParams,
                String detailPath,
                String detailParams,
                String fieldMapping,
                String transportMode,
                String httpProtocol,
                String ipVersionPolicy,
                String dnsResolverType,
                String dnsResolverEndpoint,
                Integer connectTimeoutMs,
                Integer readTimeoutMs,
                String userAgent) {
            this(id, name, baseUrl, listPath, listParams, detailPath, detailParams, fieldMapping,
                    transportMode, httpProtocol, ipVersionPolicy, dnsResolverType, dnsResolverEndpoint,
                    connectTimeoutMs, readTimeoutMs, userAgent, false);
        }

        DataSourceRecord(
                UUID id,
                String name,
                String baseUrl,
                String listPath,
                String listParams,
                String detailPath,
                String detailParams,
                String fieldMapping) {
            this(id, name, baseUrl, listPath, listParams, detailPath, detailParams, fieldMapping,
                    "AUTO", "AUTO", "AUTO", "SYSTEM", null, 10000, 10000, null, false);
        }
    }

    record ScrapedVideoDraft(
            UUID dataSourceId,
            String sourceVid,
            String title,
            String aliasTitle,
            String description,
            String coverImageUrl,
            String year,
            String area,
            String language,
            String remarks,
            BigDecimal score,
            LocalDate publishedAt,
            String totalEpisodes,
            String duration,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            String rawTypeId,
            String rawTypeName,
            List<String> genreNames,
            List<String> actorNames,
            List<String> directorNames,
            String sourcePayload,
            String rawMetadata,
            List<IngestionPlaylistDTO> playlists) {
    }

    record ListItem(String id, String updateTime, Map<String, Object> raw) {
    }

    record ListPage(List<ListItem> items, Integer totalPages, Integer totalItems) {

        boolean hasPagination() {
            return totalPages != null || totalItems != null;
        }
    }
}
