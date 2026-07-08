package com.prodigalgal.ircs.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

record DataSourceAdminRequest(
        UUID id,
        String name,
        String baseUrl,
        String listPath,
        String listParams,
        String detailPath,
        String detailParams,
        String fieldMapping,
        @Size(max = 20) String transportMode,
        @Size(max = 20) String httpProtocol,
        @Size(max = 20) String ipVersionPolicy,
        @Size(max = 20) String dnsResolverType,
        @Size(max = 512) String dnsResolverEndpoint,
        @Min(1) @Max(120000) Integer connectTimeoutMs,
        @Min(1) @Max(120000) Integer readTimeoutMs,
        @Size(max = 512) String userAgent,
        Boolean adultRestricted) {

    DataSourceAdminRequest(
            UUID id,
            String name,
            String baseUrl,
            String listPath,
            String listParams,
            String detailPath,
            String detailParams,
            String fieldMapping) {
        this(id, name, baseUrl, listPath, listParams, detailPath, detailParams, fieldMapping,
                null, null, null, null, null, null, null, null, null);
    }
}

record FetchSampleRequest(
        @NotNull
        FetchSampleRequestType requestType,
        String baseUrl,
        String listPath,
        String listParams,
        String detailPath,
        String detailParams,
        String sampleId,
        @Size(max = 20) String transportMode,
        @Size(max = 20) String httpProtocol,
        @Size(max = 20) String ipVersionPolicy,
        @Size(max = 20) String dnsResolverType,
        @Size(max = 512) String dnsResolverEndpoint,
        @Min(1) @Max(120000) Integer connectTimeoutMs,
        @Min(1) @Max(120000) Integer readTimeoutMs,
        @Size(max = 512) String userAgent) {

    FetchSampleRequest(
            FetchSampleRequestType requestType,
            String baseUrl,
            String listPath,
            String listParams,
            String detailPath,
            String detailParams,
            String sampleId) {
        this(requestType, baseUrl, listPath, listParams, detailPath, detailParams, sampleId,
                null, null, null, null, null, null, null, null);
    }
}

enum FetchSampleRequestType {
    LIST,
    DETAIL;

    @JsonCreator
    static FetchSampleRequestType from(String value) {
        if (value == null) {
            return null;
        }
        return FetchSampleRequestType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}

record StandardCategoryAdminRequest(
        UUID id,
        String name,
        String slug,
        Instant createdAt,
        Instant updatedAt) {}

record StandardGenreAdminRequest(UUID id, String name, String code) {

    StandardGenreAdminRequest(UUID id, String name) {
        this(id, name, null);
    }
}

record StandardAreaAdminRequest(
        UUID id,
        String name,
        String code,
        String region,
        Instant createdAt,
        Instant updatedAt) {}

record StandardLanguageAdminRequest(
        UUID id,
        String name,
        String code,
        String englishName,
        String nativeName) {}
