package com.prodigalgal.ircs.ops.traffic.application;

import com.prodigalgal.ircs.common.lock.TrafficLimitKeys;
import com.prodigalgal.ircs.common.lock.TrafficLimitKeys.TrafficKeyDescription;
import com.prodigalgal.ircs.ops.traffic.dto.TrafficSlotResponse;
import com.prodigalgal.ircs.ops.traffic.dto.TrafficStatusResponse;
import com.prodigalgal.ircs.ops.traffic.infrastructure.CredentialLabelRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TrafficMonitorService {

    private static final String PREFIX = TrafficLimitKeys.PREFIX;
    private static final String TMDB_PROVIDER = "TMDB";
    private static final String TMDB_API_KEY_PAYLOAD = "api_key";
    private static final int MAX_CONFIGURED_CREDENTIALS = 100;
    private static final long MAX_BLOCKING_TIME_MS = 30_000L;

    private final StringRedisTemplate redisTemplate;
    private final CredentialLabelRepository credentialLabelRepository;

    public TrafficStatusResponse getTrafficStatus() {
        List<TrafficSlotResponse> globals = new ArrayList<>();
        Map<String, TrafficSlotResponse> credentialSlots = new LinkedHashMap<>();

        try {
            for (String fullKey : indexedTrafficKeys()) {
                String rawKey = TrafficLimitKeys.stripPrefix(fullKey);
                if (!TrafficLimitKeys.isCurrentTrafficKey(rawKey)) {
                    removeIndexedKey(fullKey);
                    redisTemplate.delete(fullKey);
                    continue;
                }
                TrafficSlotResponse slot = buildSlot(rawKey, fullKey);
                if (slot == null) {
                    removeIndexedKey(fullKey);
                    continue;
                }
                if (rawKey.startsWith("cred:")) {
                    credentialSlots.put(slot.key(), slot);
                } else {
                    globals.add(slot);
                }
            }
        } catch (RuntimeException ignored) {
            return new TrafficStatusResponse(List.of(), List.of());
        }

        addConfiguredCredentialSlots(credentialSlots);
        List<TrafficSlotResponse> credentials = new ArrayList<>(credentialSlots.values());
        globals.sort(Comparator.comparing(TrafficSlotResponse::key));
        credentials.sort(Comparator.comparing(TrafficSlotResponse::key));
        return new TrafficStatusResponse(globals, credentials);
    }

    public void resetKey(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        String redisKey = PREFIX + key.trim();
        redisTemplate.delete(redisKey);
        removeIndexedKey(redisKey);
    }

    private Set<String> indexedTrafficKeys() {
        Set<String> keys = redisTemplate.opsForSet().members(TrafficLimitKeys.ACTIVE_INDEX_KEY);
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        Set<String> indexedKeys = new HashSet<>(keys);
        indexedKeys.removeIf(key -> !TrafficLimitKeys.isTrafficKey(key));
        return indexedKeys;
    }

    private void removeIndexedKey(String redisKey) {
        if (TrafficLimitKeys.isTrafficKey(redisKey)) {
            redisTemplate.opsForSet().remove(TrafficLimitKeys.ACTIVE_INDEX_KEY, redisKey);
            redisTemplate.delete(TrafficLimitKeys.metaKey(redisKey));
        }
    }

    private TrafficSlotResponse buildSlot(String rawKey, String redisKey) {
        DataType type = redisType(redisKey);
        if (DataType.HASH.equals(type)) {
            return buildTokenBucketSlot(rawKey, redisKey);
        }
        if (type != null && !DataType.STRING.equals(type) && !DataType.NONE.equals(type)) {
            return null;
        }
        return buildTimeSliceSlot(rawKey, redisKey);
    }

    private TrafficSlotResponse buildTimeSliceSlot(String rawKey, String redisKey) {
        String value = redisTemplate.opsForValue().get(redisKey);
        if (value == null || value.isBlank()) {
            return null;
        }

        long scheduledTime;
        try {
            scheduledTime = Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }

        long now = System.currentTimeMillis();
        long waitTime = Math.max(0L, scheduledTime - now);
        if (waitTime == 0L && scheduledTime < now - 60_000L) {
            return null;
        }

        double congestion = Math.round(((double) waitTime / MAX_BLOCKING_TIME_MS) * 100.0) / 100.0;
        TrafficSlotDetails details = details(rawKey, redisKey);
        return new TrafficSlotResponse(
                rawKey,
                details.label(),
                details.business(),
                details.scope(),
                details.target(),
                details.egressIdentity(),
                waitTime / 1000L,
                waitTime,
                congestion,
                waitTime > MAX_BLOCKING_TIME_MS,
                "TIME_SLICE",
                null,
                null,
                details.lastResult(),
                details.lastObservedAt(),
                details.totalRequests(),
                details.allowedCount(),
                details.waitingCount(),
                details.rejectedCount(),
                details.errorCount());
    }

    private TrafficSlotResponse buildTokenBucketSlot(String rawKey, String redisKey) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(redisKey);
        if (values == null || values.isEmpty()) {
            return null;
        }
        long remaining = parseLong(values.get("tokens"), -1L);
        long capacity = parseLong(values.get("capacity"), 0L);
        long permits = Math.max(1L, parseLong(values.get("permits"), 1L));
        long retryAfterMs = Math.max(0L, parseLong(values.get("retry_after_ms"), 0L));
        if (remaining < 0L && capacity <= 0L) {
            return null;
        }

        long safeRemaining = Math.max(0L, remaining);
        double congestion = capacity <= 0L
                ? 0.0
                : Math.round(((double) (capacity - Math.min(capacity, safeRemaining)) / capacity) * 100.0) / 100.0;
        long waitingTasks = safeRemaining >= permits ? 0L : permits - safeRemaining;
        TrafficSlotDetails details = details(rawKey, redisKey);
        return new TrafficSlotResponse(
                rawKey,
                details.label(),
                details.business(),
                details.scope(),
                details.target(),
                details.egressIdentity(),
                waitingTasks,
                retryAfterMs,
                congestion,
                retryAfterMs > MAX_BLOCKING_TIME_MS,
                "TOKEN_BUCKET",
                safeRemaining,
                capacity > 0L ? capacity : null,
                details.lastResult(),
                details.lastObservedAt(),
                details.totalRequests(),
                details.allowedCount(),
                details.waitingCount(),
                details.rejectedCount(),
                details.errorCount());
    }

    private DataType redisType(String redisKey) {
        try {
            return redisTemplate.type(redisKey);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private TrafficSlotDetails details(String rawKey, String redisKey) {
        TrafficKeyDescription description = TrafficLimitKeys.describe(rawKey);
        Map<Object, Object> meta = readMeta(redisKey);
        String label = stringValue(meta, "displayName", resolveLabel(rawKey));
        return new TrafficSlotDetails(
                label,
                stringValue(meta, "business", description.business()),
                stringValue(meta, "scope", description.scope()),
                stringValue(meta, "target", description.target()),
                stringValue(meta, "egressIdentity", description.egressIdentity()),
                stringValue(meta, "lastResult", "unknown"),
                stringValue(meta, "lastObservedAt", null),
                parseLong(meta.get("totalRequests"), 0L),
                parseLong(meta.get("allowedCount"), 0L),
                parseLong(meta.get("waitingCount"), 0L),
                parseLong(meta.get("rejectedCount"), 0L),
                parseLong(meta.get("errorCount"), 0L));
    }

    private Map<Object, Object> readMeta(String redisKey) {
        try {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(TrafficLimitKeys.metaKey(redisKey));
            return values == null ? Map.of() : values;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private String resolveLabel(String rawKey) {
        TrafficKeyDescription description = TrafficLimitKeys.describe(rawKey);
        if (rawKey.startsWith("cred:")) {
            return resolveCredentialLabel(rawKey.substring(5));
        }
        return description.displayName();
    }

    private String resolveCredentialLabel(String idText) {
        try {
            UUID id = UUID.fromString(idText);
            return credentialLabelRepository.findLabel(id)
                    .orElse("Unknown Credential (" + idText + ")");
        } catch (RuntimeException ex) {
            return "Invalid ID: " + idText;
        }
    }

    private void addConfiguredCredentialSlots(Map<String, TrafficSlotResponse> credentialSlots) {
        List<CredentialLabelRepository.CredentialTrafficLabel> labels;
        try {
            labels = credentialLabelRepository.findEnabledProviderLabels(
                    TMDB_PROVIDER,
                    TMDB_API_KEY_PAYLOAD,
                    MAX_CONFIGURED_CREDENTIALS);
        } catch (RuntimeException ignored) {
            return;
        }
        if (labels == null || labels.isEmpty()) {
            return;
        }
        for (CredentialLabelRepository.CredentialTrafficLabel label : labels) {
            if (label == null || label.id() == null) {
                continue;
            }
            String key = "cred:" + label.id();
            credentialSlots.putIfAbsent(key, idleCredentialSlot(key, label));
        }
    }

    private TrafficSlotResponse idleCredentialSlot(
            String key,
            CredentialLabelRepository.CredentialTrafficLabel label) {
        Long capacity = label.rateLimit() == null || label.rateLimit() <= 0
                ? null
                : label.rateLimit().longValue();
        return new TrafficSlotResponse(
                key,
                label.label(),
                "凭证",
                "credential",
                label.id().toString(),
                null,
                0L,
                0L,
                0.0,
                false,
                "TOKEN_BUCKET",
                capacity,
                capacity,
                "idle",
                null,
                0L,
                0L,
                0L,
                0L,
                0L);
    }

    private String stringValue(Map<Object, Object> values, String key, String fallback) {
        Object value = values.get(key);
        String text = value == null ? null : String.valueOf(value);
        return StringUtils.hasText(text) ? text : fallback;
    }

    private long parseLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record TrafficSlotDetails(
            String label,
            String business,
            String scope,
            String target,
            String egressIdentity,
            String lastResult,
            String lastObservedAt,
            long totalRequests,
            long allowedCount,
            long waitingCount,
            long rejectedCount,
            long errorCount) {
    }
}
