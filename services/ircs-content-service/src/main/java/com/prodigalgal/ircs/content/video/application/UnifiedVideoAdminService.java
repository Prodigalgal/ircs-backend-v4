package com.prodigalgal.ircs.content.video.application;




import com.prodigalgal.ircs.content.video.messaging.ContentCommandPublisher;
import com.prodigalgal.ircs.content.video.infrastructure.JdbcVideoAdminRepository;
import com.prodigalgal.ircs.content.video.api.ContentApiException;
import com.prodigalgal.ircs.content.maintenance.application.ContentMaintenanceGate;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoCardResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoCreateRequest;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoDetailResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoUpdateRequest;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

@Service
@RequiredArgsConstructor
public class UnifiedVideoAdminService {

    private final JdbcVideoAdminRepository repository;
    private final ContentCommandPublisher publisher;
    private final ContentMaintenanceGate maintenanceGate;

    public Page<UnifiedVideoCardResponse> findAll(
            Pageable pageable,
            String title,
            UUID categoryId,
            String year,
            String area,
            BigDecimal minScore,
            Boolean hasDoubanId,
            Boolean hasTmdbId,
            String contentVisibility,
            String metadataStatus,
            String genre,
            String language,
            String actor,
            String director) {
        return repository.findUnifiedVideos(
                pageable,
                title,
                categoryId,
                year,
                area,
                minScore,
                hasDoubanId,
                hasTmdbId,
                contentVisibility,
                metadataStatus,
                genre,
                language,
                actor,
                director);
    }

    public Optional<UnifiedVideoDetailResponse> findOne(UUID id) {
        return repository.findUnifiedDetail(id);
    }

    @Transactional
    public UnifiedVideoDetailResponse create(UnifiedVideoCreateRequest request) {
        maintenanceGate.assertUnifiedVideoWrite(null);
        UUID id = repository.createUnifiedVideo(request);
        publisher.publishUnifiedSearch(id, SyncOperation.INDEX);
        if (!CollectionUtils.isEmpty(request.bindVideoIds())) {
            request.bindVideoIds().forEach(rawId -> publisher.publishRawSearch(rawId, SyncOperation.INDEX));
        }
        return repository.findUnifiedDetail(id)
                .orElseThrow(() -> new ContentApiException(HttpStatus.NOT_FOUND, "Unified video not found: " + id));
    }

    @Transactional
    public UnifiedVideoDetailResponse update(UUID id, UnifiedVideoUpdateRequest request) {
        maintenanceGate.assertUnifiedVideoWrite(id);
        repository.updateUnifiedVideo(id, request);
        publisher.publishUnifiedSearch(id, SyncOperation.INDEX);
        if (!CollectionUtils.isEmpty(request.bindVideoIds())) {
            request.bindVideoIds().forEach(rawId -> publisher.publishRawSearch(rawId, SyncOperation.INDEX));
        }
        if (!CollectionUtils.isEmpty(request.unbindVideoIds())) {
            request.unbindVideoIds().forEach(rawId -> publisher.publishRawSearch(rawId, SyncOperation.INDEX));
        }
        return repository.findUnifiedDetail(id)
                .orElseThrow(() -> new ContentApiException(HttpStatus.NOT_FOUND, "Unified video not found: " + id));
    }

    @Transactional
    public void delete(UUID id) {
        batchDelete(List.of(id));
    }

    @Transactional
    public void batchDelete(List<UUID> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        ids.forEach(maintenanceGate::assertUnifiedVideoWrite);
        List<UUID> rawIds = new ArrayList<>();
        ids.forEach(id -> rawIds.addAll(repository.findRawIdsForUnified(id)));
        repository.deleteUnifiedVideos(ids);
        ids.forEach(id -> publisher.publishUnifiedSearch(id, SyncOperation.DELETE));
        rawIds.forEach(id -> publisher.publishRawSearch(id, SyncOperation.INDEX));
    }

    public void batchSyncSearch(List<UUID> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        ids.forEach(id -> publisher.publishUnifiedSearch(id, SyncOperation.INDEX));
    }

    @Transactional
    public UnifiedVideoDetailResponse recalculateMetadata(UUID id) {
        maintenanceGate.assertUnifiedVideoWrite(id);
        List<UUID> rawIds = repository.recalculateUnifiedFromSources(id);
        rawIds.forEach(rawId -> publisher.publishRawSearch(rawId, SyncOperation.INDEX));
        publisher.publishUnifiedSearch(id, SyncOperation.INDEX);
        return repository.findUnifiedDetail(id)
                .orElseThrow(() -> new ContentApiException(HttpStatus.NOT_FOUND, "Unified video not found: " + id));
    }
}
