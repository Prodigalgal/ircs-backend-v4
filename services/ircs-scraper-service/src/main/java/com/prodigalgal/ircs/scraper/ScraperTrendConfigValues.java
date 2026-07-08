package com.prodigalgal.ircs.scraper;

import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ScraperTrendConfigValues {

    private static final String DEFAULT_CONTENT_BASE_URL =
            "http://ircs-content-service.ircs-dev.svc.cluster.local:8080";
    private static final String DEFAULT_TASK_BASE_URL =
            "http://ircs-task-service.ircs-dev.svc.cluster.local:8080";
    private static final String DEFAULT_CREDENTIAL_BASE_URL =
            "http://ircs-credential-service.ircs-dev.svc.cluster.local:8080";

    private final Environment environment;

    ScraperTrendConfigValues(Environment environment) {
        this.environment = environment;
    }

    boolean doubanEnabled() {
        return booleanValue(true, "app.scraper.trend-sync.douban-enabled", "APP_SCRAPER_TREND_SYNC_DOUBAN_ENABLED");
    }

    boolean tmdbEnabled() {
        return booleanValue(true, "app.scraper.trend-sync.tmdb-enabled", "APP_SCRAPER_TREND_SYNC_TMDB_ENABLED");
    }

    String tmdbApiKey() {
        return stringValue("", "app.scraper.trend-sync.tmdb-api-key", "APP_SCRAPER_TMDB_API_KEY", "TMDB_API_KEY");
    }

    boolean tmdbCredentialServiceEnabled() {
        return booleanValue(
                true,
                "app.scraper.trend-sync.credential-service.enabled",
                "APP_SCRAPER_CREDENTIAL_SERVICE_ENABLED");
    }

    String tmdbCredentialServiceBaseUrl() {
        return stringValue(
                DEFAULT_CREDENTIAL_BASE_URL,
                "app.scraper.trend-sync.credential-service.base-url",
                "APP_SCRAPER_CREDENTIAL_SERVICE_BASE_URL",
                "APP_CREDENTIAL_SERVICE_BASE_URL");
    }

    int tmdbCredentialServiceLeaseLimit() {
        return intValue(
                1,
                1,
                20,
                "app.scraper.trend-sync.credential-service.lease-limit",
                "APP_SCRAPER_CREDENTIAL_SERVICE_LEASE_LIMIT");
    }

    String tmdbCredentialServiceToken() {
        return stringValue(
                "",
                "app.scraper.trend-sync.credential-service.token",
                "APP_SCRAPER_CREDENTIAL_SERVICE_TOKEN",
                "APP_CREDENTIAL_SERVICE_TOKEN",
                "SERVICE_CREDENTIAL_TOKEN");
    }

    String tmdbCredentialServiceId() {
        return stringValue(
                "scraper-service",
                "app.scraper.trend-sync.credential-service.service-id",
                "APP_SCRAPER_CREDENTIAL_SERVICE_ID");
    }

    String tmdbCredentialServiceScopes() {
        return stringValue(
                "credential:lease",
                "app.scraper.trend-sync.credential-service.scopes",
                "APP_SCRAPER_CREDENTIAL_SERVICE_SCOPES");
    }

    Duration tmdbCredentialServiceRequestTimeout() {
        return durationValue(
                Duration.ofSeconds(10),
                "app.scraper.trend-sync.credential-service.request-timeout",
                "APP_SCRAPER_CREDENTIAL_SERVICE_REQUEST_TIMEOUT");
    }

    Duration providerTimeout() {
        return durationValue(
                Duration.ofSeconds(20),
                "app.scraper.trend-sync.provider-timeout",
                "APP_SCRAPER_TREND_SYNC_PROVIDER_TIMEOUT");
    }

    int maxProviderItems() {
        return intValue(100, 0, 500, "app.scraper.trend-sync.max-provider-items", "APP_SCRAPER_TREND_SYNC_MAX_PROVIDER_ITEMS");
    }

    String contentOwnerBaseUrl() {
        return stringValue(
                DEFAULT_CONTENT_BASE_URL,
                "app.scraper.trend-sync.content-owner-base-url",
                "APP_SCRAPER_CONTENT_SERVICE_BASE_URL");
    }

    Duration contentOwnerRequestTimeout() {
        return durationValue(
                Duration.ofSeconds(30),
                "app.scraper.trend-sync.content-owner-request-timeout",
                "APP_SCRAPER_CONTENT_SERVICE_REQUEST_TIMEOUT");
    }

    String contentOwnerServiceId() {
        return stringValue("scraper-service", "app.scraper.trend-sync.content-owner-service-id", "APP_SCRAPER_SERVICE_ID");
    }

    String contentOwnerServiceToken() {
        return stringValue(
                "",
                "app.scraper.trend-sync.content-owner-service-token",
                "APP_SCRAPER_CONTENT_SERVICE_TOKEN",
                "APP_CONTENT_SERVICE_TOKEN");
    }

    String contentOwnerScopes() {
        return stringValue(
                "content:maintenance",
                "app.scraper.trend-sync.content-owner-scopes",
                "APP_SCRAPER_CONTENT_SERVICE_SCOPES");
    }

    boolean trendDiscoveryEnabled() {
        return booleanValue(true, "app.scraper.trend-sync.discovery-enabled", "APP_SCRAPER_TREND_DISCOVERY_ENABLED");
    }

    int trendDiscoveryMaxDataSources() {
        return intValue(
                0,
                0,
                1000,
                "app.scraper.trend-sync.discovery-max-data-sources",
                "APP_SCRAPER_TREND_DISCOVERY_MAX_DATA_SOURCES");
    }

    String taskOwnerBaseUrl() {
        return stringValue(
                DEFAULT_TASK_BASE_URL,
                "app.scraper.trend-sync.task-owner-base-url",
                "APP_SCRAPER_TASK_SERVICE_BASE_URL");
    }

    Duration taskOwnerRequestTimeout() {
        return durationValue(
                Duration.ofSeconds(30),
                "app.scraper.trend-sync.task-owner-request-timeout",
                "APP_SCRAPER_TASK_SERVICE_REQUEST_TIMEOUT");
    }

    String taskOwnerServiceId() {
        return stringValue("scraper-service", "app.scraper.trend-sync.task-owner-service-id", "APP_SCRAPER_SERVICE_ID");
    }

    String taskOwnerServiceToken() {
        return stringValue(
                "",
                "app.scraper.trend-sync.task-owner-service-token",
                "APP_SCRAPER_TASK_SERVICE_TOKEN",
                "APP_TASK_SERVICE_TOKEN");
    }

    String taskOwnerScopes() {
        return stringValue(
                "task:maintenance",
                "app.scraper.trend-sync.task-owner-scopes",
                "APP_SCRAPER_TASK_SERVICE_SCOPES");
    }

    private boolean booleanValue(boolean fallback, String... keys) {
        String value = firstValue(keys);
        return StringUtils.hasText(value) ? Boolean.parseBoolean(value.trim()) : fallback;
    }

    private int intValue(int fallback, int min, int max, String... keys) {
        String value = firstValue(keys);
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Duration durationValue(Duration fallback, String... keys) {
        String value = firstValue(keys);
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Duration.parse(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String stringValue(String fallback, String... keys) {
        String value = firstValue(keys);
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String firstValue(String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
