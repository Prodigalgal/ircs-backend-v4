package com.prodigalgal.ircs.identity.infrastructure;




import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcMemberRepository implements MemberRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<MemberRecord> findByEmail(String email) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectSql() + " where lower(email) = lower(?)",
                    mapper(),
                    email));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<MemberRecord> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectSql() + " where id = ?",
                    mapper(),
                    id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public boolean existsByEmail(String email) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from members where lower(email) = lower(?))",
                Boolean.class,
                email);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public MemberRecord insert(MemberRecord member) {
        jdbcTemplate.update(
                """
                insert into members (
                    id, created_at, updated_at, version, email, password_hash, nickname, avatar_url, role, status,
                    adult_content_allowed, experience, points, last_check_in_date, check_in_streak
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                member.id(),
                timestamp(member.createdAt()),
                timestamp(member.updatedAt()),
                member.version(),
                member.email(),
                member.passwordHash(),
                member.nickname(),
                member.avatarUrl(),
                member.role(),
                member.status().name(),
                member.adultContentAllowed(),
                member.experience(),
                member.points(),
                date(member.lastCheckInDate()),
                member.checkInStreak());
        return member;
    }

    @Override
    public MemberRecord update(MemberRecord member) {
        jdbcTemplate.update(
                """
                update members
                   set updated_at = ?,
                       version = coalesce(version, 0) + 1,
                       email = ?,
                       password_hash = ?,
                       nickname = ?,
                       avatar_url = ?,
                       role = ?,
                       status = ?,
                       adult_content_allowed = ?,
                       experience = ?,
                       points = ?,
                       last_check_in_date = ?,
                       check_in_streak = ?
                 where id = ?
                """,
                timestamp(member.updatedAt()),
                member.email(),
                member.passwordHash(),
                member.nickname(),
                member.avatarUrl(),
                member.role(),
                member.status().name(),
                member.adultContentAllowed(),
                member.experience(),
                member.points(),
                date(member.lastCheckInDate()),
                member.checkInStreak(),
                member.id());
        return member;
    }

    private String selectSql() {
        return """
        select id, created_at, updated_at, version, email, password_hash, nickname, avatar_url, role, status,
               coalesce(adult_content_allowed, false) as adult_content_allowed,
               experience, points, last_check_in_date, check_in_streak
          from members
        """;
    }

    private RowMapper<MemberRecord> mapper() {
        return (rs, rowNum) -> new MemberRecord(
                rs.getObject("id", UUID.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getObject("version", Long.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("nickname"),
                rs.getString("avatar_url"),
                rs.getString("role"),
                MemberStatus.valueOf(rs.getString("status")),
                rs.getBoolean("adult_content_allowed"),
                rs.getInt("experience"),
                rs.getInt("points"),
                localDate(rs.getDate("last_check_in_date")),
                rs.getInt("check_in_streak"));
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant == null ? Instant.now() : instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Date date(LocalDate date) {
        return date == null ? null : Date.valueOf(date);
    }

    private LocalDate localDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
