package com.prodigalgal.ircs.ops.traffic.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.lock.TrafficLimitKeys;
import com.prodigalgal.ircs.ops.traffic.dto.TrafficSlotResponse;
import com.prodigalgal.ircs.ops.traffic.dto.TrafficStatusResponse;
import com.prodigalgal.ircs.ops.traffic.infrastructure.CredentialLabelRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TrafficMonitorServiceTest {

    private static final String PREFIX = "traffic:limit:";

    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final CredentialLabelRepository credentialLabelRepository =
            org.mockito.Mockito.mock(CredentialLabelRepository.class);
    private final TrafficMonitorService service = new TrafficMonitorService(redisTemplate, credentialLabelRepository);

    @Test
    void groupsGlobalAndCredentialLimitersAndSkipsStaleKeys() {
        UUID credentialId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        long now = System.currentTimeMillis();
        ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);

        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(setOps.members(TrafficLimitKeys.ACTIVE_INDEX_KEY)).thenReturn(Set.of(
                PREFIX + "Provider:Metadata:Ip:203.0.113.10:TMDB",
                PREFIX + "Global:ImageDownload:Ip:203.0.113.10",
                PREFIX + "cred:" + credentialId,
                PREFIX + "Provider:TMDB",
                PREFIX + "Domain:stale.example"));
        when(valueOps.get(PREFIX + "Provider:Metadata:Ip:203.0.113.10:TMDB"))
                .thenReturn(String.valueOf(now + 2500L));
        when(valueOps.get(PREFIX + "Global:ImageDownload:Ip:203.0.113.10"))
                .thenReturn(String.valueOf(now + 1500L));
        when(valueOps.get(PREFIX + "cred:" + credentialId)).thenReturn(String.valueOf(now + 35_000L));
        when(valueOps.get(PREFIX + "Domain:stale.example")).thenReturn(String.valueOf(now - 120_000L));
        when(credentialLabelRepository.findLabel(credentialId)).thenReturn(Optional.of("[TMDB] main"));

        TrafficStatusResponse status = service.getTrafficStatus();

        assertEquals(2, status.globalLimiters().size());
        assertEquals("Global:ImageDownload:Ip:203.0.113.10", status.globalLimiters().getFirst().key());
        assertEquals("图片下载全局 / 出口 203.0.113.10", status.globalLimiters().getFirst().label());
        assertEquals("Provider:Metadata:Ip:203.0.113.10:TMDB", status.globalLimiters().get(1).key());
        assertFalse(status.globalLimiters().get(1).isBlocked());
        assertEquals(1, status.credentialLimiters().size());
        assertEquals("[TMDB] main", status.credentialLimiters().getFirst().label());
        assertTrue(status.credentialLimiters().getFirst().isBlocked());
        verify(setOps).remove(TrafficLimitKeys.ACTIVE_INDEX_KEY, PREFIX + "Provider:TMDB");
        verify(setOps).remove(TrafficLimitKeys.ACTIVE_INDEX_KEY, PREFIX + "Domain:stale.example");
    }

    @Test
    void returnsEmptyStatusWhenRedisIndexReadFails() {
        when(redisTemplate.opsForSet()).thenThrow(new IllegalStateException("redis down"));

        TrafficStatusResponse status = service.getTrafficStatus();

        assertTrue(status.globalLimiters().isEmpty());
        assertTrue(status.credentialLimiters().isEmpty());
    }

    @Test
    void exposesCredentialTokenBucketsStoredAsRedisHashes() {
        UUID credentialId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String key = PREFIX + "cred:" + credentialId;
        HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
        SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);

        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(TrafficLimitKeys.ACTIVE_INDEX_KEY)).thenReturn(Set.of(key));
        when(redisTemplate.type(key)).thenReturn(DataType.HASH);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(key)).thenReturn(Map.of(
                "tokens", "17",
                "capacity", "30",
                "permits", "1",
                "retry_after_ms", "0"));
        when(credentialLabelRepository.findLabel(credentialId)).thenReturn(Optional.of("[TMDB] main"));

        TrafficStatusResponse status = service.getTrafficStatus();

        assertEquals(1, status.credentialLimiters().size());
        TrafficSlotResponse slot = status.credentialLimiters().getFirst();
        assertEquals("[TMDB] main", slot.label());
        assertEquals("TOKEN_BUCKET", slot.limiterType());
        assertEquals(17L, slot.remainingPermits());
        assertEquals(30L, slot.capacity());
        assertEquals(0L, slot.waitingTasks());
        assertFalse(slot.isBlocked());
        assertEquals(0.43, slot.congestionRate());
    }

    @Test
    void includesEnabledTmdbCredentialsWithoutRuntimeBucket() {
        UUID firstCredentialId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID secondCredentialId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);

        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(TrafficLimitKeys.ACTIVE_INDEX_KEY)).thenReturn(Set.of());
        when(credentialLabelRepository.findEnabledProviderLabels("TMDB", "api_key", 100))
                .thenReturn(List.of(
                        new CredentialLabelRepository.CredentialTrafficLabel(
                                firstCredentialId,
                                "TMDB",
                                "Default TMDB Key 1",
                                40),
                        new CredentialLabelRepository.CredentialTrafficLabel(
                                secondCredentialId,
                                "TMDB",
                                "Default TMDB Key 2",
                                40)));

        TrafficStatusResponse status = service.getTrafficStatus();

        assertEquals(2, status.credentialLimiters().size());
        TrafficSlotResponse first = status.credentialLimiters().getFirst();
        assertEquals("cred:" + firstCredentialId, first.key());
        assertEquals("[TMDB] Default TMDB Key 1", first.label());
        assertEquals("TOKEN_BUCKET", first.limiterType());
        assertEquals(40L, first.remainingPermits());
        assertEquals(40L, first.capacity());
        assertFalse(first.isBlocked());
    }

    @Test
    void labelsDatasourceIpLimiter() {
        UUID sourceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        long now = System.currentTimeMillis();
        ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);

        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(setOps.members(TrafficLimitKeys.ACTIVE_INDEX_KEY))
                .thenReturn(Set.of(PREFIX + "DataSource:Scraper:Ip:203.0.113.10:" + sourceId));
        when(valueOps.get(PREFIX + "DataSource:Scraper:Ip:203.0.113.10:" + sourceId))
                .thenReturn(String.valueOf(now + 1200L));

        TrafficStatusResponse status = service.getTrafficStatus();

        assertEquals(1, status.globalLimiters().size());
        assertEquals("资源站采集: " + sourceId + " / 出口 203.0.113.10", status.globalLimiters().getFirst().label());
    }

    @Test
    void labelsProviderAndDomainIpLimiters() {
        long now = System.currentTimeMillis();
        ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);

        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(setOps.members(TrafficLimitKeys.ACTIVE_INDEX_KEY)).thenReturn(Set.of(
                PREFIX + "Provider:Magnet:Ip:2001_db8__10:DOUBAN",
                PREFIX + "Domain:ImageDownload:Ip:203.0.113.10:img.example.com"));
        when(valueOps.get(PREFIX + "Provider:Magnet:Ip:2001_db8__10:DOUBAN"))
                .thenReturn(String.valueOf(now + 1200L));
        when(valueOps.get(PREFIX + "Domain:ImageDownload:Ip:203.0.113.10:img.example.com"))
                .thenReturn(String.valueOf(now + 800L));

        TrafficStatusResponse status = service.getTrafficStatus();

        assertEquals(2, status.globalLimiters().size());
        assertEquals("图片域名: img.example.com / 出口 203.0.113.10", status.globalLimiters().getFirst().label());
        assertEquals("磁链 Provider: DOUBAN / 出口 2001_db8__10", status.globalLimiters().get(1).label());
    }

    @Test
    void resetDeletesNamespacedTrafficKey() {
        SetOperations<String, String> setOps = org.mockito.Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        service.resetKey("Provider:Metadata:Ip:203.0.113.10:TMDB");

        verify(redisTemplate).delete(PREFIX + "Provider:Metadata:Ip:203.0.113.10:TMDB");
        verify(setOps).remove(
                TrafficLimitKeys.ACTIVE_INDEX_KEY,
                PREFIX + "Provider:Metadata:Ip:203.0.113.10:TMDB");
    }
}
