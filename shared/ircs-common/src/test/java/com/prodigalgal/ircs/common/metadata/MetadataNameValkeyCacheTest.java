package com.prodigalgal.ircs.common.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class MetadataNameValkeyCacheTest {

    private static final String PREFIX = "ircs:metadata:names:test";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
    private final MetadataNameValkeyCache cache = new MetadataNameValkeyCache(
            provider(redisTemplate),
            PREFIX,
            Duration.ofHours(1));

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void readsValkeyHashHitsAndEmptySentinel() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        List<Object> fields = List.of(first.toString(), second.toString(), third.toString());
        when(hashOperations.multiGet(
                        eq(PREFIX + ":raw:actors"),
                        eq(fields)))
                .thenReturn(Arrays.asList("Alice\u001FBob", "", null));

        Map<UUID, Set<String>> result = cache.findNames(
                MetadataNameOwnerType.RAW,
                MetadataNameRelation.ACTORS,
                List.of(first, second, third));

        assertThat(result)
                .containsEntry(first, Set.of("Alice", "Bob"))
                .containsEntry(second, Set.of());
        assertThat(result).doesNotContainKey(third);
    }

    @Test
    void writesNamesAndEvictsAllRelationsForOwner() {
        UUID ownerId = UUID.randomUUID();

        cache.putNames(
                MetadataNameOwnerType.UNIFIED,
                MetadataNameRelation.DIRECTORS,
                Map.of(ownerId, Set.of("Director")));
        cache.evictOwner(MetadataNameOwnerType.UNIFIED, List.of(ownerId));

        verify(hashOperations).putAll(eq(PREFIX + ":unified:directors"), any(Map.class));
        verify(redisTemplate).expire(PREFIX + ":unified:directors", Duration.ofHours(1));
        verify(hashOperations).delete(PREFIX + ":unified:actors", ownerId.toString());
        verify(hashOperations).delete(PREFIX + ":unified:directors", ownerId.toString());
        verify(hashOperations).delete(PREFIX + ":unified:areas", ownerId.toString());
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
