package com.prodigalgal.ircs.search.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchRequest;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchResult;
import com.prodigalgal.ircs.search.document.RawVideoSearchDocument;
import com.prodigalgal.ircs.search.document.UnifiedVideoSearchDocument;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

@ExtendWith(MockitoExtension.class)
class AdminVideoSearchServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private SearchHits<RawVideoSearchDocument> searchHits;

    @Mock
    private SearchHit<RawVideoSearchDocument> searchHit;

    @Mock
    private SearchHits<UnifiedVideoSearchDocument> unifiedSearchHits;

    @Test
    void rawSearchUsesStatusFilterSortAndReturnsDeduplicatedIds() {
        UUID id = UUID.randomUUID();
        RawVideoSearchDocument document = new RawVideoSearchDocument();
        document.setId(id);
        when(searchHit.getContent()).thenReturn(document);
        when(searchHits.stream()).thenReturn(List.of(searchHit, searchHit).stream());
        when(searchHits.getTotalHits()).thenReturn(10L);
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(RawVideoSearchDocument.class)))
                .thenReturn(searchHits);

        AdminVideoSearchService service = new AdminVideoSearchService(elasticsearchOperations);
        AdminVideoSearchResult result = service.searchRawIds(rawRequest("PENDING", "AGGREGATED"));

        assertEquals(List.of(id), result.ids());
        assertEquals(10L, result.total());
        assertEquals(AdminVideoSearchResult.TotalRelation.EXACT, result.totalRelation());

        ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).search(queryCaptor.capture(), eq(RawVideoSearchDocument.class));
        NativeQuery query = queryCaptor.getValue();
        assertEquals(20, query.getPageable().getPageSize());
        assertTrue(query.getQuery().isBool());
        assertEquals("normalizationStatus", query.getQuery().bool().filter().getFirst().term().field());
        assertEquals("PENDING", query.getQuery().bool().filter().getFirst().term().value().stringValue());
        assertEquals("aggregationStatus", query.getQuery().bool().filter().get(1).term().field());
        assertEquals("AGGREGATED", query.getQuery().bool().filter().get(1).term().value().stringValue());
    }

    @Test
    void unifiedSearchUsesAreaNameOrCodeAsSingleFilter() {
        when(unifiedSearchHits.stream()).thenReturn(List.<SearchHit<UnifiedVideoSearchDocument>>of().stream());
        when(unifiedSearchHits.getTotalHits()).thenReturn(0L);
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class)))
                .thenReturn(unifiedSearchHits);

        AdminVideoSearchService service = new AdminVideoSearchService(elasticsearchOperations);
        service.searchUnifiedIds(unifiedRequest("SYNCED", "CN"));

        ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).search(queryCaptor.capture(), eq(UnifiedVideoSearchDocument.class));
        List<Query> filters = queryCaptor.getValue().getQuery().bool().filter();
        Query statusFilter = filters.stream()
                .filter(Query::isTerm)
                .filter(filter -> "metadataStatus".equals(filter.term().field()))
                .findFirst()
                .orElseThrow();
        assertEquals("metadataStatus", statusFilter.term().field());
        assertEquals("SYNCED", statusFilter.term().value().stringValue());
        Query areaFilter = filters.stream()
                .filter(Query::isBool)
                .filter(filter -> filter.bool().should().stream()
                        .anyMatch(should -> should.isTerm() && "areaCodes".equals(should.term().field())))
                .findFirst()
                .orElseThrow();
        assertTrue(areaFilter.isBool());
        assertEquals("1", areaFilter.bool().minimumShouldMatch());
        assertEquals("areas", areaFilter.bool().should().get(0).term().field());
        assertEquals("areaCodes", areaFilter.bool().should().get(1).term().field());
    }

    private AdminVideoSearchRequest rawRequest(String normalizationStatus, String aggregationStatus) {
        return new AdminVideoSearchRequest(
                0,
                20,
                "updatedAt",
                "DESC",
                null,
                null,
                null,
                null,
                normalizationStatus,
                aggregationStatus,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private AdminVideoSearchRequest unifiedRequest(String metadataStatus, String area) {
        return new AdminVideoSearchRequest(
                0,
                20,
                "updatedAt",
                "DESC",
                null,
                null,
                null,
                null,
                null,
                null,
                metadataStatus,
                null,
                area,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
