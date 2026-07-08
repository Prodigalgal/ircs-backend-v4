package com.prodigalgal.ircs.common.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.lease.UnavailableClusterLeaseService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class DistributedLockHealthIndicatorTest {

    @Test
    void reportsRedisBackendUpWhenPingSucceeds() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        RedisConnectionFactory connectionFactory = Mockito.mock(RedisConnectionFactory.class);
        RedisConnection connection = Mockito.mock(RedisConnection.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        Health health = new DistributedLockHealthIndicator(new RedisDistributedLockManager(redisTemplate)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("backend", "redis")
                .containsEntry("reason", "OK");
        verify(connection).close();
    }

    @Test
    void reportsUnavailableBackendDown() {
        Health health = new DistributedLockHealthIndicator(
                        new UnavailableClusterLeaseService("Redis StringRedisTemplate is unavailable for distributed locks"))
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("backend", "unavailable")
                .containsEntry("reason", "Redis StringRedisTemplate is unavailable for distributed locks");
    }
}
