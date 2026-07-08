package com.prodigalgal.ircs.interaction;

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
public class JdbcMediaRequestRepository {

    private final JdbcTemplate jdbcTemplate;

    public MediaRequestResponse upsert(UUID id, UUID memberId, String title, String normalizedTitle,
            Integer releaseYear, String extraInfo, Instant now, int spentPoints) {
        int storedReleaseYear = releaseYear == null ? 0 : releaseYear;
        return jdbcTemplate.queryForObject(
                """
                with upserted as (
                    insert into portal_media_requests (
                        id, created_at, updated_at, version, member_id, title, normalized_title, release_year,
                        extra_info, status, request_count, last_requested_at
                    ) values (?, ?, ?, 0, ?, ?, ?, ?, ?, 'PENDING', 1, ?)
                    on conflict (normalized_title, release_year) do update
                       set updated_at = excluded.updated_at,
                           version = portal_media_requests.version + 1,
                           title = excluded.title,
                           extra_info = coalesce(nullif(excluded.extra_info, ''), portal_media_requests.extra_info),
                           status = case
                               when portal_media_requests.status in ('COMPLETED', 'FAILED', 'CANCELLED') then 'PENDING'
                               else portal_media_requests.status
                           end,
                           request_count = portal_media_requests.request_count + 1,
                           last_requested_at = excluded.last_requested_at,
                           existing_video_id = case
                               when portal_media_requests.status = 'SKIPPED_EXISTS' then portal_media_requests.existing_video_id
                               else null
                           end,
                           existing_video_source = case
                               when portal_media_requests.status = 'SKIPPED_EXISTS' then portal_media_requests.existing_video_source
                               else null
                           end,
                           last_error_message = case
                               when portal_media_requests.status = 'SKIPPED_EXISTS' then portal_media_requests.last_error_message
                               else null
                           end
                    returning id, member_id, title, release_year, extra_info, status, request_count, created_at,
                              updated_at, last_requested_at, scheduled_at, completed_at, last_error_message,
                              existing_video_id, existing_video_source
                )
                select *, ? as spent_points
                from upserted
                """,
                mediaRequestMapper(),
                id,
                Timestamp.from(now),
                Timestamp.from(now),
                memberId,
                title,
                normalizedTitle,
                storedReleaseYear,
                extraInfo,
                Timestamp.from(now),
                spentPoints);
    }

    public PageResponse<MediaRequestResponse> findMemberRequests(UUID memberId, PageBounds bounds) {
        long total = count("select count(*) from portal_media_requests where member_id = ?", memberId);
        List<MediaRequestResponse> content = jdbcTemplate.query(
                """
                select id, member_id, title, release_year, extra_info, status, request_count, created_at,
                       updated_at, last_requested_at, scheduled_at, completed_at, last_error_message,
                       existing_video_id, existing_video_source,
                       0 as spent_points
                  from portal_media_requests
                 where member_id = ?
                 order by last_requested_at desc, id asc
                 limit ? offset ?
                """,
                mediaRequestMapper(),
                memberId,
                bounds.size(),
                bounds.offset());
        return PageResponse.of(content, total, bounds);
    }

    public long countMemberRequestsCreatedBetween(UUID memberId, Instant startInclusive, Instant endExclusive) {
        return count(
                "select count(*) from portal_media_requests where member_id = ? and last_requested_at >= ? and last_requested_at < ?",
                memberId,
                Timestamp.from(startInclusive),
                Timestamp.from(endExclusive));
    }

    public Optional<MediaRequestResponse> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id, member_id, title, release_year, extra_info, status, request_count, created_at,
                           updated_at, last_requested_at, scheduled_at, completed_at, last_error_message,
                           existing_video_id, existing_video_source,
                           0 as spent_points
                      from portal_media_requests
                     where id = ?
                    """,
                    mediaRequestMapper(),
                    id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private RowMapper<MediaRequestResponse> mediaRequestMapper() {
        return (rs, rowNum) -> new MediaRequestResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("member_id", UUID.class),
                rs.getString("title"),
                apiReleaseYear(rs.getInt("release_year")),
                rs.getString("extra_info"),
                rs.getString("status"),
                rs.getInt("request_count"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("last_requested_at")),
                instant(rs.getTimestamp("scheduled_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getString("last_error_message"),
                rs.getObject("existing_video_id", UUID.class),
                rs.getString("existing_video_source"),
                rs.getInt("spent_points"));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Integer apiReleaseYear(int releaseYear) {
        return releaseYear <= 0 ? null : releaseYear;
    }
}
