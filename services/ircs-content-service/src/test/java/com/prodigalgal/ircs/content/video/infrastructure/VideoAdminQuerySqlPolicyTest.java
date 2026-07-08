package com.prodigalgal.ircs.content.video.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VideoAdminQuerySqlPolicyTest {

    @Test
    void rawDetailQueryExposesResolvedCategoryAliasUsedByMapper() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("sc.id as resolved_category_id");
        assertThat(source).contains("rs.getObject(\"resolved_category_id\", UUID.class)");
        assertThat(source).doesNotContain("select rv.*, sc.id as category_id");
    }

    @Test
    void defaultListPaginationUsesCheapTotalsWithExactFallback() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("pg_stat_user_tables");
        assertThat(source).contains("exactCount(exactCountSql, params)");
        assertThat(source).contains("shouldUseExactFilteredCount(pageable, contentSize)");
        assertThat(source).contains("estimatedTotal(pageable, contentSize)");
        assertThat(source).contains("hasAnyDatabaseRow(\"raw_videos rv\", where, params)");
        assertThat(source).contains("hasAnyDatabaseRow(\"unified_videos uv\", where, params)");
        assertThat(source).contains("totalElements(\"raw_videos\"");
        assertThat(source).contains("totalElements(\"unified_videos\"");
    }

    @Test
    void unifiedListLoadsSourceCountsInBatch() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("withSourceCounts(page)");
        assertThat(source).contains("where unified_video_id in (:ids)");
        assertThat(source).doesNotContain("where rvu.unified_video_id = uv.id\n                       ) as source_count");
    }

    @Test
    void categoryFiltersUseCodeSubqueryInsteadOfForcingCountJoins() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("rv.category_code = (select sc.slug from standard_category sc where sc.id = :categoryId)");
        assertThat(source).contains("uv.category_code = (select sc.slug from standard_category sc where sc.id = :categoryId)");
    }

    @Test
    void filteredListsUseSearchIdsAndHydrateCurrentPageFromDatabase() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("adminVideoSearchClient.searchRawIds(request)");
        assertThat(source).contains("adminVideoSearchClient.searchUnifiedIds(request)");
        assertThat(source).contains("findRawCardsByIds(result.ids())");
        assertThat(source).contains("withSourceCounts(findUnifiedCardsByIds(result.ids()))");
        assertThat(source).contains("aggregationStatus,");
        assertThat(source).contains("normalizedMetadataStatus");
        assertThat(source).contains("trimToNull(title)");
        assertThat(source).contains("trimToNull(actor)");
        assertThat(source).contains("trimToNull(director)");
        assertThat(source).doesNotContain(".filter(result -> !result.ids().isEmpty())");
        assertThat(source).doesNotContain("if (StringUtils.hasText(aggregationStatus)) {\n            return Optional.empty();");
        assertThat(source).doesNotContain("if (StringUtils.hasText(metadataStatus)) {\n            return Optional.empty();");
    }

    @Test
    void statusOnlyRawFiltersCanShortCircuitEmptyDatabaseStateBeforeSearch() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("rawStatusOnlyEmptyPage(");
        assertThat(source).contains("isRawStatusOnlyFilter(");
        assertThat(source).contains("Optional.of(Page.empty(pageable))");
        assertThat(source).contains("hasAnyDatabaseRow(\"raw_videos rv\", where, params)");
    }

    private static Path sourceFile() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path relative = Path.of(
                "services/ircs-content-service/src/main/java/com/prodigalgal/ircs/content/video/infrastructure/VideoAdminQueryRepository.java");
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        Path moduleFile = current.resolve(
                "src/main/java/com/prodigalgal/ircs/content/video/infrastructure/VideoAdminQueryRepository.java");
        if (Files.isRegularFile(moduleFile)) {
            return moduleFile;
        }
        return relative;
    }
}
