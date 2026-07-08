package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageResponse;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageRow;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.ExtractedSourceDomain;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.NormalizedFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class CoverImageAdminRepository {

    private static final int MAX_PAGE_SIZE = 100;
    private static final UUID SENTINEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String SENTINEL_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "createdAt", "ci.created_at",
            "updatedAt", "ci.updated_at",
            "storageType", "ci.storage_type",
            "status", "ci.status",
            "originalUrl", "ci.original_url",
            "fileSize", "ci.file_size");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CoverImageUrlResolver urlResolver;

    public Page<CoverImageResponse> findAll(
            Pageable pageable,
            CoverImageStatus status,
            CoverImageStorageType storageType,
            String url,
            String sourceDomain,
            Long minFileSize,
            Long maxFileSize) {
        Pageable safe = sanitize(pageable);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safe.getPageSize())
                .addValue("offset", safe.getOffset());
        String where = where(params, status, storageType, url, sourceDomain, minFileSize, maxFileSize);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from cover_images ci left join source_domains sd on ci.source_domain_id = sd.id " + where,
                params,
                Long.class);
        List<CoverImageResponse> content = jdbcTemplate.query(
                selectSql(where) + orderBy(safe),
                params,
                (rs, rowNum) -> toResponse(mapRow(rs)));
        return new PageImpl<>(content, safe, total == null ? 0 : total);
    }

    public Optional<CoverImageResponse> findResponseById(UUID id) {
        return findRowById(id).map(this::toResponse);
    }

    public List<UUID> findDownloadCandidates(int limit) {
        return jdbcTemplate.queryForList(
                """
                select id
                from cover_images
                where storage_type = 'EXTERNAL'
                  and status in ('UNPROCESSED', 'FAILED')
                  and (next_retry_time is null or next_retry_time <= now())
                  and nullif(trim(original_url), '') is not null
                order by
                  case status when 'FAILED' then 0 else 1 end,
                  next_retry_time asc nulls first,
                  updated_at asc,
                  id
                limit :limit
                """,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                UUID.class);
    }

    public Optional<CoverImageRow> findRowById(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectSql("where ci.id = :id"),
                    new MapSqlParameterSource("id", id),
                    (rs, rowNum) -> mapRow(rs)));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public CoverImageResponse createLocal(NormalizedFile file) {
        UUID sourceDomainId = getLocalSentinelId();
        UUID imageId = jdbcTemplate.queryForObject(
                """
                insert into cover_images (
                    id, created_at, updated_at, version, storage_type, original_url, storage_path,
                    file_hash, file_size, mime_type, source_domain_id, status, retry_count,
                    next_retry_time, last_error
                ) values (
                    :id, now(), now(), 0, 'LOCAL', :originalUrl, :storagePath,
                    :fileHash, :fileSize, :mimeType, :sourceDomainId, 'LOCAL_STORED', 0,
                    null, null
                )
                on conflict (original_url, source_domain_id) do update
                set updated_at = cover_images.updated_at
                returning id
                """,
                new MapSqlParameterSource()
                        .addValue("id", IrcsUuidGenerators.nextId())
                        .addValue("originalUrl", file.storageKey())
                        .addValue("storagePath", file.storageKey())
                        .addValue("fileHash", file.hash())
                        .addValue("fileSize", file.size())
                        .addValue("mimeType", file.mimeType())
                        .addValue("sourceDomainId", sourceDomainId),
                UUID.class);
        return findResponseById(imageId).orElseThrow();
    }

    public CoverImageRow getOrCreateExternalReference(String url) {
        ExtractedSourceDomain extracted = extractSourceDomain(url);
        UUID imageId = jdbcTemplate.queryForObject(
                """
                insert into cover_images (
                    id, created_at, updated_at, version, storage_type, original_url, storage_path,
                    file_hash, file_size, mime_type, source_domain_id, status, retry_count,
                    next_retry_time, last_error
                ) values (
                    :id, now(), now(), 0, 'EXTERNAL', :originalUrl, null,
                    null, null, null, :sourceDomainId, 'UNPROCESSED', 0,
                    now(), null
                )
                on conflict (original_url, source_domain_id) do update
                set updated_at = cover_images.updated_at
                returning id
                """,
                new MapSqlParameterSource()
                        .addValue("id", IrcsUuidGenerators.nextId())
                        .addValue("originalUrl", extracted.originalUrl())
                        .addValue("sourceDomainId", extracted.sourceDomainId()),
                UUID.class);
        jdbcTemplate.update(
                """
                update cover_images
                set status = 'UNPROCESSED',
                    retry_count = 0,
                    next_retry_time = now(),
                    last_error = null,
                    updated_at = now()
                where id = :id
                  and status in ('FAILED', 'DEAD')
                """,
                new MapSqlParameterSource("id", imageId));
        return findRowById(imageId).orElseThrow();
    }

    public boolean markPendingDelete(UUID id) {
        int updated = jdbcTemplate.update(
                """
                update cover_images
                set status = 'PENDING_DELETE',
                    next_retry_time = now(),
                    updated_at = now()
                where id = :id
                  and status <> 'PENDING_DELETE'
                """,
                new MapSqlParameterSource("id", id));
        return updated > 0;
    }

    public void resetForDownload(UUID id) {
        int updated = jdbcTemplate.update(
                """
                update cover_images
                set status = 'UNPROCESSED',
                    storage_type = 'EXTERNAL',
                    retry_count = 0,
                    next_retry_time = now(),
                    last_error = null,
                    storage_path = null,
                    updated_at = now()
                where id = :id
                """,
                new MapSqlParameterSource("id", id));
        if (updated == 0) {
            throw new StorageApiException(HttpStatus.NOT_FOUND, "Image not found: " + id);
        }
    }

    public boolean markFetching(UUID id) {
        int updated = jdbcTemplate.update(
                """
                update cover_images
                set status = 'FETCHING',
                    updated_at = now()
                where id = :id
                  and storage_type = 'EXTERNAL'
                  and status in ('UNPROCESSED', 'FAILED')
                """,
                new MapSqlParameterSource("id", id));
        return updated > 0;
    }

    public void finalizeDownload(UUID id, NormalizedFile file) {
        jdbcTemplate.update(
                """
                update cover_images
                set storage_type = 'LOCAL',
                    storage_path = :storagePath,
                    file_hash = :fileHash,
                    file_size = :fileSize,
                    mime_type = :mimeType,
                    status = 'LOCAL_STORED',
                    retry_count = 0,
                    next_retry_time = null,
                    last_error = null,
                    updated_at = now()
                where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("storagePath", file.storageKey())
                        .addValue("fileHash", file.hash())
                        .addValue("fileSize", file.size())
                        .addValue("mimeType", file.mimeType()));
    }

    public void markUploading(UUID id) {
        jdbcTemplate.update(
                """
                update cover_images
                set status = 'UPLOADING',
                    updated_at = now()
                where id = :id
                """,
                new MapSqlParameterSource("id", id));
    }

    public void finalizeUpload(UUID id, String storagePath) {
        jdbcTemplate.update(
                """
                update cover_images
                set storage_type = 'R2',
                    storage_path = :storagePath,
                    status = 'REMOTE_STORED',
                    retry_count = 0,
                    next_retry_time = null,
                    last_error = null,
                    updated_at = now()
                where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("storagePath", storagePath));
    }

    public void markFailed(UUID id, String message, int maxRetries) {
        CoverImageRow row = findRowById(id).orElse(null);
        if (row == null) {
            return;
        }
        int retryCount = row.retryCount() == null ? 1 : row.retryCount() + 1;
        String status = retryCount >= maxRetries ? "DEAD" : "FAILED";
        Timestamp nextRetry = retryCount >= maxRetries
                ? null
                : Timestamp.from(Instant.now().plusSeconds((long) Math.pow(2, retryCount) * 30));
        jdbcTemplate.update(
                """
                update cover_images
                set status = :status,
                    retry_count = :retryCount,
                    next_retry_time = :nextRetryTime,
                    last_error = :lastError,
                    updated_at = now()
                where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status)
                        .addValue("retryCount", retryCount)
                        .addValue("nextRetryTime", nextRetry)
                        .addValue("lastError", truncate(message, 500)));
    }

    private CoverImageResponse toResponse(CoverImageRow row) {
        return new CoverImageResponse(
                row.id(),
                row.storageType(),
                row.status(),
                urlResolver.resolve(row),
                row.originalUrl(),
                row.storagePath(),
                row.fileSize(),
                row.mimeType(),
                row.fileHash(),
                row.sourceDomainValue(),
                row.retryCount(),
                row.lastError(),
                row.createdAt(),
                row.updatedAt());
    }

    private String where(
            MapSqlParameterSource params,
            CoverImageStatus status,
            CoverImageStorageType storageType,
            String url,
            String sourceDomain,
            Long minFileSize,
            Long maxFileSize) {
        List<String> predicates = new ArrayList<>();
        if (status != null) {
            predicates.add("ci.status = :status");
            params.addValue("status", status.name());
        }
        if (storageType != null) {
            predicates.add("ci.storage_type = :storageType");
            params.addValue("storageType", storageType.name());
        }
        if (StringUtils.hasText(url)) {
            predicates.add("lower(ci.original_url) like :url");
            params.addValue("url", "%" + url.toLowerCase() + "%");
        }
        if (StringUtils.hasText(sourceDomain)) {
            predicates.add("lower(coalesce(sd.domain_value, '')) like :sourceDomain");
            params.addValue("sourceDomain", "%" + sourceDomain.toLowerCase() + "%");
        }
        if (minFileSize != null) {
            predicates.add("coalesce(ci.file_size, 0) >= :minFileSize");
            params.addValue("minFileSize", Math.max(0, minFileSize));
        }
        if (maxFileSize != null) {
            predicates.add("coalesce(ci.file_size, 0) <= :maxFileSize");
            params.addValue("maxFileSize", Math.max(0, maxFileSize));
        }
        return predicates.isEmpty() ? "" : "where " + String.join(" and ", predicates);
    }

    private String selectSql(String where) {
        return """
                select ci.id, ci.storage_type, ci.status, ci.original_url, ci.storage_path,
                       ci.file_size, ci.mime_type, ci.file_hash, ci.source_domain_id,
                       sd.domain_value as source_domain_value,
                       ci.retry_count, ci.last_error, ci.next_retry_time,
                       ci.created_at, ci.updated_at
                from cover_images ci
                left join source_domains sd on ci.source_domain_id = sd.id
                """ + where + " ";
    }

    private String orderBy(Pageable pageable) {
        List<String> orders = new ArrayList<>();
        pageable.getSort().forEach(order -> {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column != null) {
                orders.add(column + (order.isAscending() ? " asc" : " desc"));
            }
        });
        if (orders.isEmpty()) {
            orders.add("ci.created_at desc");
        }
        return " order by " + String.join(", ", orders) + " limit :limit offset :offset";
    }

    private Pageable sanitize(Pageable pageable) {
        int page = Math.max(0, pageable.getPageNumber());
        int size = Math.min(Math.max(1, pageable.getPageSize()), MAX_PAGE_SIZE);
        return PageRequest.of(page, size, pageable.getSort());
    }

    private CoverImageRow mapRow(ResultSet rs) throws SQLException {
        return new CoverImageRow(
                rs.getObject("id", UUID.class),
                CoverImageStorageType.valueOf(rs.getString("storage_type")),
                CoverImageStatus.valueOf(rs.getString("status")),
                rs.getString("original_url"),
                rs.getString("storage_path"),
                (Long) rs.getObject("file_size"),
                rs.getString("mime_type"),
                rs.getString("file_hash"),
                rs.getObject("source_domain_id", UUID.class),
                rs.getString("source_domain_value"),
                (Integer) rs.getObject("retry_count"),
                rs.getString("last_error"),
                instant(rs, "next_retry_time"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private ExtractedSourceDomain extractSourceDomain(String rawUrl) {
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("http")) {
            try {
                URI uri = URI.create(trimmed);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (StringUtils.hasText(scheme) && StringUtils.hasText(host)) {
                    String domain = scheme + "://" + host + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
                    UUID sourceDomainId = getOrCreateSourceDomain(domain, "Auto-discovered");
                    StringBuilder relative = new StringBuilder();
                    if (uri.getRawPath() != null) {
                        relative.append(uri.getRawPath());
                    }
                    if (uri.getRawQuery() != null) {
                        relative.append("?").append(uri.getRawQuery());
                    }
                    if (uri.getRawFragment() != null) {
                        relative.append("#").append(uri.getRawFragment());
                    }
                    return new ExtractedSourceDomain(sourceDomainId, domain, relative.toString());
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ExtractedSourceDomain(getLocalSentinelId(), "LOCAL_STORAGE", trimmed);
    }

    private UUID getLocalSentinelId() {
        return jdbcTemplate.queryForObject(
                """
                insert into source_domains (id, created_at, updated_at, version, domain_hash, domain_value, remark, data_source_id)
                values (:id, now(), now(), 0, :hash, 'LOCAL_STORAGE', 'System Sentinel', null)
                on conflict (domain_hash) do update
                set updated_at = source_domains.updated_at
                returning id
                """,
                new MapSqlParameterSource()
                        .addValue("id", SENTINEL_ID)
                        .addValue("hash", SENTINEL_HASH),
                UUID.class);
    }

    private UUID getOrCreateSourceDomain(String domain, String remark) {
        return jdbcTemplate.queryForObject(
                """
                insert into source_domains (id, created_at, updated_at, version, domain_hash, domain_value, remark, data_source_id)
                values (:id, now(), now(), 0, :hash, :domain, :remark, null)
                on conflict (domain_hash) do update
                set updated_at = source_domains.updated_at
                returning id
                """,
                new MapSqlParameterSource()
                        .addValue("id", IrcsUuidGenerators.nextId())
                        .addValue("hash", sha256(domain))
                        .addValue("domain", domain)
                        .addValue("remark", remark),
                UUID.class);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
