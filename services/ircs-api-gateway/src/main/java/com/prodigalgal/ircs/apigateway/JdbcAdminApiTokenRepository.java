package com.prodigalgal.ircs.apigateway;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdminApiTokenRepository implements AdminApiTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcAdminApiTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(AdminApiTokenRecord record) {
        jdbcTemplate.update(
                """
                        insert into admin_api_tokens (
                            id, created_at, updated_at, version, name, token_prefix, token_hash,
                            status, created_by, last_used_at, revoked_at, revoked_by, expires_at
                        ) values (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                record.id(),
                timestamp(record.createdAt()),
                timestamp(record.createdAt()),
                record.name(),
                record.tokenPrefix(),
                record.tokenHash(),
                record.status(),
                record.createdBy(),
                timestamp(record.lastUsedAt()),
                timestamp(record.revokedAt()),
                record.revokedBy(),
                timestamp(record.expiresAt()));
    }

    @Override
    public List<AdminApiTokenRecord> list() {
        return jdbcTemplate.query(
                """
                        select id, name, token_prefix, token_hash, status, created_by, created_at,
                               last_used_at, revoked_at, revoked_by, expires_at
                          from admin_api_tokens
                         order by created_at desc
                        """,
                this::map);
    }

    @Override
    public Optional<AdminApiTokenRecord> findActiveByHash(String tokenHash, Instant now) {
        List<AdminApiTokenRecord> records = jdbcTemplate.query(
                """
                        select id, name, token_prefix, token_hash, status, created_by, created_at,
                               last_used_at, revoked_at, revoked_by, expires_at
                          from admin_api_tokens
                         where token_hash = ?
                           and status = 'ACTIVE'
                           and (expires_at is null or expires_at > ?)
                         limit 1
                        """,
                this::map,
                tokenHash,
                timestamp(now));
        return records.stream().findFirst();
    }

    @Override
    public boolean revoke(UUID id, String revokedBy, Instant now) {
        return jdbcTemplate.update(
                """
                        update admin_api_tokens
                           set status = 'REVOKED',
                               revoked_at = ?,
                               revoked_by = ?,
                               updated_at = ?,
                               version = version + 1
                         where id = ?
                           and status = 'ACTIVE'
                        """,
                timestamp(now),
                revokedBy,
                timestamp(now),
                id) > 0;
    }

    @Override
    public void touch(UUID id, Instant now) {
        jdbcTemplate.update(
                """
                        update admin_api_tokens
                           set last_used_at = ?,
                               updated_at = ?,
                               version = version + 1
                         where id = ?
                           and status = 'ACTIVE'
                        """,
                timestamp(now),
                timestamp(now),
                id);
    }

    private AdminApiTokenRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new AdminApiTokenRecord(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("token_prefix"),
                rs.getString("token_hash"),
                rs.getString("status"),
                rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("last_used_at")),
                instant(rs.getTimestamp("revoked_at")),
                rs.getString("revoked_by"),
                instant(rs.getTimestamp("expires_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
