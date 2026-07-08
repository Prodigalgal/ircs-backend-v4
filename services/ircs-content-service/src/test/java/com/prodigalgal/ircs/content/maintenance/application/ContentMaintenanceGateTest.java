package com.prodigalgal.ircs.content.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.maintenance.MaintenanceGateCheckKind;
import com.prodigalgal.ircs.common.maintenance.MaintenanceGateDecision;
import com.prodigalgal.ircs.common.maintenance.MaintenanceGateLockedException;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateChangedEvent;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ContentMaintenanceGateTest {

    private static final Instant NOW = Instant.parse("2099-06-11T10:15:30Z");
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final String REDIS_PREFIX = "ircs:maintenance:gate:block:content-service:";

    @Test
    void genericVideoGateBlocksRawAndUnifiedWrites() {
        Fixture fixture = fixture("generic");
        UUID rawId = UUID.randomUUID();
        UUID unifiedId = UUID.randomUUID();
        fixture.insert("content-video-rebuild", "video", "*", "READ_ONLY", NOW.plusSeconds(600));

        assertThatThrownBy(() -> fixture.gate().assertRawVideoWrite(rawId))
                .isInstanceOf(MaintenanceGateLockedException.class);
        assertThatThrownBy(() -> fixture.gate().assertUnifiedVideoWrite(unifiedId))
                .isInstanceOf(MaintenanceGateLockedException.class);
    }

    @Test
    void specificUnifiedGateDoesNotBlockRawWrites() {
        Fixture fixture = fixture("specific");
        UUID rawId = UUID.randomUUID();
        UUID unifiedId = UUID.randomUUID();
        fixture.insert("unified-recalculate", "unified-video", unifiedId.toString(), "QUIESCE_WRITES", NOW.plusSeconds(600));

        fixture.gate().assertRawVideoWrite(rawId);

        assertThatThrownBy(() -> fixture.gate().assertUnifiedVideoWrite(unifiedId))
                .isInstanceOf(MaintenanceGateLockedException.class);
    }

    @Test
    void cachesBlockedDbDecisionToRedisAndLocalCache() {
        Fixture fixture = fixture("redis_write");
        RedisFixture redis = redis();
        UUID rawId = UUID.randomUUID();
        String resourceScope = rawId.toString();
        ContentMaintenanceGate gate = new ContentMaintenanceGate(
                fixture.jdbcTemplate(),
                OBJECT_MAPPER,
                redisProvider(redis.template()));
        fixture.insert("content-video-rebuild", "video", "*", "READ_ONLY", NOW.plusSeconds(600));

        assertThatThrownBy(() -> gate.assertRawVideoWrite(rawId))
                .isInstanceOf(MaintenanceGateLockedException.class);

        String redisKey = redisKey("video", resourceScope);
        verify(redis.values()).set(org.mockito.Mockito.eq(redisKey), anyString(), any(Duration.class));
        assertThat(gate.blockedDecisionCacheSize()).isEqualTo(1);
    }

    @Test
    void readsBlockedDecisionFromRedisBeforeDbFallback() throws Exception {
        Fixture fixture = fixture("redis_read");
        RedisFixture redis = redis();
        UUID rawId = UUID.randomUUID();
        String resourceScope = rawId.toString();
        ContentMaintenanceGate gate = new ContentMaintenanceGate(
                fixture.jdbcTemplate(),
                OBJECT_MAPPER,
                redisProvider(redis.template()));
        String redisKey = redisKey("video", resourceScope);
        MaintenanceGateDecision blocked = MaintenanceGateDecision.blocked(
                MaintenanceGateCheckKind.WRITE,
                UUID.randomUUID(),
                "content-video-rebuild",
                "content-service",
                "video",
                resourceScope,
                MaintenanceGateMode.READ_ONLY,
                "rebuild",
                Instant.now().plusSeconds(600));
        when(redis.values().get(redisKey)).thenReturn(OBJECT_MAPPER.writeValueAsString(blocked));

        MaintenanceGateDecision decision = gate.checkWrite("video", resourceScope);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.operationKey()).isEqualTo("content-video-rebuild");
        assertThat(gate.blockedDecisionCacheSize()).isEqualTo(1);
    }

    @Test
    void invalidationClearsLocalAndRedisBlockedDecision() {
        Fixture fixture = fixture("redis_evict");
        RedisFixture redis = redis();
        UUID rawId = UUID.randomUUID();
        String resourceScope = rawId.toString();
        ContentMaintenanceGate gate = new ContentMaintenanceGate(
                fixture.jdbcTemplate(),
                OBJECT_MAPPER,
                redisProvider(redis.template()));
        fixture.insert("content-video-rebuild", "video", "*", "READ_ONLY", NOW.plusSeconds(600));
        assertThatThrownBy(() -> gate.assertRawVideoWrite(rawId))
                .isInstanceOf(MaintenanceGateLockedException.class);
        String redisKey = redisKey("video", resourceScope);

        gate.invalidate(new MaintenanceGateChangedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "content-video-rebuild",
                MaintenanceGateChangedEvent.Action.CLOSED,
                "content-service",
                "video",
                resourceScope,
                MaintenanceGateMode.READ_ONLY,
                MaintenanceGateStatus.CLOSED,
                2L,
                NOW,
                NOW.plusSeconds(600),
                "corr"));

        assertThat(gate.blockedDecisionCacheSize()).isZero();
        assertThat(gate.lastInvalidationRevision()).isEqualTo(2L);
        verify(redis.template()).delete(redisKey);
    }

    private static Fixture fixture(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:content_maintenance_gate_" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table maintenance_operations (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint not null default 0,
                    operation_key varchar(128) not null,
                    owner_service varchar(128) not null,
                    resource_type varchar(128) not null,
                    resource_scope varchar(256) not null default '*',
                    mode varchar(32) not null,
                    status varchar(32) not null default 'ACTIVE',
                    reason varchar(512),
                    requested_by varchar(128),
                    correlation_id varchar(128),
                    expires_at timestamp not null,
                    closed_at timestamp,
                    close_reason varchar(512)
                )
                """);
        return new Fixture(jdbcTemplate, new ContentMaintenanceGate(jdbcTemplate, OBJECT_MAPPER, null));
    }

    @SuppressWarnings("unchecked")
    private static RedisFixture redis() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        return new RedisFixture(redisTemplate, values);
    }

    private static ObjectProvider<StringRedisTemplate> redisProvider(StringRedisTemplate redisTemplate) {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() {
                return redisTemplate;
            }
        };
    }

    private static String redisKey(String resourceType, String resourceScope) {
        return REDIS_PREFIX + resourceType + ":" + resourceScope;
    }

    private record Fixture(JdbcTemplate jdbcTemplate, ContentMaintenanceGate gate) {
        void insert(String operationKey, String resourceType, String resourceScope, String mode, Instant expiresAt) {
            jdbcTemplate.update("""
                    insert into maintenance_operations (
                        id, created_at, updated_at, operation_key, owner_service, resource_type,
                        resource_scope, mode, status, reason, expires_at
                    ) values (?, ?, ?, ?, 'content-service', ?, ?, ?, 'ACTIVE', '', ?)
                    """,
                    UUID.randomUUID(),
                    Timestamp.from(NOW.minusSeconds(60)),
                    Timestamp.from(NOW.minusSeconds(60)),
                    operationKey,
                    resourceType,
                    resourceScope,
                    mode,
                    Timestamp.from(expiresAt));
        }
    }

    private record RedisFixture(StringRedisTemplate template, ValueOperations<String, String> values) {
    }
}
