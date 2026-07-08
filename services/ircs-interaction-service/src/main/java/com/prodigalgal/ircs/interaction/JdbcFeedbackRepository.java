package com.prodigalgal.ircs.interaction;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcFeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserMessageResponse insertMessage(UUID id, UUID memberId, String content, Instant now) {
        return jdbcTemplate.queryForObject(
                """
                with inserted as (
                    insert into user_messages (
                        id,
                        created_at,
                        updated_at,
                        version,
                        member_id,
                        content,
                        status,
                        is_public
                    ) values (?, ?, ?, 0, ?, ?, 'PENDING', false)
                    returning id, member_id, content, reply, status, is_public, created_at, updated_at
                )
                select inserted.id,
                       inserted.member_id,
                       coalesce(nullif(m.nickname, ''), '画外用户') as member_nickname,
                       m.email as member_email,
                       m.avatar_url as member_avatar_url,
                       inserted.content,
                       inserted.reply,
                       inserted.status,
                       inserted.is_public,
                       inserted.created_at,
                       inserted.updated_at
                from inserted
                join members m on m.id = inserted.member_id
                """,
                userMessageMapper(),
                id,
                Timestamp.from(now),
                Timestamp.from(now),
                memberId,
                content);
    }

    public PageResponse<UserMessageResponse> findMemberMessages(UUID memberId, PageBounds bounds) {
        long total = count("select count(*) from user_messages where member_id = ?", memberId);
        List<UserMessageResponse> content = jdbcTemplate.query(
                """
                select um.id,
                       um.member_id,
                       coalesce(nullif(m.nickname, ''), '画外用户') as member_nickname,
                       m.email as member_email,
                       m.avatar_url as member_avatar_url,
                       um.content,
                       um.reply,
                       um.status,
                       um.is_public,
                       um.created_at,
                       um.updated_at
                from user_messages um
                join members m on m.id = um.member_id
                where um.member_id = ?
                order by um.created_at desc, um.id asc
                limit ? offset ?
                """,
                userMessageMapper(),
                memberId,
                bounds.size(),
                bounds.offset());
        return PageResponse.of(content, total, bounds);
    }

    public long countMemberMessagesCreatedBetween(UUID memberId, Instant startInclusive, Instant endExclusive) {
        return count(
                "select count(*) from user_messages where member_id = ? and created_at >= ? and created_at < ?",
                memberId,
                Timestamp.from(startInclusive),
                Timestamp.from(endExclusive));
    }

    public int deleteMemberMessage(UUID memberId, UUID messageId) {
        return jdbcTemplate.update(
                "delete from user_messages where member_id = ? and id = ?",
                memberId,
                messageId);
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
                rs.getString("member_email"),
                rs.getString("member_avatar_url"),
                rs.getString("content"),
                rs.getString("reply"),
                rs.getString("status"),
                rs.getBoolean("is_public"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
