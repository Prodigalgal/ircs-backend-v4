package com.prodigalgal.ircs.content.video.application;



import com.prodigalgal.ircs.content.video.messaging.ContentCommandPublisher;
import com.prodigalgal.ircs.content.video.infrastructure.JdbcVideoAdminRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.content.maintenance.application.ContentMaintenanceGate;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import com.prodigalgal.ircs.contracts.trend.TrendItemPayload;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TrendSyncContentServiceTest {

    private final JdbcVideoAdminRepository repository = org.mockito.Mockito.mock(JdbcVideoAdminRepository.class);
    private final ContentCommandPublisher publisher = org.mockito.Mockito.mock(ContentCommandPublisher.class);
    private final ContentMaintenanceGate maintenanceGate = org.mockito.Mockito.mock(ContentMaintenanceGate.class);
    private TrendSyncContentService service;

    @BeforeEach
    void setUp() {
        service = new TrendSyncContentService(repository, publisher, maintenanceGate);
        ReflectionTestUtils.setField(service, "titleSimilarityThreshold", 0.4d);
    }

    @Test
    void appliesV1TrendSyncOrderAndPublishesUnifiedSearchSync() {
        UUID externalId = UUID.randomUUID();
        UUID titleMatchId = UUID.randomUUID();
        UUID createdId = UUID.randomUUID();
        TrendItemPayload existingDouban = item("D1 Title", "2026", "d-1", null);
        TrendItemPayload duplicateDouban = item("D1 Duplicate", "2026", "d-1", null);
        TrendItemPayload newTmdb = item("T New", "2025", null, "9001");
        TrendItemPayload titleOnly = item("Title Match", "2024", null, null);

        when(repository.updateTrendTimeByDoubanIds(anyCollection(), any())).thenReturn(List.of(externalId));
        when(repository.updateTrendTimeByTmdbIds(anyCollection(), any())).thenReturn(List.of());
        when(repository.findExistingDoubanIds(anyCollection())).thenReturn(Set.of("d-1"));
        when(repository.findExistingTmdbIds(anyCollection())).thenReturn(Set.of());
        when(repository.findIdsByYearAndTitleSimilarity(anyList(), eq("Title Match"), anyDouble()))
                .thenReturn(List.of(titleMatchId.toString()));
        when(repository.updateTrendTimeById(eq(titleMatchId), any())).thenReturn(true);
        when(repository.createTrendGhost(eq(newTmdb), any())).thenReturn(createdId);

        var response = service.apply(new TrendSyncApplyRequest(List.of(
                existingDouban,
                duplicateDouban,
                newTmdb,
                titleOnly)));

        assertThat(response.taskName()).isEqualTo("trend-sync");
        assertThat(response.candidates()).isEqualTo(4);
        assertThat(response.updatedByExternalId()).isEqualTo(1);
        assertThat(response.updatedByTitleMatch()).isEqualTo(1);
        assertThat(response.createdGhosts()).isEqualTo(1);
        assertThat(response.skippedDuplicates()).isEqualTo(1);
        assertThat(response.updatedUnifiedVideoIds()).containsExactly(externalId, titleMatchId);
        assertThat(response.createdUnifiedVideoIds()).containsExactly(createdId);
        assertThat(response.discoveryKeywords()).containsExactly("T New");

        verify(maintenanceGate).assertUnifiedVideoWrite(null);
        verify(repository).updateTrendTimeByDoubanIds(anyCollection(), any());
        verify(repository).updateTrendTimeByTmdbIds(anyCollection(), any());
        verify(repository).findIdsByYearAndTitleSimilarity(List.of("2024"), "Title Match", 0.4d);
        verify(repository).updateTrendTimeById(eq(titleMatchId), any());
        verify(repository).createTrendGhost(eq(newTmdb), any());
        verify(publisher).publishUnifiedSearch(externalId, SyncOperation.INDEX);
        verify(publisher).publishUnifiedSearch(titleMatchId, SyncOperation.INDEX);
        verify(publisher).publishUnifiedSearch(createdId, SyncOperation.INDEX);
    }

    private TrendItemPayload item(String title, String year, String doubanId, String tmdbId) {
        return new TrendItemPayload(
                title,
                null,
                "desc",
                year,
                null,
                BigDecimal.valueOf(8.1),
                null,
                tmdbId,
                doubanId,
                null,
                "movie");
    }
}
