package com.prodigalgal.ircs.opsalert.application;

import com.prodigalgal.ircs.opsalert.dto.AlertEventFilter;
import com.prodigalgal.ircs.opsalert.dto.AlertEventResponse;
import com.prodigalgal.ircs.opsalert.dto.HealingActionFilter;
import com.prodigalgal.ircs.opsalert.dto.HealingActionResponse;
import com.prodigalgal.ircs.opsalert.dto.IncidentFilter;
import com.prodigalgal.ircs.opsalert.dto.IncidentResponse;
import com.prodigalgal.ircs.opsalert.dto.OpsAlertMapper;
import com.prodigalgal.ircs.opsalert.infrastructure.OpsAlertRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsAlertQueryService {

    private static final Duration FIRST_PAGE_CACHE_TTL = Duration.ofSeconds(30);

    private final OpsAlertRepository repository;
    private final FirstPageCache<AlertEventResponse> eventFirstPageCache = new FirstPageCache<>(FIRST_PAGE_CACHE_TTL);
    private final FirstPageCache<IncidentResponse> incidentFirstPageCache = new FirstPageCache<>(FIRST_PAGE_CACHE_TTL);
    private final FirstPageCache<HealingActionResponse> healingActionFirstPageCache =
            new FirstPageCache<>(FIRST_PAGE_CACHE_TTL);

    public Page<AlertEventResponse> findEvents(Pageable pageable, AlertEventFilter filter) {
        Supplier<Page<AlertEventResponse>> loader =
                () -> repository.findEvents(pageable, filter).map(OpsAlertMapper::toResponse);
        if (!firstPageCacheable(pageable) || !unfiltered(filter)) {
            return loader.get();
        }
        return eventFirstPageCache.get(cacheKey(pageable), loader);
    }

    public Page<IncidentResponse> findIncidents(Pageable pageable, IncidentFilter filter) {
        Supplier<Page<IncidentResponse>> loader =
                () -> repository.findIncidents(pageable, filter).map(OpsAlertMapper::toResponse);
        if (!firstPageCacheable(pageable) || !unfiltered(filter)) {
            return loader.get();
        }
        return incidentFirstPageCache.get(cacheKey(pageable), loader);
    }

    public Page<HealingActionResponse> findHealingActions(Pageable pageable, HealingActionFilter filter) {
        Supplier<Page<HealingActionResponse>> loader =
                () -> repository.findHealingActions(pageable, filter).map(OpsAlertMapper::toResponse);
        if (!firstPageCacheable(pageable) || !unfiltered(filter)) {
            return loader.get();
        }
        return healingActionFirstPageCache.get(cacheKey(pageable), loader);
    }

    public int warmFirstPages(int pageSize) {
        int boundedPageSize = Math.max(1, Math.min(pageSize, 100));
        findEvents(
                PageRequest.of(0, boundedPageSize, Sort.by(Sort.Direction.DESC, "createdAt")),
                new AlertEventFilter(null, null, null, null, null, null, null, null));
        findIncidents(
                PageRequest.of(0, boundedPageSize, Sort.by(Sort.Direction.DESC, "lastSeenAt")),
                new IncidentFilter(null, null, null, null, null, null, null, null));
        findHealingActions(
                PageRequest.of(0, boundedPageSize, Sort.by(Sort.Direction.DESC, "createdAt")),
                new HealingActionFilter(null, null, null, null, null, null, null));
        return 3;
    }

    private boolean firstPageCacheable(Pageable pageable) {
        return pageable != null && pageable.getPageNumber() == 0;
    }

    private String cacheKey(Pageable pageable) {
        return "page=%d|size=%d|sort=%s".formatted(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                pageable.getSort());
    }

    private boolean unfiltered(AlertEventFilter filter) {
        return filter == null
                || (filter.severity() == null
                        && !StringUtils.hasText(filter.source())
                        && !StringUtils.hasText(filter.eventType())
                        && !StringUtils.hasText(filter.resourceType())
                        && !StringUtils.hasText(filter.resourceName())
                        && !StringUtils.hasText(filter.fingerprint())
                        && filter.from() == null
                        && filter.to() == null);
    }

    private boolean unfiltered(IncidentFilter filter) {
        return filter == null
                || (filter.status() == null
                        && filter.severity() == null
                        && !StringUtils.hasText(filter.source())
                        && !StringUtils.hasText(filter.resourceType())
                        && !StringUtils.hasText(filter.resourceName())
                        && !StringUtils.hasText(filter.fingerprint())
                        && filter.from() == null
                        && filter.to() == null);
    }

    private boolean unfiltered(HealingActionFilter filter) {
        return filter == null
                || (filter.incidentId() == null
                        && filter.status() == null
                        && !StringUtils.hasText(filter.policyKey())
                        && !StringUtils.hasText(filter.playbookKey())
                        && filter.dryRun() == null
                        && filter.from() == null
                        && filter.to() == null);
    }

    private static final class FirstPageCache<T> {

        private final Duration ttl;
        private final Map<String, CachedPage<T>> pages = new ConcurrentHashMap<>();

        private FirstPageCache(Duration ttl) {
            this.ttl = ttl;
        }

        private Page<T> get(String key, Supplier<Page<T>> loader) {
            Instant now = Instant.now();
            CachedPage<T> cached = pages.get(key);
            if (cached != null && cached.isFresh(now)) {
                return cached.page();
            }
            synchronized (this) {
                now = Instant.now();
                cached = pages.get(key);
                if (cached != null && cached.isFresh(now)) {
                    return cached.page();
                }
                Page<T> loaded = loader.get();
                pages.put(key, new CachedPage<>(loaded, now.plus(ttl)));
                return loaded;
            }
        }
    }

    private record CachedPage<T>(
            Page<T> page,
            Instant expiresAt) {

        private boolean isFresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
