package com.prodigalgal.ircs.search.portal.application;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.search.document.UnifiedVideoSearchDocument;
import com.prodigalgal.ircs.search.portal.cache.SearchPortalReadModelCache;
import com.prodigalgal.ircs.search.portal.dto.PortalMovieCardResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class PortalSearchQueryService {

    private static final int MAX_SUGGEST_LENGTH = 64;
    private static final int MAX_KEYWORD_LENGTH = 128;
    private static final int CONTEXT_CANDIDATE_LIMIT = 20;
    private static final int DESCRIPTION_LIMIT = 200;

    private final ElasticsearchOperations elasticsearchOperations;
    private final SearchPortalReadModelCache readModelCache;
    public PortalSearchQueryService(
            ElasticsearchOperations elasticsearchOperations,
            SearchPortalReadModelCache readModelCache) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.readModelCache = readModelCache;
    }

    public List<String> suggest(String keyword) {
        return suggest(keyword, IrcsRequestPrincipal.publicPrincipal());
    }

    public List<String> suggest(String keyword, IrcsRequestPrincipal principal) {
        String safeKeyword = sanitize(keyword, MAX_SUGGEST_LENGTH);
        if (safeKeyword == null) {
            return List.of();
        }
        return readModelCache.suggest(principal, safeKeyword, () -> suggestUncached(safeKeyword, principal));
    }

    private List<String> suggestUncached(String safeKeyword, IrcsRequestPrincipal principal) {
        try {
            SearchHits<UnifiedVideoSearchDocument> searchHits = elasticsearchOperations.search(
                    buildSuggestQuery(safeKeyword, principal),
                    UnifiedVideoSearchDocument.class);
            return searchHits.stream()
                    .map(SearchHit::getContent)
                    .map(UnifiedVideoSearchDocument::getTitle)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .limit(10)
                    .collect(Collectors.toList());
        } catch (RuntimeException ex) {
            log.warn("Portal search suggest failed, returning empty suggestions: {}", ex.getMessage());
            return List.of();
        }
    }

    public Page<PortalMovieCardResponse> recommend(UUID videoId, Pageable pageable) {
        return recommend(videoId, pageable, IrcsRequestPrincipal.publicPrincipal());
    }

    public Page<PortalMovieCardResponse> recommend(UUID videoId, Pageable pageable, IrcsRequestPrincipal principal) {
        if (videoId == null) {
            return Page.empty(pageable);
        }
        return readModelCache.recommend(principal, videoId, pageable, () -> recommendUncached(videoId, pageable, principal));
    }

    private Page<PortalMovieCardResponse> recommendUncached(UUID videoId, Pageable pageable, IrcsRequestPrincipal principal) {
        try {
            SearchHits<UnifiedVideoSearchDocument> hits = elasticsearchOperations.search(
                    buildRecommendQuery(videoId, pageable, principal),
                    UnifiedVideoSearchDocument.class);
            List<PortalMovieCardResponse> content = hits.stream()
                    .map(SearchHit::getContent)
                    .map(this::toPortalCard)
                    .toList();
            return new PageImpl<>(content, pageable, hits.getTotalHits());
        } catch (RuntimeException ex) {
            log.warn("Portal search recommendations failed for {}, returning empty page: {}", videoId, ex.getMessage());
            return Page.empty(pageable);
        }
    }

    public List<UUID> findCandidateUnifiedVideoIds(String title, String year) {
        String safeTitle = sanitize(title, MAX_KEYWORD_LENGTH);
        if (safeTitle == null) {
            return List.of();
        }
        try {
            SearchHits<UnifiedVideoSearchDocument> hits = elasticsearchOperations.search(
                    buildCandidateQuery(safeTitle, year),
                    UnifiedVideoSearchDocument.class);
            LinkedHashSet<UUID> ids = new LinkedHashSet<>();
            hits.stream()
                    .map(SearchHit::getContent)
                    .map(UnifiedVideoSearchDocument::getId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(ids::add);
            return List.copyOf(ids);
        } catch (RuntimeException ex) {
            log.warn("Search context candidate recall failed, returning empty IDs: {}", ex.getMessage());
            return List.of();
        }
    }

    private NativeQuery buildSuggestQuery(String keyword, IrcsRequestPrincipal principal) {
        Query textQuery = MultiMatchQuery.of(m -> m
                .fields(
                        "title^5",
                        "title.keyword^8",
                        "title.pinyin^4",
                        "normalizedTitle^5",
                        "aliasTitle^3",
                        "normalizedAliasTitle^3",
                        "titleVariants^3",
                        "genres",
                        "tags",
                        "actors",
                        "directors")
                .query(keyword)
                .type(TextQueryType.BestFields)
                .operator(Operator.And))._toQuery();
        return NativeQuery.builder()
                .withQuery(scopedQuery(textQuery, principal))
                .withMaxResults(10)
                .build();
    }

    private NativeQuery buildRecommendQuery(UUID videoId, Pageable pageable, IrcsRequestPrincipal principal) {
        Query recommendQuery = Query.of(q -> q.moreLikeThis(mlt -> mlt
                        .fields("title", "genres", "tags", "actors", "directors", "description")
                .like(l -> l.document(d -> d
                        .index(UnifiedVideoSearchDocument.INDEX_NAME)
                        .id(videoId.toString())))
                .minTermFreq(1)
                .minDocFreq(1)
                .maxQueryTerms(12)));
        return NativeQuery.builder()
                .withQuery(scopedQuery(recommendQuery, principal))
                .withPageable(pageable)
                .withTrackTotalHits(false)
                .build();
    }

    private NativeQuery buildCandidateQuery(String title, String year) {
        List<Query> must = new ArrayList<>();
        must.add(MultiMatchQuery.of(m -> m
                .fields(
                        "title^5",
                        "normalizedTitle^5",
                        "aliasTitle^3",
                        "normalizedAliasTitle^3",
                        "titleVariants^3",
                        "externalIds^8")
                .query(title)
                .type(TextQueryType.BestFields)
                .operator(Operator.And)
                .minimumShouldMatch("90%"))._toQuery());

        if (StringUtils.hasText(year)) {
            try {
                int parsedYear = Integer.parseInt(year.replaceAll("\\D", ""));
                must.add(RangeQuery.of(r -> r
                                .number(n -> n
                                        .field("year")
                                        .gte((double) (parsedYear - 2))
                                        .lte((double) (parsedYear + 2))))
                        ._toQuery());
            } catch (RuntimeException ignored) {
            }
        }

        return NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b.must(must)))
                .withMaxResults(CONTEXT_CANDIDATE_LIMIT)
                .build();
    }

    private Query scopedQuery(Query query, IrcsRequestPrincipal principal) {
        List<Query> filters = scopeFilters(principal);
        if (filters.isEmpty()) {
            return query;
        }
        return Query.of(q -> q.bool(b -> b
                .must(query)
                .filter(filters)));
    }

    private List<Query> scopeFilters(IrcsRequestPrincipal principal) {
        IrcsRequestPrincipal effective = principal == null ? IrcsRequestPrincipal.publicPrincipal() : principal;
        List<Query> filters = new ArrayList<>();
        if (!effective.hasUnrestrictedVisibility()) {
            filters.add(termsFilter("contentVisibility", effective.contentVisibility()));
        }
        if (!effective.hasUnrestrictedCategories()) {
            List<Query> categoryFilters = List.of(
                    termsFilter("categoryId", effective.dataCategories()),
                    termsFilter("categoryName", effective.dataCategories()),
                    termsFilter("categorySlug", effective.dataCategories()));
            filters.add(Query.of(q -> q.bool(b -> b
                    .should(categoryFilters)
                    .minimumShouldMatch("1"))));
        }
        if (!effective.allowsAdultRestrictedContent()) {
            filters.add(falseOrMissingFilter("adultRestricted"));
            filters.add(falseOrMissingFilter("adultAssessmentRestricted"));
            filters.add(termsOrMissingFilter("adultAssessmentLevel", List.of("SAFE", "SUSPECT")));
        }
        if (!effective.hasUnrestrictedGenres()) {
            filters.add(termsFilter("genres", effective.dataGenres()));
        }
        if (!effective.hasUnrestrictedTags()) {
            filters.add(termsFilter("tags", effective.dataTags()));
        }
        return filters;
    }

    private Query termsFilter(String field, Collection<String> values) {
        List<FieldValue> fieldValues = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(FieldValue::of)
                .toList();
        return Query.of(q -> q.terms(t -> t
                .field(field)
                .terms(TermsQueryField.of(v -> v.value(fieldValues)))));
    }

    private Query falseOrMissingFilter(String field) {
        return Query.of(q -> q.bool(b -> b
                .should(Query.of(s -> s.term(t -> t
                        .field(field)
                        .value(FieldValue.of(false)))))
                .should(missingFieldFilter(field))
                .minimumShouldMatch("1")));
    }

    private Query termsOrMissingFilter(String field, Collection<String> values) {
        return Query.of(q -> q.bool(b -> b
                .should(termsFilter(field, values))
                .should(missingFieldFilter(field))
                .minimumShouldMatch("1")));
    }

    private Query missingFieldFilter(String field) {
        return Query.of(q -> q.bool(b -> b
                .mustNot(Query.of(n -> n.exists(e -> e.field(field))))));
    }

    private PortalMovieCardResponse toPortalCard(UnifiedVideoSearchDocument doc) {
        return new PortalMovieCardResponse(
                doc.getId(),
                doc.getTitle(),
                doc.getAliasTitle(),
                doc.getSeason(),
                doc.getSubtitle(),
                doc.getCoverImageUrl(),
                doc.getScore() == null ? BigDecimal.ZERO : BigDecimal.valueOf(doc.getScore()),
                doc.getYear() == null ? "" : String.valueOf(doc.getYear()),
                doc.getCategoryName(),
                doc.getTotalEpisodes(),
                doc.getDuration(),
                doc.getRemarks(),
                join(doc.getAreas()),
                limitDescription(doc.getDescription()),
                doc.getGenres() == null ? List.of() : doc.getGenres(),
                doc.getLastTrendAt());
    }

    private String sanitize(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            trimmed = trimmed.substring(0, maxLength);
        }
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(", ", values);
    }

    private String limitDescription(String description) {
        if (description == null || description.length() <= DESCRIPTION_LIMIT) {
            return description;
        }
        return description.substring(0, DESCRIPTION_LIMIT);
    }
}
