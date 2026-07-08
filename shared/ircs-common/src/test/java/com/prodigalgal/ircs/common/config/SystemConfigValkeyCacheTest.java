package com.prodigalgal.ircs.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SystemConfigValkeyCacheTest {

    private static final String PREFIX = "ircs:system-config:test";
    private static final Duration REMOTE_TTL = Duration.ofHours(12);
    private static final Duration LOCAL_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final SystemConfigValkeyCache cache = new SystemConfigValkeyCache(
            provider(redisTemplate),
            PREFIX,
            REMOTE_TTL,
            LOCAL_TTL);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void readsFromLocalBeforeValkeyAndDatabase() {
        when(valueOperations.get(PREFIX + ":worker.enabled")).thenReturn("true");
        AtomicInteger dbLoads = new AtomicInteger();

        assertThat(cache.findValue("worker.enabled", () -> {
            dbLoads.incrementAndGet();
            return Optional.of("false");
        })).contains("true");
        assertThat(cache.findValue("worker.enabled", () -> {
            dbLoads.incrementAndGet();
            return Optional.of("false");
        })).contains("true");

        assertThat(dbLoads).hasValue(0);
        verify(valueOperations, times(1)).get(PREFIX + ":worker.enabled");
    }

    @Test
    void valkeyMissLoadsDatabaseAndBackfillsValkey() {
        when(valueOperations.get(PREFIX + ":mail.enabled")).thenReturn(null);

        assertThat(cache.findValue("mail.enabled", () -> Optional.of("false"))).contains("false");
        assertThat(cache.findValue("mail.enabled", Optional::empty)).contains("false");

        verify(valueOperations, times(1)).get(PREFIX + ":mail.enabled");
        verify(valueOperations).set(PREFIX + ":mail.enabled", "false", REMOTE_TTL);
    }

    @Test
    void localLayerCachesMissingDatabaseValuesWithoutWritingValkey() {
        when(valueOperations.get(PREFIX + ":missing")).thenReturn(null);
        AtomicInteger dbLoads = new AtomicInteger();

        assertThat(cache.findValue("missing", () -> {
            dbLoads.incrementAndGet();
            return Optional.empty();
        })).isEmpty();
        assertThat(cache.findValue("missing", () -> {
            dbLoads.incrementAndGet();
            return Optional.of("late");
        })).isEmpty();

        assertThat(dbLoads).hasValue(1);
        verify(valueOperations, times(1)).get(PREFIX + ":missing");
        verify(valueOperations, never()).set(eq(PREFIX + ":missing"), eq("late"), eq(REMOTE_TTL));
    }

    @Test
    void evictLocalKeepsValkeyLayerAuthoritative() {
        cache.put("scraper.base-url", "http://old");
        cache.evictLocal("scraper.base-url");
        when(valueOperations.get(PREFIX + ":scraper.base-url")).thenReturn("http://new");

        assertThat(cache.findValue("scraper.base-url", () -> Optional.of("http://db"))).contains("http://new");

        verify(redisTemplate, never()).delete(PREFIX + ":scraper.base-url");
    }

    @Test
    void putAfterCommitWritesOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();

        cache.putAfterCommit("r2.enabled", "true");

        verify(valueOperations, never()).set(PREFIX + ":r2.enabled", "true", REMOTE_TTL);
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());

        verify(valueOperations).set(PREFIX + ":r2.enabled", "true", REMOTE_TTL);
    }

    private static ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate redisTemplate) {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() {
                return redisTemplate;
            }
        };
    }
}
