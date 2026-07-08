package com.prodigalgal.ircs.search.portal.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import com.prodigalgal.ircs.common.cache.TieredStringCacheStore;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.search.document.UnifiedVideoSearchDocument;
import com.prodigalgal.ircs.search.portal.cache.SearchPortalReadModelCache;
import com.prodigalgal.ircs.search.portal.dto.PortalMovieCardResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

@ExtendWith(MockitoExtension.class)
class PortalSearchQueryServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private SearchHits<UnifiedVideoSearchDocument> searchHits;

    @Mock
    private SearchHit<UnifiedVideoSearchDocument> searchHit;

    @Test
    void blankSuggestReturnsEmptyWithoutCallingElasticsearch() {
        PortalSearchQueryService service = new PortalSearchQueryService(
                elasticsearchOperations,
                SearchPortalReadModelCache.disabled());

        assertEquals(List.of(), service.suggest("   "));

        verify(elasticsearchOperations, never()).search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class));
    }

    @Test
    void suggestCachesAnonymousPublicReads() {
        UnifiedVideoSearchDocument document = new UnifiedVideoSearchDocument();
        document.setTitle("Codex Movie");

        when(searchHit.getContent()).thenReturn(document);
        when(searchHits.stream()).thenReturn(List.of(searchHit).stream());
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class)))
                .thenReturn(searchHits);

        PortalSearchQueryService service = cachedService();

        assertEquals(List.of("Codex Movie"), service.suggest("codex", IrcsRequestPrincipal.publicPrincipal()));
        assertEquals(List.of("Codex Movie"), service.suggest("codex", IrcsRequestPrincipal.publicPrincipal()));
        verify(elasticsearchOperations, times(1)).search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class));
    }

    @Test
    void suggestBypassesCacheForScopedMemberReads() {
        UnifiedVideoSearchDocument document = new UnifiedVideoSearchDocument();
        document.setTitle("Codex Movie");
        IrcsRequestPrincipal principal = memberPrincipal();

        when(searchHit.getContent()).thenReturn(document);
        when(searchHits.stream()).thenReturn(
                List.of(searchHit).stream(),
                List.of(searchHit).stream());
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class)))
                .thenReturn(searchHits);

        PortalSearchQueryService service = cachedService();

        assertEquals(List.of("Codex Movie"), service.suggest("codex", principal));
        assertEquals(List.of("Codex Movie"), service.suggest("codex", principal));
        verify(elasticsearchOperations, times(2)).search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class));
    }

    @Test
    void recommendMapsUnifiedDocumentsToPortalCards() {
        UUID id = UUID.randomUUID();
        UnifiedVideoSearchDocument document = new UnifiedVideoSearchDocument();
        document.setId(id);
        document.setTitle("Codex Movie");
        document.setAliasTitle("Codex Alias");
        document.setScore(8.6);
        document.setYear(2026);
        document.setCategoryName("电影");
        document.setAreas(List.of("中国大陆"));
        document.setGenres(List.of("剧情", "科幻"));
        document.setDescription("简介");
        document.setLastTrendAt(Instant.parse("2026-06-04T00:00:00Z"));

        when(searchHit.getContent()).thenReturn(document);
        when(searchHits.stream()).thenReturn(List.of(searchHit).stream());
        when(searchHits.getTotalHits()).thenReturn(1L);
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class)))
                .thenReturn(searchHits);

        PortalSearchQueryService service = new PortalSearchQueryService(
                elasticsearchOperations,
                SearchPortalReadModelCache.disabled());
        var page = service.recommend(id, PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        PortalMovieCardResponse card = page.getContent().getFirst();
        assertEquals(id, card.id());
        assertEquals("Codex Movie", card.title());
        assertEquals("2026", card.releaseYear());
        assertEquals("中国大陆", card.area());
        assertEquals(List.of("剧情", "科幻"), card.genres());
    }

    @Test
    void recommendCachesAnonymousPublicReads() {
        UUID id = UUID.randomUUID();
        UnifiedVideoSearchDocument document = new UnifiedVideoSearchDocument();
        document.setId(id);
        document.setTitle("Codex Movie");
        document.setYear(2026);

        when(searchHit.getContent()).thenReturn(document);
        when(searchHits.stream()).thenReturn(List.of(searchHit).stream());
        when(searchHits.getTotalHits()).thenReturn(1L);
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class)))
                .thenReturn(searchHits);

        PortalSearchQueryService service = cachedService();

        var first = service.recommend(id, PageRequest.of(0, 10), IrcsRequestPrincipal.publicPrincipal());
        var second = service.recommend(id, PageRequest.of(0, 10), IrcsRequestPrincipal.publicPrincipal());

        assertEquals(1, first.getTotalElements());
        assertEquals(1, second.getTotalElements());
        assertEquals("Codex Movie", second.getContent().getFirst().title());
        verify(elasticsearchOperations, times(1)).search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class));
    }

    @Test
    void recommendReturnsEmptyPageWhenElasticsearchFails() {
        UUID id = UUID.randomUUID();
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class)))
                .thenThrow(new IllegalStateException("index missing"));

        PortalSearchQueryService service = new PortalSearchQueryService(
                elasticsearchOperations,
                SearchPortalReadModelCache.disabled());
        var page = service.recommend(id, PageRequest.of(0, 10));

        assertEquals(0, page.getTotalElements());
    }

    @Test
    void recommendAddsVisibilityAndDataScopeFilters() {
        UUID id = UUID.randomUUID();
        IrcsRequestPrincipal principal = memberPrincipal();
        when(searchHits.stream()).thenReturn(List.<SearchHit<UnifiedVideoSearchDocument>>of().stream());
        when(searchHits.getTotalHits()).thenReturn(0L);
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class)))
                .thenReturn(searchHits);

        PortalSearchQueryService service = new PortalSearchQueryService(
                elasticsearchOperations,
                SearchPortalReadModelCache.disabled());
        service.recommend(id, PageRequest.of(0, 10), principal);

        ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).search(queryCaptor.capture(), eq(UnifiedVideoSearchDocument.class));
        Query query = queryCaptor.getValue().getQuery();
        assertTrue(query.isBool());
        assertEquals(1, query.bool().must().size());
        assertTrue(query.bool().must().getFirst().isMoreLikeThis());
        assertEquals(6, query.bool().filter().size());
        assertEquals("contentVisibility", query.bool().filter().get(0).terms().field());
        assertEquals(
                List.of("categoryId", "categoryName", "categorySlug"),
                query.bool().filter().get(1).bool().should().stream()
                        .map(filter -> filter.terms().field())
                        .toList());
        assertFalseOrMissingFilter(query.bool().filter().get(2), "adultRestricted");
        assertFalseOrMissingFilter(query.bool().filter().get(3), "adultAssessmentRestricted");
        assertTermsOrMissingFilter(query.bool().filter().get(4), "adultAssessmentLevel");
        assertEquals("genres", query.bool().filter().get(5).terms().field());
    }

    @Test
    void candidateRecallBuildsV1CompatibleQueryAndReturnsDeduplicatedIds() {
        UUID id = UUID.randomUUID();
        UnifiedVideoSearchDocument document = new UnifiedVideoSearchDocument();
        document.setId(id);

        when(searchHit.getContent()).thenReturn(document);
        when(searchHits.stream()).thenReturn(List.of(searchHit, searchHit).stream());
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class)))
                .thenReturn(searchHits);

        PortalSearchQueryService service = new PortalSearchQueryService(
                elasticsearchOperations,
                SearchPortalReadModelCache.disabled());
        List<UUID> result = service.findCandidateUnifiedVideoIds("  Codex Signal  ", "2026 年");

        assertEquals(List.of(id), result);
        ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).search(queryCaptor.capture(), eq(UnifiedVideoSearchDocument.class));
        NativeQuery query = queryCaptor.getValue();
        assertEquals(20, query.getMaxResults());
        assertTrue(query.getQuery().isBool());
        List<Query> must = query.getQuery().bool().must();
        assertEquals(2, must.size());
        assertTrue(must.get(0).isMultiMatch());
        assertEquals(
                List.of(
                        "title^5",
                        "normalizedTitle^5",
                        "aliasTitle^3",
                        "normalizedAliasTitle^3",
                        "titleVariants^3",
                        "externalIds^8"),
                must.get(0).multiMatch().fields());
        assertEquals("Codex Signal", must.get(0).multiMatch().query());
        assertEquals(TextQueryType.BestFields, must.get(0).multiMatch().type());
        assertEquals(Operator.And, must.get(0).multiMatch().operator());
        assertEquals("90%", must.get(0).multiMatch().minimumShouldMatch());
        assertTrue(must.get(1).isRange());
        assertTrue(must.get(1).range().isNumber());
        assertEquals("year", must.get(1).range().number().field());
        assertEquals(2024.0, must.get(1).range().number().gte());
        assertEquals(2028.0, must.get(1).range().number().lte());
    }

    @Test
    void blankCandidateRecallReturnsEmptyWithoutCallingElasticsearch() {
        PortalSearchQueryService service = new PortalSearchQueryService(
                elasticsearchOperations,
                SearchPortalReadModelCache.disabled());

        assertEquals(List.of(), service.findCandidateUnifiedVideoIds("   ", "2026"));

        verify(elasticsearchOperations, never()).search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class));
    }

    @Test
    void candidateRecallReturnsEmptyWhenElasticsearchFails() {
        when(elasticsearchOperations.search(isA(NativeQuery.class), eq(UnifiedVideoSearchDocument.class)))
                .thenThrow(new IllegalStateException("index missing"));

        PortalSearchQueryService service = new PortalSearchQueryService(
                elasticsearchOperations,
                SearchPortalReadModelCache.disabled());

        assertEquals(List.of(), service.findCandidateUnifiedVideoIds("Codex Signal", "bad year"));
    }

    private PortalSearchQueryService cachedService() {
        SearchPortalReadModelCache cache = SearchPortalReadModelCache.forTest(
                JsonMapper.builder().findAndAddModules().build(),
                new CacheRegistry(),
                null,
                null,
                null,
                true,
                Duration.ofSeconds(60),
                Duration.ofSeconds(60));
        return new PortalSearchQueryService(elasticsearchOperations, cache);
    }

    private IrcsRequestPrincipal memberPrincipal() {
        return new IrcsRequestPrincipal(
                "member-1",
                IrcsPermissions.ROLE_MEMBER,
                Set.of(IrcsPermissions.PORTAL_READ),
                Set.of(IrcsPermissions.SCOPE_PORTAL_READ),
                Set.of("movies"),
                Set.of("剧情"),
                Set.of("*"),
                Set.of(IrcsPermissions.VISIBILITY_PUBLIC, IrcsPermissions.VISIBILITY_MEMBER));
    }

    private static void assertFalseOrMissingFilter(Query query, String field) {
        assertTrue(query.isBool());
        assertEquals("1", query.bool().minimumShouldMatch());
        assertEquals(field, query.bool().should().get(0).term().field());
        assertFalse(query.bool().should().get(0).term().value().booleanValue());
        assertMissingFieldFilter(query.bool().should().get(1), field);
    }

    private static void assertTermsOrMissingFilter(Query query, String field) {
        assertTrue(query.isBool());
        assertEquals("1", query.bool().minimumShouldMatch());
        assertEquals(field, query.bool().should().get(0).terms().field());
        assertMissingFieldFilter(query.bool().should().get(1), field);
    }

    private static void assertMissingFieldFilter(Query query, String field) {
        assertTrue(query.isBool());
        assertEquals(1, query.bool().mustNot().size());
        assertEquals(field, query.bool().mustNot().getFirst().exists().field());
    }
}
