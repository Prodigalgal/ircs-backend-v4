package com.prodigalgal.ircs.content.video.application;



import com.prodigalgal.ircs.content.video.messaging.ContentCommandPublisher;
import com.prodigalgal.ircs.content.video.infrastructure.JdbcVideoAdminRepository;
import com.prodigalgal.ircs.content.maintenance.application.ContentMaintenanceGate;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import com.prodigalgal.ircs.contracts.trend.TrendItemPayload;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyRequest;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TrendSyncContentService {

    private final JdbcVideoAdminRepository repository;
    private final ContentCommandPublisher publisher;
    private final ContentMaintenanceGate maintenanceGate;

    @Value("${app.content.trend-sync.title-similarity-threshold:${APP_CONTENT_TREND_SYNC_TITLE_SIMILARITY_THRESHOLD:0.4}}")
    private double titleSimilarityThreshold;

    @Transactional
    public TrendSyncApplyResponse apply(TrendSyncApplyRequest request) {
        List<TrendItemPayload> candidates = request == null ? List.of() : request.items();
        if (candidates.isEmpty()) {
            return new TrendSyncApplyResponse("trend-sync", 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }

        maintenanceGate.assertUnifiedVideoWrite(null);

        Instant now = Instant.now();
        Set<String> doubanIds = new LinkedHashSet<>();
        Set<String> tmdbIds = new LinkedHashSet<>();
        List<TrendItemPayload> potentialNewItems = new ArrayList<>();
        Set<String> processedKeys = new LinkedHashSet<>();
        long skippedDuplicates = 0;

        for (TrendItemPayload item : candidates) {
            String uniqueKey = uniqueKey(item);
            if (!processedKeys.add(uniqueKey)) {
                skippedDuplicates++;
                continue;
            }
            if (StringUtils.hasText(item.doubanId())) {
                doubanIds.add(item.doubanId().trim());
            } else if (StringUtils.hasText(item.tmdbId())) {
                tmdbIds.add(item.tmdbId().trim());
            } else {
                potentialNewItems.add(item);
            }
        }

        List<UUID> updatedByExternal = new ArrayList<>();
        updatedByExternal.addAll(repository.updateTrendTimeByDoubanIds(doubanIds, now));
        updatedByExternal.addAll(repository.updateTrendTimeByTmdbIds(tmdbIds, now));
        updatedByExternal.forEach(id -> publisher.publishUnifiedSearch(id, SyncOperation.INDEX));

        Set<String> existingDoubanIds = repository.findExistingDoubanIds(doubanIds);
        Set<String> existingTmdbIds = repository.findExistingTmdbIds(tmdbIds);

        List<TrendItemPayload> finalNewItems = new ArrayList<>();
        for (TrendItemPayload item : candidates) {
            if (StringUtils.hasText(item.doubanId()) && !existingDoubanIds.contains(item.doubanId().trim())) {
                finalNewItems.add(item);
            } else if (StringUtils.hasText(item.tmdbId()) && !existingTmdbIds.contains(item.tmdbId().trim())) {
                finalNewItems.add(item);
            }
        }

        List<UUID> titleMatched = new ArrayList<>();
        for (TrendItemPayload item : potentialNewItems) {
            if (StringUtils.hasText(item.title()) && StringUtils.hasText(item.year())) {
                List<String> matches = repository.findIdsByYearAndTitleSimilarity(
                        List.of(item.year().trim()),
                        item.title(),
                        titleSimilarityThreshold);
                if (!matches.isEmpty()) {
                    UUID id = UUID.fromString(matches.getFirst());
                    if (repository.updateTrendTimeById(id, now)) {
                        titleMatched.add(id);
                        publisher.publishUnifiedSearch(id, SyncOperation.INDEX);
                        continue;
                    }
                }
            }
            finalNewItems.add(item);
        }

        List<TrendItemPayload> uniqueNewItems = new ArrayList<>();
        Set<String> finalKeys = new LinkedHashSet<>();
        for (TrendItemPayload item : finalNewItems) {
            if (finalKeys.add(uniqueKey(item))) {
                uniqueNewItems.add(item);
            }
        }

        List<UUID> created = new ArrayList<>();
        List<String> discoveryKeywords = new ArrayList<>();
        for (TrendItemPayload item : uniqueNewItems) {
            if (!StringUtils.hasText(item.title())) {
                continue;
            }
            UUID id = repository.createTrendGhost(item, now);
            created.add(id);
            publisher.publishUnifiedSearch(id, SyncOperation.INDEX);
            discoveryKeywords.add(item.title().trim());
        }

        List<UUID> updated = new ArrayList<>(updatedByExternal);
        updated.addAll(titleMatched);
        return new TrendSyncApplyResponse(
                "trend-sync",
                candidates.size(),
                updatedByExternal.size(),
                titleMatched.size(),
                created.size(),
                skippedDuplicates,
                updated,
                created,
                discoveryKeywords);
    }

    private String uniqueKey(TrendItemPayload item) {
        if (item == null) {
            return "N::";
        }
        if (StringUtils.hasText(item.doubanId())) {
            return "D:" + item.doubanId().trim();
        }
        if (StringUtils.hasText(item.tmdbId())) {
            return "T:" + item.tmdbId().trim();
        }
        return "N:" + nullToEmpty(item.title()) + ":" + nullToEmpty(item.year());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
