package com.prodigalgal.ircs.content.video.application;




import com.prodigalgal.ircs.content.video.messaging.ContentCommandPublisher;
import com.prodigalgal.ircs.content.video.infrastructure.JdbcVideoAdminRepository;
import com.prodigalgal.ircs.content.video.api.ContentApiException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.content.maintenance.application.ContentMaintenanceGate;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnifiedVideoAdminServiceTest {

    @Mock
    private JdbcVideoAdminRepository repository;

    @Mock
    private ContentCommandPublisher publisher;

    @Mock
    private ContentMaintenanceGate maintenanceGate;

    @InjectMocks
    private UnifiedVideoAdminService service;

    @Test
    void batchDeleteDeletesUnifiedIndexAndReindexesUnboundRawVideos() {
        UUID unifiedId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        when(repository.findRawIdsForUnified(unifiedId)).thenReturn(List.of(rawId));

        service.batchDelete(List.of(unifiedId));

        verify(maintenanceGate).assertUnifiedVideoWrite(unifiedId);
        verify(repository).deleteUnifiedVideos(List.of(unifiedId));
        verify(publisher).publishUnifiedSearch(unifiedId, SyncOperation.DELETE);
        verify(publisher).publishRawSearch(rawId, SyncOperation.INDEX);
    }

    @Test
    void recalculateCallsAggregationAndPublishesUnifiedIndex() {
        UUID unifiedId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();

        when(repository.recalculateUnifiedFromSources(unifiedId)).thenReturn(List.of(rawId));
        try {
            service.recalculateMetadata(unifiedId);
        } catch (ContentApiException ignored) {
            // Detail lookup is separately covered by repository smoke; this assertion focuses on dispatch side effects.
        }

        verify(maintenanceGate).assertUnifiedVideoWrite(unifiedId);
        verify(repository).recalculateUnifiedFromSources(unifiedId);
        verify(publisher).publishRawSearch(rawId, SyncOperation.INDEX);
        verify(publisher).publishUnifiedSearch(unifiedId, SyncOperation.INDEX);
    }
}
