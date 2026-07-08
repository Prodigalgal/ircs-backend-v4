package com.prodigalgal.ircs.catalog;

import java.time.Instant;
import java.util.UUID;

public record DataSourceRead(
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
        boolean adultRestricted,
        Instant createdAt,
        Instant updatedAt) {

    public DataSourceRead(
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
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                name,
                baseUrl,
                listPath,
                listParams,
                detailPath,
                detailParams,
                fieldMapping,
                transportMode,
                httpProtocol,
                ipVersionPolicy,
                dnsResolverType,
                dnsResolverEndpoint,
                connectTimeoutMs,
                readTimeoutMs,
                userAgent,
                false,
                createdAt,
                updatedAt);
    }

    public DataSourceRead(
            UUID id,
            String name,
            String baseUrl,
            String listPath,
            String listParams,
            String detailPath,
            String detailParams,
            String fieldMapping,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                name,
                baseUrl,
                listPath,
                listParams,
                detailPath,
                detailParams,
                fieldMapping,
                "AUTO",
                "AUTO",
                "AUTO",
                "SYSTEM",
                null,
                10000,
                10000,
                null,
                false,
                createdAt,
                updatedAt);
    }
}
