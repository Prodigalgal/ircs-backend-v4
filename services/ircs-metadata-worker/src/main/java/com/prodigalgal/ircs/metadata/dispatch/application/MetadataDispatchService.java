package com.prodigalgal.ircs.metadata.dispatch.application;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkPublisher;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.config.MetadataConfigValues;
import com.prodigalgal.ircs.metadata.dispatch.domain.RawVideoEnrichmentRecord;
import com.prodigalgal.ircs.metadata.dispatch.dto.MetadataDispatchResult;
import com.prodigalgal.ircs.metadata.dispatch.infrastructure.RawVideoEnrichmentRepository;
import com.prodigalgal.ircs.metadata.dispatch.messaging.MetadataProviderTaskPublisher;
import com.prodigalgal.ircs.metadata.pipeline.MetadataPipelineRunRepository;
import com.prodigalgal.ircs.metadata.result.messaging.RawSearchSyncPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class MetadataDispatchService {

    private final RawVideoEnrichmentRepository repository;
    private final MetadataConfigValues configValues;
    private final MetadataPipelineRunRepository pipelineRunRepository;
    private final MetadataProviderTaskPublisher providerTaskPublisher;
    private final RawSearchSyncPublisher searchSyncPublisher;
    private final AggregationWorkPublisher aggregationWorkPublisher;

    public MetadataDispatchResult dispatch(UUID videoId, String pipelineVersion) {
        Optional<RawVideoEnrichmentRecord> recordOpt = repository.findById(videoId);
        if (recordOpt.isEmpty()) {
            log.warn("Metadata dispatch references missing raw video: {}", videoId);
            return new MetadataDispatchResult(videoId, false, null, List.of());
        }

        RawVideoEnrichmentRecord record = recordOpt.get();
        if (isStale(pipelineVersion, record.getDataHash())) {
            return new MetadataDispatchResult(videoId, true, "STALE", List.of());
        }

        if ("SUCCESS".equals(record.getEnrichmentStatus())
                || "PERMANENT_FAILURE".equals(record.getEnrichmentStatus())) {
            return new MetadataDispatchResult(videoId, true, record.getEnrichmentStatus(), List.of());
        }

        List<ProviderType> providers = providersFor(record);
        if (providers.isEmpty()) {
            String status = finalizeImmediate(record);
            searchSyncPublisher.publishIndex(videoId);
            aggregationWorkPublisher.enqueue(videoId, record.getDataHash(), "metadata-immediate-finalized");
            return new MetadataDispatchResult(videoId, true, status, List.of());
        }

        MetadataSearchContext context = toContext(record);
        List<ProviderType> pendingProviders =
                pipelineRunRepository.prepareDispatch(videoId, record.getDataHash(), providers);
        pendingProviders.forEach(provider -> providerTaskPublisher.publish(provider, context));
        log.info("Dispatched metadata provider Valkey tasks: videoId={}, providers={}", videoId, pendingProviders);
        return new MetadataDispatchResult(videoId, true, "DISPATCHED", pendingProviders);
    }

    private String finalizeImmediate(RawVideoEnrichmentRecord record) {
        if (record.hasAuthoritativeId()) {
            repository.updateStatus(record.getId(), "SUCCESS", 0);
            return "SUCCESS";
        }

        int nextRetry = (record.getEnrichmentRetryCount() == null ? 0 : record.getEnrichmentRetryCount()) + 1;
        if (nextRetry >= RawVideoEnrichmentRepository.MAX_ENRICHMENT_RETRIES) {
            repository.updateStatus(record.getId(), "PERMANENT_FAILURE", record.getEnrichmentRetryCount());
            return "PERMANENT_FAILURE";
        }

        repository.updateStatus(record.getId(), "FAILED", nextRetry);
        return "FAILED";
    }

    private List<ProviderType> providersFor(RawVideoEnrichmentRecord record) {
        List<ProviderType> providers = new ArrayList<>();
        if (configValues.doubanEnabled() && !RawVideoEnrichmentRecord.hasText(record.getDoubanId())) {
            providers.add(ProviderType.DOUBAN);
        }
        if (configValues.tmdbEnabled() && !RawVideoEnrichmentRecord.hasText(record.getTmdbId())) {
            providers.add(ProviderType.TMDB);
        }
        if (configValues.rottenTomatoesEnabled() && !RawVideoEnrichmentRecord.hasText(record.getRottenTomatoesId())) {
            providers.add(ProviderType.ROTTEN_TOMATOES);
        }
        return providers;
    }

    private MetadataSearchContext toContext(RawVideoEnrichmentRecord record) {
        return MetadataSearchContext.builder()
                .videoId(record.getId())
                .pipelineVersion(record.getDataHash())
                .title(record.getTitle())
                .aliasTitle(record.getAliasTitle())
                .subtitle(record.getSubtitle())
                .season(record.getSeason())
                .year(record.getYear())
                .categorySlug(record.getCategorySlug())
                .doubanId(record.getDoubanId())
                .tmdbId(record.getTmdbId())
                .imdbId(record.getImdbId())
                .rottenTomatoesId(record.getRottenTomatoesId())
                .build();
    }

    private boolean isStale(String pipelineVersion, String currentDataHash) {
        if (StringUtils.hasText(currentDataHash)) {
            return !StringUtils.hasText(pipelineVersion) || !pipelineVersion.trim().equals(currentDataHash);
        }
        return StringUtils.hasText(pipelineVersion);
    }
}
