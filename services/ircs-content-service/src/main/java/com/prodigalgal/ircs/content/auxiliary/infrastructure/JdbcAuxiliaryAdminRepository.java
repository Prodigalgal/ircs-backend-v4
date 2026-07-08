package com.prodigalgal.ircs.content.auxiliary.infrastructure;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.EpisodeRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.EpisodeResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistCardResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistDetailResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistUpdateRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.SourceDomainRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.SourceDomainResponse;
import com.prodigalgal.ircs.content.video.api.ContentApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
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
public class JdbcAuxiliaryAdminRepository {

    private static final int MAX_PAGE_SIZE = 100;
    private static final UUID SENTINEL_SOURCE_DOMAIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final Map<String, String> PLAYLIST_SORT_COLUMNS = Map.of(
            "id", "p.id",
            "name", "p.name",
            "videoTitle", "rv.title",
            "createdAt", "p.created_at",
            "updatedAt", "p.updated_at");
    private static final Map<String, String> SOURCE_DOMAIN_SORT_COLUMNS = Map.of(
            "id", "sd.id",
            "domainValue", "sd.domain_value",
            "dataSourceName", "ds.name",
            "createdAt", "sd.created_at",
            "updatedAt", "sd.updated_at");
    private static final Map<String, String> RESOLVER_SORT_COLUMNS = Map.of(
            "id", "id",
            "name", "name",
            "createdAt", "created_at",
            "updatedAt", "updated_at");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PlaylistDetailResponse createPlaylist(PlaylistRequest request) {
        assertRawVideoExists(request.videoId());
        UUID id = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                insert into playlists (id, created_at, updated_at, version, name, video_id)
                values (:id, :now, :now, 0, :name, :videoId)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", now)
                        .addValue("name", trimToLength(request.name(), 100))
                        .addValue("videoId", request.videoId()));
        replaceEpisodes(id, resolveDataSourceId(request.videoId()).orElse(null), request.episodes());
        return requirePlaylist(id);
    }

    public PlaylistDetailResponse updatePlaylist(UUID id, PlaylistUpdateRequest request) {
        if (!playlistExists(id)) {
            throw notFound("Playlist not found: " + id);
        }
        jdbcTemplate.update(
                """
                update playlists
                set name = :name, updated_at = :now, version = coalesce(version, 0) + 1
                where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("name", trimToLength(request.name(), 100))
                        .addValue("now", Timestamp.from(Instant.now())));
        if (request.episodes() != null) {
            UUID videoId = playlistVideoId(id);
            replaceEpisodes(id, resolveDataSourceId(videoId).orElse(null), request.episodes());
        }
        return requirePlaylist(id);
    }

    public Page<PlaylistCardResponse> findPlaylists(Pageable pageable, String name, String videoTitle) {
        Pageable safe = sanitize(pageable);
        MapSqlParameterSource params = pagingParams(safe);
        String where = playlistWhere(params, name, videoTitle);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from playlists p left join raw_videos rv on p.video_id = rv.id" + where,
                params,
                Long.class);
        List<PlaylistBaseCard> pageItems = jdbcTemplate.query(
                """
                select p.id, p.name, p.video_id
                from playlists p
                left join raw_videos rv on p.video_id = rv.id
                """
                        + where
                        + orderBy(safe, PLAYLIST_SORT_COLUMNS, "p.created_at desc")
                        + " limit :limit offset :offset",
                params,
                (rs, rowNum) -> new PlaylistBaseCard(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("video_id", UUID.class)));
        Map<UUID, Integer> episodeCounts = episodeCounts(pageItems.stream()
                .map(PlaylistBaseCard::id)
                .toList());
        List<PlaylistCardResponse> content = pageItems.stream()
                .map(item -> new PlaylistCardResponse(
                        item.id(),
                        item.name(),
                        item.videoId(),
                        episodeCounts.getOrDefault(item.id(), 0)))
                .toList();
        return new PageImpl<>(content, safe, total == null ? 0 : total);
    }

    private Map<UUID, Integer> episodeCounts(List<UUID> playlistIds) {
        if (playlistIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        jdbcTemplate.query(
                """
                select playlist_id, count(*) as episode_count
                from episodes
                where playlist_id in (:playlistIds)
                group by playlist_id
                """,
                new MapSqlParameterSource("playlistIds", playlistIds),
                (RowCallbackHandler) rs ->
                        counts.put(rs.getObject("playlist_id", UUID.class), rs.getInt("episode_count")));
        return counts;
    }

    public Optional<PlaylistDetailResponse> findPlaylist(UUID id) {
        try {
            PlaylistBase base = jdbcTemplate.queryForObject(
                    """
                    select p.id, p.name, p.video_id, rv.title as video_title, p.created_at, p.updated_at
                    from playlists p
                    left join raw_videos rv on p.video_id = rv.id
                    where p.id = :id
                    """,
                    new MapSqlParameterSource("id", id),
                    this::mapPlaylistBase);
            return Optional.of(new PlaylistDetailResponse(
                    base.id(),
                    base.name(),
                    base.videoId(),
                    base.videoTitle(),
                    findEpisodes(id),
                    base.createdAt(),
                    base.updatedAt()));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public void deletePlaylist(UUID id) {
        if (!playlistExists(id)) {
            throw notFound("Playlist not found: " + id);
        }
        jdbcTemplate.update("delete from episodes where playlist_id = :id", new MapSqlParameterSource("id", id));
        jdbcTemplate.update("delete from playlists where id = :id", new MapSqlParameterSource("id", id));
    }

    public Page<SourceDomainResponse> findSourceDomains(Pageable pageable, String keyword, UUID dataSourceId) {
        Pageable safe = sanitize(pageable);
        MapSqlParameterSource params = pagingParams(safe);
        String where = sourceDomainWhere(params, keyword, dataSourceId);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from source_domains sd left join data_sources ds on sd.data_source_id = ds.id" + where,
                params,
                Long.class);
        List<SourceDomainResponse> content = jdbcTemplate.query(
                """
                select sd.id, sd.domain_hash, sd.domain_value, sd.remark, sd.data_source_id,
                       ds.name as data_source_name,
                       coalesce(ds.adult_restricted, false) as adult_restricted,
                       sd.created_at, sd.updated_at
                from source_domains sd
                left join data_sources ds on sd.data_source_id = ds.id
                """
                        + where
                        + orderBy(safe, SOURCE_DOMAIN_SORT_COLUMNS, "sd.domain_value asc")
                        + " limit :limit offset :offset",
                params,
                this::mapSourceDomain);
        return new PageImpl<>(content, safe, total == null ? 0 : total);
    }

    public Optional<SourceDomainResponse> findSourceDomain(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select sd.id, sd.domain_hash, sd.domain_value, sd.remark, sd.data_source_id,
                           ds.name as data_source_name,
                           coalesce(ds.adult_restricted, false) as adult_restricted,
                           sd.created_at, sd.updated_at
                    from source_domains sd
                    left join data_sources ds on sd.data_source_id = ds.id
                    where sd.id = :id
                    """,
                    new MapSqlParameterSource("id", id),
                    this::mapSourceDomain));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public SourceDomainResponse updateSourceDomain(UUID id, SourceDomainRequest request) {
        SourceDomainResponse existing = findSourceDomain(id).orElseThrow(() -> notFound("Source domain not found: " + id));
        if (SENTINEL_SOURCE_DOMAIN_ID.equals(existing.id())) {
            throw new ContentApiException(HttpStatus.BAD_REQUEST, "Cannot modify system sentinel domain.");
        }
        jdbcTemplate.update(
                """
                update source_domains
                set domain_value = :domainValue,
                    remark = :remark,
                    data_source_id = :dataSourceId,
                    updated_at = :now,
                    version = coalesce(version, 0) + 1
                where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("domainValue", trimToLength(request.domainValue(), 255))
                        .addValue("remark", trimToLength(request.remark(), 255))
                        .addValue("dataSourceId", request.dataSourceId())
                        .addValue("now", Timestamp.from(Instant.now())));
        return findSourceDomain(id).orElseThrow(() -> notFound("Source domain not found after update: " + id));
    }

    public ResolverResponse createResolver(ResolverRequest request) {
        UUID id = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                insert into video_resolver_sources (id, created_at, updated_at, version, name, is_active, remark, lines)
                values (:id, :now, :now, 0, :name, :active, :remark, cast(:lines as jsonb))
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", now)
                        .addValue("name", trimToLength(request.name(), 255))
                        .addValue("active", request.activeValue())
                        .addValue("remark", trimToLength(request.remark(), 255))
                        .addValue("lines", toJson(request.lines())));
        return requireResolver(id);
    }

    public boolean resolverExistsByName(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from video_resolver_sources where name = :name)",
                new MapSqlParameterSource("name", name.trim()),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public ResolverResponse updateResolver(UUID id, ResolverRequest request) {
        if (!resolverExists(id)) {
            throw notFound("Resolver not found: " + id);
        }
        jdbcTemplate.update(
                """
                update video_resolver_sources
                set name = :name,
                    is_active = :active,
                    remark = :remark,
                    lines = cast(:lines as jsonb),
                    updated_at = :now,
                    version = coalesce(version, 0) + 1
                where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("name", trimToLength(request.name(), 255))
                        .addValue("active", request.activeValue())
                        .addValue("remark", trimToLength(request.remark(), 255))
                        .addValue("lines", toJson(request.lines()))
                        .addValue("now", Timestamp.from(Instant.now())));
        return requireResolver(id);
    }

    public Page<ResolverResponse> findResolvers(Pageable pageable) {
        Pageable safe = sanitize(pageable);
        MapSqlParameterSource params = pagingParams(safe);
        Long total = jdbcTemplate.queryForObject("select count(*) from video_resolver_sources", params, Long.class);
        List<ResolverResponse> content = jdbcTemplate.query(
                "select id, name, is_active, remark, lines from video_resolver_sources "
                        + orderBy(safe, RESOLVER_SORT_COLUMNS, "created_at desc")
                        + " limit :limit offset :offset",
                params,
                this::mapResolver);
        return new PageImpl<>(content, safe, total == null ? 0 : total);
    }

    public List<ResolverResponse> findActiveResolvers() {
        return jdbcTemplate.query(
                "select id, name, is_active, remark, lines from video_resolver_sources where is_active = true order by name asc",
                new MapSqlParameterSource(),
                this::mapResolver);
    }

    public Optional<ResolverResponse> findResolver(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "select id, name, is_active, remark, lines from video_resolver_sources where id = :id",
                    new MapSqlParameterSource("id", id),
                    this::mapResolver));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public void deleteResolver(UUID id) {
        if (!resolverExists(id)) {
            throw notFound("Resolver not found: " + id);
        }
        jdbcTemplate.update("delete from video_resolver_sources where id = :id", new MapSqlParameterSource("id", id));
    }

    private void replaceEpisodes(UUID playlistId, UUID dataSourceId, List<EpisodeRequest> episodes) {
        jdbcTemplate.update("delete from episodes where playlist_id = :playlistId",
                new MapSqlParameterSource("playlistId", playlistId));
        if (episodes == null) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        for (EpisodeRequest episode : episodes) {
            if (!StringUtils.hasText(episode.name()) || !StringUtils.hasText(episode.url())) {
                throw new ContentApiException(HttpStatus.BAD_REQUEST, "Episode name and url are required");
            }
            EpisodeUrlBinding binding = bindEpisodeUrl(episode.url().trim(), dataSourceId);
            jdbcTemplate.update(
                    """
                    insert into episodes (id, created_at, updated_at, version, name, url, playlist_id, source_domain_id)
                    values (:id, :now, :now, 0, :name, :url, :playlistId, :sourceDomainId)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", IrcsUuidGenerators.nextId())
                            .addValue("now", now)
                            .addValue("name", trimToLength(episode.name(), 255))
                            .addValue("url", trimToLength(binding.relativePath(), 2048))
                            .addValue("playlistId", playlistId)
                            .addValue("sourceDomainId", binding.sourceDomainId()));
        }
    }

    private EpisodeUrlBinding bindEpisodeUrl(String url, UUID dataSourceId) {
        if (!url.startsWith("http")) {
            return new EpisodeUrlBinding(null, url);
        }
        try {
            URI uri = URI.create(url);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return new EpisodeUrlBinding(null, url);
            }
            String domain = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            UUID sourceDomainId = getOrCreateSourceDomain(domain, dataSourceId);
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
            return new EpisodeUrlBinding(sourceDomainId, relative.isEmpty() ? "/" : relative.toString());
        } catch (IllegalArgumentException ignored) {
            return new EpisodeUrlBinding(null, url);
        }
    }

    private UUID getOrCreateSourceDomain(String domain, UUID dataSourceId) {
        String hash = sha256(domain);
        Optional<UUID> existing = findSourceDomainIdByHash(hash);
        if (existing.isPresent()) {
            if (dataSourceId != null) {
                jdbcTemplate.update(
                        "update source_domains set data_source_id = coalesce(data_source_id, :dataSourceId) where id = :id",
                        new MapSqlParameterSource()
                                .addValue("id", existing.get())
                                .addValue("dataSourceId", dataSourceId));
            }
            return existing.get();
        }
        UUID id = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                insert into source_domains (id, created_at, updated_at, version, domain_hash, domain_value, remark, data_source_id)
                values (:id, :now, :now, 0, :hash, :domain, 'Auto-discovered', :dataSourceId)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", now)
                        .addValue("hash", hash)
                        .addValue("domain", domain)
                        .addValue("dataSourceId", dataSourceId));
        return id;
    }

    private Optional<UUID> findSourceDomainIdByHash(String hash) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "select id from source_domains where domain_hash = :hash",
                    new MapSqlParameterSource("hash", hash),
                    UUID.class));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private List<EpisodeResponse> findEpisodes(UUID playlistId) {
        return jdbcTemplate.query(
                """
                select e.id, e.name, e.url, sd.domain_value
                from episodes e
                left join source_domains sd on e.source_domain_id = sd.id
                where e.playlist_id = :playlistId
                order by e.created_at asc, e.id asc
                """,
                new MapSqlParameterSource("playlistId", playlistId),
                (rs, rowNum) -> new EpisodeResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        resolveEpisodeUrl(rs.getString("domain_value"), rs.getString("url"))));
    }

    private String resolveEpisodeUrl(String domain, String path) {
        if (!StringUtils.hasText(domain)) {
            return path;
        }
        if (!StringUtils.hasText(path)) {
            return domain;
        }
        boolean domainHasSlash = domain.endsWith("/");
        boolean pathHasSlash = path.startsWith("/");
        if (domainHasSlash && pathHasSlash) {
            return domain + path.substring(1);
        }
        if (!domainHasSlash && !pathHasSlash) {
            return domain + "/" + path;
        }
        return domain + path;
    }

    private boolean playlistExists(UUID id) {
        return exists("playlists", id);
    }

    private boolean resolverExists(UUID id) {
        return exists("video_resolver_sources", id);
    }

    private boolean exists(String table, UUID id) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from " + table + " where id = :id)",
                new MapSqlParameterSource("id", id),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private void assertRawVideoExists(UUID id) {
        if (id == null || !exists("raw_videos", id)) {
            throw notFound("Video not found: " + id);
        }
    }

    private UUID playlistVideoId(UUID id) {
        return jdbcTemplate.queryForObject(
                "select video_id from playlists where id = :id",
                new MapSqlParameterSource("id", id),
                UUID.class);
    }

    private Optional<UUID> resolveDataSourceId(UUID rawVideoId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select rv.data_source_id as data_source_id
                    from raw_videos rv
                    where rv.id = :id
                    """,
                    new MapSqlParameterSource("id", rawVideoId),
                    UUID.class));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private PlaylistDetailResponse requirePlaylist(UUID id) {
        return findPlaylist(id).orElseThrow(() -> notFound("Playlist not found: " + id));
    }

    private ResolverResponse requireResolver(UUID id) {
        return findResolver(id).orElseThrow(() -> notFound("Resolver not found: " + id));
    }

    private String playlistWhere(MapSqlParameterSource params, String name, String videoTitle) {
        List<String> where = new ArrayList<>();
        if (StringUtils.hasText(name)) {
            params.addValue("name", "%" + name.trim().toLowerCase() + "%");
            where.add("lower(p.name) like :name");
        }
        if (StringUtils.hasText(videoTitle)) {
            params.addValue("videoTitle", "%" + videoTitle.trim().toLowerCase() + "%");
            where.add("lower(rv.title) like :videoTitle");
        }
        return where.isEmpty() ? "" : " where " + String.join(" and ", where);
    }

    private String sourceDomainWhere(MapSqlParameterSource params, String keyword, UUID dataSourceId) {
        List<String> where = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            params.addValue("keyword", "%" + keyword.trim().toLowerCase() + "%");
            where.add("(lower(sd.domain_value) like :keyword or lower(coalesce(sd.remark, '')) like :keyword)");
        }
        if (dataSourceId != null) {
            params.addValue("dataSourceId", dataSourceId);
            where.add("sd.data_source_id = :dataSourceId");
        }
        return where.isEmpty() ? "" : " where " + String.join(" and ", where);
    }

    private Pageable sanitize(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, MAX_PAGE_SIZE);
        }
        int page = Math.max(0, pageable.getPageNumber());
        int size = Math.min(Math.max(1, pageable.getPageSize()), MAX_PAGE_SIZE);
        return PageRequest.of(page, size, pageable.getSort());
    }

    private MapSqlParameterSource pagingParams(Pageable pageable) {
        return new MapSqlParameterSource()
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
    }

    private String orderBy(Pageable pageable, Map<String, String> columns, String defaultOrder) {
        if (pageable.getSort().isUnsorted()) {
            return " order by " + defaultOrder;
        }
        List<String> orders = new ArrayList<>();
        pageable.getSort().forEach(order -> {
            String column = columns.get(order.getProperty());
            if (column != null) {
                orders.add(column + " " + order.getDirection().name());
            }
        });
        return orders.isEmpty() ? " order by " + defaultOrder : " order by " + String.join(", ", orders);
    }

    private PlaylistBase mapPlaylistBase(ResultSet rs, int rowNum) throws SQLException {
        return new PlaylistBase(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("video_id", UUID.class),
                rs.getString("video_title"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private SourceDomainResponse mapSourceDomain(ResultSet rs, int rowNum) throws SQLException {
        return new SourceDomainResponse(
                rs.getObject("id", UUID.class),
                rs.getString("domain_hash"),
                rs.getString("domain_value"),
                rs.getString("remark"),
                rs.getObject("data_source_id", UUID.class),
                rs.getString("data_source_name"),
                rs.getBoolean("adult_restricted"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private ResolverResponse mapResolver(ResultSet rs, int rowNum) throws SQLException {
        boolean active = rs.getBoolean("is_active");
        return new ResolverResponse(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                active,
                active,
                rs.getString("remark"),
                readJson(rs.getString("lines")));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            throw new ContentApiException(HttpStatus.BAD_REQUEST, "Invalid resolver lines");
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(StringUtils.hasText(json) ? json : "[]");
        } catch (JsonProcessingException ex) {
            return objectMapper.createArrayNode();
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private ContentApiException notFound(String message) {
        return new ContentApiException(HttpStatus.NOT_FOUND, message);
    }

    private record PlaylistBase(
            UUID id,
            String name,
            UUID videoId,
            String videoTitle,
            Instant createdAt,
            Instant updatedAt) {
    }

    private record PlaylistBaseCard(
            UUID id,
            String name,
            UUID videoId) {
    }

    private record EpisodeUrlBinding(
            UUID sourceDomainId,
            String relativePath) {
    }
}
