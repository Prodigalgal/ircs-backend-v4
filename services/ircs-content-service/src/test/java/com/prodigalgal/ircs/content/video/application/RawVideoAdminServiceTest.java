package com.prodigalgal.ircs.content.video.application;




import com.prodigalgal.ircs.content.video.infrastructure.InternalContentClients;
import com.prodigalgal.ircs.content.video.messaging.ContentCommandPublisher;
import com.prodigalgal.ircs.content.video.infrastructure.JdbcVideoAdminRepository;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.content.maintenance.application.ContentMaintenanceGate;
import com.prodigalgal.ircs.content.video.infrastructure.JdbcVideoAdminRepository.RawVideoSnapshot;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoUpdateRequest;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RawVideoAdminServiceTest {

    @Mock
    private JdbcVideoAdminRepository repository;

    @Mock
    private ContentCommandPublisher publisher;

    @Mock
    private InternalContentClients internalClients;

    @Mock
    private ContentMaintenanceGate maintenanceGate;

    @InjectMocks
    private RawVideoAdminService service;

    @Test
    void updateCriticalFieldUnbindsOldUnifiedAndPublishesIndexes() {
        UUID rawId = UUID.randomUUID();
        UUID oldUnifiedId = UUID.randomUUID();
        UUID newUnifiedId = UUID.randomUUID();
        RawVideoUpdateRequest request = new RawVideoUpdateRequest(
                rawId,       // id
                "新标题",     // title
                null,        // aliasTitle
                null,        // coverImageUrl
                null,        // description
                null,        // subtitle
                null,        // season
                null,        // year
                null,        // remarks
                null,        // score
                null,        // publishedAt
                null,        // totalEpisodes
                null,        // duration
                null,        // doubanId
                null,        // tmdbId
                null,        // imdbId
                null,        // rottenTomatoesId
                null,        // lockedFields
                null,        // dataSourceId
                null,        // categoryId
                newUnifiedId, // unifiedVideoId
                null,        // rawLanguageStr
                null,        // actorNames
                null,        // directorNames
                null,        // areaCodes
                null,        // languageCodes
                null,        // genreCodes
                null,        // categoryCode
                null,        // sourceCategoryCode
                null);       // sourceCategoryName
        when(repository.rawSnapshot(rawId)).thenReturn(
                new RawVideoSnapshot(rawId, "旧标题", null, "2024", 1, "hash-old", oldUnifiedId));

        service.update(rawId, request);

        verify(maintenanceGate).assertRawVideoWrite(rawId);
        verify(repository).markUnifiedDirty(oldUnifiedId);
        verify(repository).unbindRaw(rawId);
        verify(repository).bindRawToUnified(rawId, newUnifiedId);
        verify(publisher).publishRawSearch(rawId, SyncOperation.INDEX);
        verify(publisher).publishUnifiedSearch(newUnifiedId, SyncOperation.INDEX);
    }

    @Test
    void batchDeletePublishesRawDeleteAndMarksBoundUnifiedDirty() {
        UUID rawId = UUID.randomUUID();
        UUID unifiedId = UUID.randomUUID();
        when(repository.findRawUnifiedBindings(List.of(rawId))).thenReturn(List.of(unifiedId));

        service.batchDelete(List.of(rawId));

        verify(maintenanceGate).assertRawVideoWrite(rawId);
        verify(repository).deleteRawVideos(List.of(rawId));
        verify(publisher).publishRawSearch(rawId, SyncOperation.DELETE);
        verify(repository).markUnifiedDirty(unifiedId);
        verify(publisher).publishUnifiedSearch(unifiedId, SyncOperation.INDEX);
    }

    @Test
    void reNormalizeUpdatesStatusAndPublishesQueueMessage() {
        UUID rawId = UUID.randomUUID();
        when(repository.rawSnapshot(rawId)).thenReturn(
                new RawVideoSnapshot(rawId, "title", null, null, null, "hash-current", null));

        service.reNormalize(rawId);

        verify(maintenanceGate).assertRawVideoWrite(rawId);
        verify(repository).setRawNormalizationPending(rawId);
        verify(publisher).publishNormalize(rawId, "hash-current");
    }

    @Test
    void batchRefetchDispatchesInternalClientForEachId() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.rawSnapshot(first)).thenReturn(new RawVideoSnapshot(first, "a", null, null, null, null, null));
        when(repository.rawSnapshot(second)).thenReturn(new RawVideoSnapshot(second, "b", null, null, null, null, null));

        service.batchReFetch(List.of(first, second));

        verify(maintenanceGate).assertRawVideoWrite(first);
        verify(maintenanceGate).assertRawVideoWrite(second);
        verify(internalClients).refetchRawVideo(first);
        verify(internalClients).refetchRawVideo(second);
    }
}
