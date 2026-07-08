package com.prodigalgal.ircs.storage.image;

import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AvatarSyncMemberRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<String> findAvatarUrl(UUID memberId) {
        return jdbcTemplate.query(
                "select avatar_url from members where id = ?",
                ps -> ps.setObject(1, memberId),
                rs -> rs.next() ? Optional.ofNullable(rs.getString("avatar_url")) : Optional.empty());
    }

    public int updateAvatarUrl(UUID memberId, String expectedCurrentUrl, String nextUrl) {
        return jdbcTemplate.update(
                """
                update members
                   set avatar_url = ?,
                       updated_at = now(),
                       version = coalesce(version, 0) + 1
                 where id = ?
                   and avatar_url = ?
                """,
                new Object[] {nextUrl, memberId, expectedCurrentUrl},
                new int[] {Types.VARCHAR, Types.OTHER, Types.VARCHAR});
    }

}
