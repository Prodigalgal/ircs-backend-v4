package com.prodigalgal.ircs.interaction;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberPointLedger {

    private final JdbcTemplate jdbcTemplate;

    public void spend(UUID memberId, int points, String reason) {
        if (points <= 0) {
            return;
        }
        int updated = jdbcTemplate.update(
                """
                update members
                   set points = points - ?,
                       updated_at = now(),
                       version = coalesce(version, 0) + 1
                 where id = ?
                   and points >= ?
                """,
                points,
                memberId,
                points);
        if (updated == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "积分不足，请先签到获取积分");
        }
    }
}
