package com.prodigalgal.ircs.ingestion;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import com.prodigalgal.ircs.contracts.ingestion.IngestionEpisodeDTO;
import com.prodigalgal.ircs.contracts.ingestion.IngestionPlaylistDTO;
import com.prodigalgal.ircs.contracts.ingestion.IngestionVideoDTO;
import com.prodigalgal.ircs.contracts.ingestion.PlaylistSyncMessage;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class RawVideoIngestionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<RawVideoState> findStateBySourceHash(String sourceHash) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id, data_hash
                    from raw_videos
                    where source_hash = :sourceHash
                    """,
                    new MapSqlParameterSource("sourceHash", sourceHash),
                    (rs, rowNum) -> new RawVideoState(
                            rs.getObject("id", UUID.class),
                            rs.getString("data_hash"))));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<String> findDataHashById(UUID rawVideoId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "select data_hash from raw_videos where id = :id",
                    new MapSqlParameterSource("id", rawVideoId),
                    String.class));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public UUID upsertRawVideo(IngestionVideoDTO video, UUID existingId) {
        UUID id = existingId == null ? IrcsUuidGenerators.nextId() : existingId;
        UUID coverImageId = getOrCreateCoverImage(video.coverImageUrl(), video.dataSourceId());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("sourceVid", trimToLength(video.sourceVid(), 255))
                .addValue("sourceHash", trimToLength(video.sourceHash(), 64))
                .addValue("dataHash", trimToLength(video.dataHash(), 64))
                .addValue("title", trimToLength(video.title(), 255))
                .addValue("aliasTitle", trimToLength(video.aliasTitle(), 255))
                .addValue("description", video.description())
                .addValue("coverImageId", coverImageId)
                .addValue("year", trimToLength(video.year(), 20))
                .addValue("area", trimToLength(video.area(), 255))
                .addValue("language", trimToLength(video.language(), 255))
                .addValue("remarks", trimToLength(video.remarks(), 255))
                .addValue("score", video.score())
                .addValue("publishedAt", video.publishedAt() == null ? null : Date.valueOf(video.publishedAt()))
                .addValue("totalEpisodes", trimToLength(video.totalEpisodes(), 50))
                .addValue("duration", trimToLength(video.duration(), 50))
                .addValue("doubanId", trimToLength(video.doubanId(), 20))
                .addValue("tmdbId", trimToLength(video.tmdbId(), 20))
                .addValue("imdbId", trimToLength(video.imdbId(), 20))
                .addValue("rottenTomatoesId", trimToLength(video.rottenTomatoesId(), 50))
                .addValue("rawMetadata", rawMetadata(video))
                .addValue("dataSourceId", video.dataSourceId());

        return jdbcTemplate.queryForObject(
                """
                insert into raw_videos (
                    id, created_at, updated_at, version,
                    source_vid, source_hash, data_hash, title, alias_title,
                    description, cover_image_id, year, area, raw_language_str, remarks, score, published_at,
                    total_episodes, duration,
                    douban_id, tmdb_id, imdb_id, rotten_tomatoes_id,
                    locked_fields, enrichment_status, enrichment_retry_count,
                    normalization_status, normalization_retry_count,
                    aggregation_status, raw_metadata, data_source_id
                ) values (
                    :id, now(), now(), 0,
                    :sourceVid, :sourceHash, :dataHash, :title, :aliasTitle,
                    :description, :coverImageId, :year, :area, :language, :remarks, :score, :publishedAt,
                    :totalEpisodes, :duration,
                    :doubanId, :tmdbId, :imdbId, :rottenTomatoesId,
                    '[]'::jsonb, 'PENDING', 0,
                    'PENDING', 0,
                    'PENDING', cast(:rawMetadata as jsonb), :dataSourceId
                )
                on conflict (source_hash) do update set
                    source_vid = excluded.source_vid,
                    data_hash = excluded.data_hash,
                    title = excluded.title,
                    alias_title = excluded.alias_title,
                    description = excluded.description,
                    cover_image_id = coalesce(excluded.cover_image_id, raw_videos.cover_image_id),
                    year = excluded.year,
                    area = excluded.area,
                    raw_language_str = excluded.raw_language_str,
                    remarks = excluded.remarks,
                    score = excluded.score,
                    published_at = excluded.published_at,
                    total_episodes = excluded.total_episodes,
                    duration = excluded.duration,
                    douban_id = excluded.douban_id,
                    tmdb_id = excluded.tmdb_id,
                    imdb_id = excluded.imdb_id,
                    rotten_tomatoes_id = excluded.rotten_tomatoes_id,
                    normalization_status = 'PENDING',
                    enrichment_status = 'PENDING',
                    aggregation_status = 'PENDING',
                    raw_metadata = excluded.raw_metadata,
                    data_source_id = excluded.data_source_id,
                    updated_at = now()
                returning id
                """,
                params,
                UUID.class);
    }

    private UUID getOrCreateCoverImage(String coverImageUrl, UUID dataSourceId) {
        if (!StringUtils.hasText(coverImageUrl)) {
            return null;
        }
        CoverImageReference reference = resolveCoverImageReference(coverImageUrl);
        UUID sourceDomainId = upsertSourceDomain(reference.domainValue(), dataSourceId, "ingestion-cover");
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
                        .addValue("originalUrl", trimToLength(reference.originalUrl(), 2048))
                        .addValue("sourceDomainId", sourceDomainId),
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
        return imageId;
    }

    private CoverImageReference resolveCoverImageReference(String coverImageUrl) {
        String trimmed = coverImageUrl.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("http")) {
            try {
                URI uri = URI.create(trimmed);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (StringUtils.hasText(scheme) && StringUtils.hasText(host)) {
                    String domainValue = scheme + "://" + host + (uri.getPort() > -1 ? ":" + uri.getPort() : "");
                    String path = StringUtils.hasText(uri.getRawPath()) ? uri.getRawPath() : "/";
                    String relativeUrl = path
                            + (StringUtils.hasText(uri.getRawQuery()) ? "?" + uri.getRawQuery() : "")
                            + (StringUtils.hasText(uri.getRawFragment()) ? "#" + uri.getRawFragment() : "");
                    return new CoverImageReference(domainValue, relativeUrl);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new CoverImageReference("EXTERNAL_COVER", trimmed);
    }

    private UUID upsertSourceDomain(String domainValue, UUID dataSourceId, String remark) {
        return jdbcTemplate.queryForObject(
                """
                insert into source_domains (id, created_at, updated_at, version, domain_hash, domain_value, remark, data_source_id)
                values (:id, now(), now(), 0, :domainHash, :domainValue, :remark, :dataSourceId)
                on conflict (domain_hash) do update set
                    domain_value = excluded.domain_value,
                    data_source_id = coalesce(source_domains.data_source_id, excluded.data_source_id),
                    updated_at = now()
                returning id
                """,
                new MapSqlParameterSource()
                        .addValue("id", IrcsUuidGenerators.nextId())
                        .addValue("domainHash", sha256(domainValue))
                        .addValue("domainValue", trimToLength(domainValue, 255))
                        .addValue("remark", remark)
                        .addValue("dataSourceId", dataSourceId),
                UUID.class);
    }

    public int replacePlaylists(PlaylistSyncMessage message) {
        List<IngestionPlaylistDTO> playlists = message.playlists() == null ? List.of() : message.playlists();
        List<UUID> playlistIds = jdbcTemplate.queryForList(
                "select id from playlists where video_id = :videoId",
                new MapSqlParameterSource("videoId", message.videoId()),
                UUID.class);

        if (!playlistIds.isEmpty()) {
            jdbcTemplate.update(
                    "delete from episodes where playlist_id in (:playlistIds)",
                    new MapSqlParameterSource("playlistIds", playlistIds));
        }
        jdbcTemplate.update(
                "delete from playlists where video_id = :videoId",
                new MapSqlParameterSource("videoId", message.videoId()));

        int inserted = 0;
        for (IngestionPlaylistDTO playlist : playlists) {
            UUID playlistId = IrcsUuidGenerators.nextId();
            String playlistName = StringUtils.hasText(playlist.name()) ? playlist.name().trim() : "默认";
            jdbcTemplate.update(
                    """
                    insert into playlists (id, created_at, updated_at, version, name, video_id)
                    values (:id, now(), now(), 0, :name, :videoId)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", playlistId)
                            .addValue("name", trimToLength(playlistName, 255))
                            .addValue("videoId", message.videoId()));

            List<IngestionEpisodeDTO> episodes = playlist.episodes() == null ? List.of() : playlist.episodes();
            for (IngestionEpisodeDTO episode : episodes) {
                EpisodeLocation location = resolveEpisodeLocation(episode, message);
                jdbcTemplate.update(
                        """
                        insert into episodes (id, created_at, updated_at, version, name, url, playlist_id, source_domain_id)
                        values (:id, now(), now(), 0, :name, :url, :playlistId, :sourceDomainId)
                        """,
                        new MapSqlParameterSource()
                                .addValue("id", IrcsUuidGenerators.nextId())
                                .addValue("name", trimToLength(defaultEpisodeName(episode), 255))
                                .addValue("url", trimToLength(location.url(), 255))
                                .addValue("playlistId", playlistId)
                                .addValue("sourceDomainId", location.sourceDomainId()));
            }
            inserted++;
        }
        return inserted;
    }

    private EpisodeLocation resolveEpisodeLocation(IngestionEpisodeDTO episode, PlaylistSyncMessage message) {
        if (episode.sourceDomainId() != null) {
            return new EpisodeLocation(episode.url(), episode.sourceDomainId());
        }
        if (!StringUtils.hasText(episode.url())) {
            return new EpisodeLocation("", null);
        }

        try {
            URI uri = URI.create(episode.url().trim());
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return new EpisodeLocation(episode.url().trim(), null);
            }
            String domainValue = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > -1 ? ":" + uri.getPort() : "");
            String path = StringUtils.hasText(uri.getRawPath()) ? uri.getRawPath() : "/";
            String relativePath = path + (StringUtils.hasText(uri.getRawQuery()) ? "?" + uri.getRawQuery() : "");
            UUID sourceDomainId = upsertSourceDomain(domainValue, message);
            return new EpisodeLocation(relativePath, sourceDomainId);
        } catch (IllegalArgumentException ignored) {
            return new EpisodeLocation(episode.url().trim(), null);
        }
    }

    private UUID upsertSourceDomain(String domainValue, PlaylistSyncMessage message) {
        return jdbcTemplate.queryForObject(
                """
                insert into source_domains (id, created_at, updated_at, version, domain_hash, domain_value, remark, data_source_id)
                values (:id, now(), now(), 0, :domainHash, :domainValue, 'ingestion-worker', (
                    select data_source_id from raw_videos where id = :videoId
                ))
                on conflict (domain_hash) do update set
                    domain_value = excluded.domain_value,
                    updated_at = now()
                returning id
                """,
                new MapSqlParameterSource()
                        .addValue("id", IrcsUuidGenerators.nextId())
                        .addValue("domainHash", sha256(domainValue))
                        .addValue("domainValue", trimToLength(domainValue, 255))
                        .addValue("videoId", message.videoId()),
                UUID.class);
    }

    private String defaultEpisodeName(IngestionEpisodeDTO episode) {
        return StringUtils.hasText(episode.name()) ? episode.name().trim() : "默认";
    }

    private String rawMetadata(IngestionVideoDTO video) {
        return StringUtils.hasText(video.rawMetadata()) ? video.rawMetadata() : "{}";
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record EpisodeLocation(String url, UUID sourceDomainId) {
    }

    private record CoverImageReference(String domainValue, String originalUrl) {
    }
}
