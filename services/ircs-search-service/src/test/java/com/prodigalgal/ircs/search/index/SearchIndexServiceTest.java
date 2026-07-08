package com.prodigalgal.ircs.search.index;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.search.portal.cache.SearchPortalReadModelCache;
import com.prodigalgal.ircs.search.document.AuditEventSearchDocument;
import com.prodigalgal.ircs.search.document.RawVideoSearchDocument;
import com.prodigalgal.ircs.search.document.UnifiedVideoSearchDocument;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.ByQueryResponse;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;

@ExtendWith(MockitoExtension.class)
class SearchIndexServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private IndexOperations indexOperations;

    @Mock
    private SearchPortalReadModelCache readModelCache;

    @Test
    void saveUnifiedEvictsPortalPublicReadModelCache() {
        UnifiedVideoSearchDocument document = new UnifiedVideoSearchDocument();
        when(elasticsearchOperations.indexOps(UnifiedVideoSearchDocument.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);

        SearchIndexService service = new SearchIndexService(elasticsearchOperations, readModelCache);
        service.saveUnified(document);

        verify(elasticsearchOperations).save(document);
        verify(indexOperations).putMapping();
        verify(readModelCache).evictPortalPublicReadModel();
    }

    @Test
    void deleteUnifiedEvictsPortalPublicReadModelCache() {
        UUID id = UUID.randomUUID();
        when(elasticsearchOperations.indexOps(UnifiedVideoSearchDocument.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);

        SearchIndexService service = new SearchIndexService(elasticsearchOperations, readModelCache);
        service.delete(id, SearchEntityType.UNIFIED_VIDEO);

        verify(elasticsearchOperations).delete(id.toString(), UnifiedVideoSearchDocument.class);
        verify(readModelCache).evictPortalPublicReadModel();
    }

    @Test
    void deleteRawDoesNotEvictPortalPublicReadModelCache() {
        UUID id = UUID.randomUUID();
        when(elasticsearchOperations.indexOps(RawVideoSearchDocument.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);

        SearchIndexService service = new SearchIndexService(elasticsearchOperations, readModelCache);
        service.delete(id, SearchEntityType.RAW_VIDEO);

        verify(elasticsearchOperations).delete(id.toString(), RawVideoSearchDocument.class);
        verify(readModelCache, never()).evictPortalPublicReadModel();
    }

    @Test
    void deleteAuditOlderThanUsesAuditIndexAndReturnsDeletedCount() {
        Instant cutoff = Instant.parse("2026-05-21T00:00:00Z");
        when(elasticsearchOperations.indexOps(AuditEventSearchDocument.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);
        when(elasticsearchOperations.delete(any(DeleteQuery.class), eq(AuditEventSearchDocument.class)))
                .thenReturn(ByQueryResponse.builder().withDeleted(7L).build());

        SearchIndexService service = new SearchIndexService(elasticsearchOperations, readModelCache);
        long deleted = service.deleteAuditOlderThan(cutoff);

        org.junit.jupiter.api.Assertions.assertEquals(7L, deleted);
        verify(elasticsearchOperations).delete(any(DeleteQuery.class), eq(AuditEventSearchDocument.class));
        verify(readModelCache, never()).evictPortalPublicReadModel();
    }

    @Test
    void hardResetUnifiedDeletesRecreatesIndexAndEvictsPortalCache() {
        when(elasticsearchOperations.indexOps(UnifiedVideoSearchDocument.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);
        when(indexOperations.createWithMapping()).thenReturn(true);

        SearchIndexService service = new SearchIndexService(elasticsearchOperations, readModelCache);
        boolean recreated = service.hardReset(SearchEntityType.UNIFIED_VIDEO);

        org.junit.jupiter.api.Assertions.assertTrue(recreated);
        verify(indexOperations).delete();
        verify(indexOperations).createWithMapping();
        verify(readModelCache).evictPortalPublicReadModel();
    }

    @Test
    void hardResetRawDoesNotEvictPortalCache() {
        when(elasticsearchOperations.indexOps(RawVideoSearchDocument.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(false);
        when(indexOperations.createWithMapping()).thenReturn(true);

        SearchIndexService service = new SearchIndexService(elasticsearchOperations, readModelCache);
        boolean recreated = service.hardReset(SearchEntityType.RAW_VIDEO);

        org.junit.jupiter.api.Assertions.assertTrue(recreated);
        verify(indexOperations, never()).delete();
        verify(indexOperations).createWithMapping();
        verify(readModelCache, never()).evictPortalPublicReadModel();
    }
}
