package com.prodigalgal.ircs.search.admin.application;

import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchRequest;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchResult;
import com.prodigalgal.ircs.search.document.RawVideoSearchDocument;
import com.prodigalgal.ircs.search.document.UnifiedVideoSearchDocument;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdminVideoSearchService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ElasticsearchOperations elasticsearchOperations;

    public AdminVideoSearchService(ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchOperations = elasticsearchOperations;
    }

    public AdminVideoSearchResult searchRawIds(AdminVideoSearchRequest request) {
        Instant started = Instant.now();
        SearchHits<RawVideoSearchDocument> hits = elasticsearchOperations.search(
                rawQuery(request),
                RawVideoSearchDocument.class);
        return toResult(rawIds(hits), hits, started);
    }

    public AdminVideoSearchResult searchUnifiedIds(AdminVideoSearchRequest request) {
        Instant started = Instant.now();
        SearchHits<UnifiedVideoSearchDocument> hits = elasticsearchOperations.search(
                unifiedQuery(request),
                UnifiedVideoSearchDocument.class);
        return toResult(unifiedIds(hits), hits, started);
    }

    private NativeQuery rawQuery(AdminVideoSearchRequest request) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        addText(bool, request.title(), List.of(
                "title^5",
                "title.keyword^8",
                "normalizedTitle^5",
                "aliasTitle^3",
                "normalizedAliasTitle^3",
                "titleVariants^3",
                "sourceVid^4",
                "externalIds^8",
                "metadata"));
        addTerm(bool, "categoryId", request.categoryId());
        addTerm(bool, "categoryCode", request.categoryCode());
        addTerm(bool, "enrichmentStatus", request.enrichmentStatus());
        addTerm(bool, "normalizationStatus", request.normalizationStatus());
        addTerm(bool, "aggregationStatus", request.aggregationStatus());
        addTerm(bool, "year", parseRawYear(request.year()));
        addTerm(bool, "dataSourceId", request.dataSourceId());
        addText(bool, request.sourceCategoryName(), List.of("sourceCategoryName"));
        addTerm(bool, "rawAreas", request.area());
        addTerm(bool, "rawGenres", request.genre());
        addTerm(bool, "rawLanguages", request.language());
        addTerm(bool, "isMissingSlug", request.isMissingSlug());
        addMinScore(bool, request.minScore());
        return buildQuery(bool, request, "updatedAt");
    }

    private NativeQuery unifiedQuery(AdminVideoSearchRequest request) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        addText(bool, request.title(), List.of(
                "title^5",
                "title.keyword^8",
                "title.pinyin^4",
                "normalizedTitle^5",
                "aliasTitle^3",
                "normalizedAliasTitle^3",
                "titleVariants^3",
                "externalIds^8",
                "description"));
        addTerm(bool, "categoryId", request.categoryId());
        addTerm(bool, "categorySlug", request.categoryCode());
        addTerm(bool, "year", parseUnifiedYear(request.year()));
        addAnyTerm(bool, request.area(), List.of("areas", "areaCodes"));
        addAnyTerm(bool, request.genre(), List.of("genres", "genreCodes"));
        addAnyTerm(bool, request.language(), List.of("languages", "languageCodes"));
        addText(bool, request.actor(), List.of("actors", "actors.keyword"));
        addText(bool, request.director(), List.of("directors", "directors.keyword"));
        addTerm(bool, "hasDouban", request.hasDoubanId());
        addTerm(bool, "hasTmdb", request.hasTmdbId());
        addTerm(bool, "contentVisibility", request.contentVisibility());
        addTerm(bool, "metadataStatus", request.metadataStatus());
        addMinScore(bool, request.minScore());
        return buildQuery(bool, request, "updatedAt");
    }

    private NativeQuery buildQuery(BoolQuery.Builder bool, AdminVideoSearchRequest request, String defaultSort) {
        String sortField = sortField(request.sort(), defaultSort);
        SortOrder direction = "asc".equalsIgnoreCase(request.direction()) ? SortOrder.Asc : SortOrder.Desc;
        Pageable pageable = PageRequest.of(Math.max(0, request.page()), Math.min(Math.max(1, request.size()), MAX_PAGE_SIZE));
        return NativeQuery.builder()
                .withQuery(bool.build()._toQuery())
                .withPageable(pageable)
                .withSort(s -> s.field(FieldSort.of(f -> f.field(sortField).order(direction))))
                .withTrackTotalHits(true)
                .build();
    }

    private void addText(BoolQuery.Builder bool, String value, List<String> fields) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        bool.must(MultiMatchQuery.of(m -> m
                .fields(fields)
                .query(value.trim())
                .type(TextQueryType.BestFields)
                .operator(Operator.And))._toQuery());
    }

    private void addTerm(BoolQuery.Builder bool, String field, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && !StringUtils.hasText(text)) {
            return;
        }
        bool.filter(Query.of(q -> q.term(t -> t.field(field).value(fieldValue(value)))));
    }

    private void addAnyTerm(BoolQuery.Builder bool, Object value, List<String> fields) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && !StringUtils.hasText(text)) {
            return;
        }
        bool.filter(Query.of(q -> q.bool(any -> {
            fields.forEach(field -> any.should(Query.of(
                    should -> should.term(term -> term.field(field).value(fieldValue(value))))));
            return any.minimumShouldMatch("1");
        })));
    }

    private FieldValue fieldValue(Object value) {
        if (value instanceof Boolean bool) {
            return FieldValue.of(bool);
        }
        if (value instanceof Number number) {
            return FieldValue.of(number.doubleValue());
        }
        return FieldValue.of(value.toString().trim());
    }

    private void addMinScore(BoolQuery.Builder bool, java.math.BigDecimal minScore) {
        if (minScore == null) {
            return;
        }
        bool.filter(Query.of(q -> q.range(r -> r.number(n -> n.field("score").gte(minScore.doubleValue())))));
    }

    private String parseRawYear(String year) {
        if (!StringUtils.hasText(year)) {
            return null;
        }
        return year.trim();
    }

    private Integer parseUnifiedYear(String year) {
        if (!StringUtils.hasText(year)) {
            return null;
        }
        String digits = year.replaceAll("\\D", "");
        if (!StringUtils.hasText(digits)) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String sortField(String requested, String defaultSort) {
        if (!StringUtils.hasText(requested)) {
            return defaultSort;
        }
        return switch (requested.trim().toLowerCase(Locale.ROOT)) {
            case "createdat", "created_at" -> "createdAt";
            case "updatedat", "updated_at" -> "updatedAt";
            case "title" -> "title.keyword";
            case "year" -> "year";
            case "score" -> "score";
            default -> defaultSort;
        };
    }

    private List<UUID> rawIds(SearchHits<RawVideoSearchDocument> hits) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        hits.stream()
                .map(SearchHit::getContent)
                .map(RawVideoSearchDocument::getId)
                .filter(java.util.Objects::nonNull)
                .forEach(ids::add);
        return new ArrayList<>(ids);
    }

    private List<UUID> unifiedIds(SearchHits<UnifiedVideoSearchDocument> hits) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        hits.stream()
                .map(SearchHit::getContent)
                .map(UnifiedVideoSearchDocument::getId)
                .filter(java.util.Objects::nonNull)
                .forEach(ids::add);
        return new ArrayList<>(ids);
    }

    private AdminVideoSearchResult toResult(List<UUID> ids, SearchHits<?> hits, Instant started) {
        long total = Math.max(hits.getTotalHits(), ids.size());
        return new AdminVideoSearchResult(
                ids,
                total,
                AdminVideoSearchResult.TotalRelation.EXACT,
                Duration.between(started, Instant.now()).toMillis(),
                "elasticsearch");
    }
}
