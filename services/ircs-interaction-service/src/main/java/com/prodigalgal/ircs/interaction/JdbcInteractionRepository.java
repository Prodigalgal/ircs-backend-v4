package com.prodigalgal.ircs.interaction;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcInteractionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final CoverImageUrlResolver coverImageUrlResolver;

    public PageResponse<UserMessageResponse> findPublicMessages(PageBounds bounds) {
        long total = count("select count(*) from user_messages where is_public = true");
        List<UserMessageResponse> content = jdbcTemplate.query(
                """
                select um.id,
                       um.member_id,
                       coalesce(nullif(m.nickname, ''), '画外用户') as member_nickname,
                       m.avatar_url as member_avatar_url,
                       um.content,
                       um.reply,
                       um.status,
                       um.is_public,
                       um.created_at,
                       um.updated_at
                from user_messages um
                join members m on m.id = um.member_id
                where um.is_public = true
                order by um.created_at desc, um.id asc
                limit ? offset ?
                """,
                userMessageMapper(),
                bounds.size(),
                bounds.offset());
        return PageResponse.of(content, total, bounds);
    }

    public PageResponse<InteractionRecordResponse> findHistory(UUID memberId, PageBounds bounds) {
        long total = count("select count(*) from member_watch_histories where member_id = ?", memberId);
        List<InteractionRecordResponse> content = jdbcTemplate.query(
                """
                select mwh.id,
                       mwh.unified_video_id,
                       uv.title,
                       uv.score,
                       ci.storage_type as cover_storage_type,
                       ci.original_url as cover_original_url,
                       ci.storage_path as cover_storage_path,
                       sd.domain_value as cover_source_domain,
                       mwh.last_video_id,
                       mwh.last_episode_id,
                       mwh.episode_name,
                       mwh.progress_seconds,
                       mwh.duration_seconds,
                       mwh.last_watched_at,
                       exists (
                           select 1
                           from member_favorites mf
                           where mf.member_id = mwh.member_id
                             and mf.unified_video_id = mwh.unified_video_id
                       ) as is_favorite
                from member_watch_histories mwh
                join unified_videos uv on uv.id = mwh.unified_video_id
                left join cover_images ci on ci.id = uv.cover_image_id
                left join source_domains sd on sd.id = ci.source_domain_id
                where mwh.member_id = ?
                order by mwh.last_watched_at desc, mwh.id asc
                limit ? offset ?
                """,
                interactionRecordMapper(),
                memberId,
                bounds.size(),
                bounds.offset());
        return PageResponse.of(content, total, bounds);
    }

    public PageResponse<InteractionRecordResponse> findFavorites(UUID memberId, PageBounds bounds) {
        long total = count("select count(*) from member_favorites where member_id = ?", memberId);
        List<InteractionRecordResponse> content = jdbcTemplate.query(
                """
                select mf.id,
                       mf.unified_video_id,
                       uv.title,
                       uv.score,
                       ci.storage_type as cover_storage_type,
                       ci.original_url as cover_original_url,
                       ci.storage_path as cover_storage_path,
                       sd.domain_value as cover_source_domain,
                       cast(null as uuid) as last_video_id,
                       cast(null as uuid) as last_episode_id,
                       cast(null as text) as episode_name,
                       0 as progress_seconds,
                       0 as duration_seconds,
                       mf.created_at as last_watched_at,
                       true as is_favorite
                from member_favorites mf
                join unified_videos uv on uv.id = mf.unified_video_id
                left join cover_images ci on ci.id = uv.cover_image_id
                left join source_domains sd on sd.id = ci.source_domain_id
                where mf.member_id = ?
                order by mf.created_at desc, mf.id asc
                limit ? offset ?
                """,
                interactionRecordMapper(),
                memberId,
                bounds.size(),
                bounds.offset());
        return PageResponse.of(content, total, bounds);
    }

    public Optional<String> findMemberStatus(UUID memberId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "select status from members where id = ?",
                    String.class,
                    memberId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public boolean unifiedVideoExists(UUID unifiedVideoId) {
        return count("select count(*) from unified_videos where id = ?", unifiedVideoId) > 0;
    }

    public boolean favoriteExists(UUID memberId, UUID unifiedVideoId) {
        return count(
                "select count(*) from member_favorites where member_id = ? and unified_video_id = ?",
                memberId,
                unifiedVideoId) > 0;
    }

    public void insertFavorite(UUID id, UUID memberId, UUID unifiedVideoId, Instant now) {
        jdbcTemplate.update(
                """
                insert into member_favorites (
                    id, member_id, unified_video_id, created_at, updated_at, version
                ) values (?, ?, ?, ?, ?, 0)
                """,
                id,
                memberId,
                unifiedVideoId,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public int deleteFavorite(UUID memberId, UUID unifiedVideoId) {
        return jdbcTemplate.update(
                "delete from member_favorites where member_id = ? and unified_video_id = ?",
                memberId,
                unifiedVideoId);
    }

    public void upsertWatchHistory(
            UUID id,
            UUID memberId,
            UUID unifiedVideoId,
            UUID videoId,
            UUID episodeId,
            String episodeName,
            int progress,
            int duration,
            Instant now) {
        jdbcTemplate.update(
                """
                insert into member_watch_histories (
                    id,
                    member_id,
                    unified_video_id,
                    last_video_id,
                    last_episode_id,
                    episode_name,
                    progress_seconds,
                    duration_seconds,
                    last_watched_at,
                    created_at,
                    updated_at,
                    version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                on conflict (member_id, unified_video_id)
                do update set
                    last_video_id = excluded.last_video_id,
                    last_episode_id = excluded.last_episode_id,
                    episode_name = excluded.episode_name,
                    progress_seconds = excluded.progress_seconds,
                    duration_seconds = excluded.duration_seconds,
                    last_watched_at = excluded.last_watched_at,
                    updated_at = excluded.updated_at,
                    version = coalesce(member_watch_histories.version, 0) + 1
                """,
                id,
                memberId,
                unifiedVideoId,
                videoId,
                episodeId,
                episodeName,
                progress,
                duration,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public void upsertWatchHistoryIfNewer(
            UUID id,
            UUID memberId,
            UUID unifiedVideoId,
            UUID videoId,
            UUID episodeId,
            String episodeName,
            int progress,
            int duration,
            Instant now) {
        jdbcTemplate.update(
                """
                insert into member_watch_histories (
                    id,
                    member_id,
                    unified_video_id,
                    last_video_id,
                    last_episode_id,
                    episode_name,
                    progress_seconds,
                    duration_seconds,
                    last_watched_at,
                    created_at,
                    updated_at,
                    version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                on conflict (member_id, unified_video_id)
                do update set
                    last_video_id = excluded.last_video_id,
                    last_episode_id = excluded.last_episode_id,
                    episode_name = excluded.episode_name,
                    progress_seconds = excluded.progress_seconds,
                    duration_seconds = excluded.duration_seconds,
                    last_watched_at = excluded.last_watched_at,
                    updated_at = excluded.updated_at,
                    version = coalesce(member_watch_histories.version, 0) + 1
                where excluded.last_watched_at >= member_watch_histories.last_watched_at
                """,
                id,
                memberId,
                unifiedVideoId,
                videoId,
                episodeId,
                episodeName,
                progress,
                duration,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public int deleteHistory(UUID memberId) {
        return jdbcTemplate.update("delete from member_watch_histories where member_id = ?", memberId);
    }

    public int deleteHistoryRecord(UUID memberId, UUID historyId) {
        return jdbcTemplate.update(
                "delete from member_watch_histories where member_id = ? and id = ?",
                memberId,
                historyId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private RowMapper<UserMessageResponse> userMessageMapper() {
        return (rs, rowNum) -> new UserMessageResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("member_id", UUID.class),
                rs.getString("member_nickname"),
                null,
                rs.getString("member_avatar_url"),
                rs.getString("content"),
                rs.getString("reply"),
                rs.getString("status"),
                rs.getBoolean("is_public"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private RowMapper<InteractionRecordResponse> interactionRecordMapper() {
        return (rs, rowNum) -> new InteractionRecordResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("unified_video_id", UUID.class),
                rs.getString("title"),
                coverImageUrl(rs),
                rs.getObject("score", BigDecimal.class),
                getUuid(rs, "last_video_id"),
                getUuid(rs, "last_episode_id"),
                rs.getString("episode_name"),
                getInt(rs, "progress_seconds"),
                getInt(rs, "duration_seconds"),
                toInstant(rs.getTimestamp("last_watched_at")),
                rs.getBoolean("is_favorite"));
    }

    private String coverImageUrl(ResultSet rs) throws SQLException {
        return coverImageUrlResolver.resolve(
                rs.getString("cover_storage_type"),
                rs.getString("cover_original_url"),
                rs.getString("cover_storage_path"),
                rs.getString("cover_source_domain"));
    }

    private UUID getUuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private int getInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? 0 : value;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
