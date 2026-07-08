package com.prodigalgal.ircs.identity.infrastructure;



import com.prodigalgal.ircs.identity.repository.MemberOAuthAccountRepository;
import com.prodigalgal.ircs.identity.domain.MemberOAuthAccountRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcMemberOAuthAccountRepository implements MemberOAuthAccountRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<MemberOAuthAccountRecord> findByProviderAndSubject(String provider, String subject) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectSql() + " where provider = ? and provider_user_id = ?",
                    mapper(),
                    provider,
                    subject));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public MemberOAuthAccountRecord insert(MemberOAuthAccountRecord account) {
        jdbcTemplate.update(
                """
                insert into member_oauth_accounts (
                    id, created_at, updated_at, version, member_id, provider, provider_user_id,
                    provider_email, provider_email_verified, provider_nickname, provider_avatar_url,
                    access_token_hash, last_login_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                account.id(),
                timestamp(account.createdAt()),
                timestamp(account.updatedAt()),
                account.version(),
                account.memberId(),
                account.provider(),
                account.providerUserId(),
                account.providerEmail(),
                account.providerEmailVerified(),
                account.providerNickname(),
                account.providerAvatarUrl(),
                account.accessTokenHash(),
                timestamp(account.lastLoginAt()));
        return account;
    }

    @Override
    public MemberOAuthAccountRecord update(MemberOAuthAccountRecord account) {
        jdbcTemplate.update(
                """
                update member_oauth_accounts
                   set updated_at = ?,
                       version = coalesce(version, 0) + 1,
                       provider_email = ?,
                       provider_email_verified = ?,
                       provider_nickname = ?,
                       provider_avatar_url = ?,
                       access_token_hash = ?,
                       last_login_at = ?
                 where id = ?
                """,
                timestamp(account.updatedAt()),
                account.providerEmail(),
                account.providerEmailVerified(),
                account.providerNickname(),
                account.providerAvatarUrl(),
                account.accessTokenHash(),
                timestamp(account.lastLoginAt()),
                account.id());
        return account;
    }

    private String selectSql() {
        return """
        select id, created_at, updated_at, coalesce(version, 0) as version, member_id, provider, provider_user_id,
               provider_email, provider_email_verified, provider_nickname, provider_avatar_url,
               access_token_hash, last_login_at
          from member_oauth_accounts
        """;
    }

    private RowMapper<MemberOAuthAccountRecord> mapper() {
        return (rs, rowNum) -> new MemberOAuthAccountRecord(
                rs.getObject("id", UUID.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getLong("version"),
                rs.getObject("member_id", UUID.class),
                rs.getString("provider"),
                rs.getString("provider_user_id"),
                rs.getString("provider_email"),
                rs.getBoolean("provider_email_verified"),
                rs.getString("provider_nickname"),
                rs.getString("provider_avatar_url"),
                rs.getString("access_token_hash"),
                instant(rs.getTimestamp("last_login_at")));
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
