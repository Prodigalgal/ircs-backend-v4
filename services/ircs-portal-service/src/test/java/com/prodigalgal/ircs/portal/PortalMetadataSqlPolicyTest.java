package com.prodigalgal.ircs.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PortalMetadataSqlPolicyTest {

    @Test
    void activeMetadataQueriesSelectGroupedFallbackNames() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("SELECT coalesce(sg.name, gc.code) as name");
        assertThat(source).contains("SELECT coalesce(sa.name, ac.code) as name");
        assertThat(source).contains("SELECT coalesce(sl.name, lc.code) as name");
        assertThat(source).doesNotContain("SELECT sg.name\n");
        assertThat(source).doesNotContain("SELECT sa.name\n");
        assertThat(source).doesNotContain("SELECT sl.name\n");
    }

    @Test
    void unfilteredExploreUsesEstimatedTotalBeforeExactCountFallback() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("exploreRequiresExactTotal");
        assertThat(source).contains("estimatedTotal(page, size, content.size())");
        assertThat(source).contains("exactExploreTotal(where, params)");
    }

    @Test
    void portalCategoryReadsUseFlattenedCodeColumns() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("WHERE uv.category_code = :categoryKey");
        assertThat(source).contains("WHERE uv.category_code IN (:categoryKeys)");
        assertThat(source).contains("uv.content_visibility IN (:contentVisibility)");
        assertThat(source).contains("coalesce(uv.adult_restricted, false) = false");
        assertThat(source).contains("uv.adult_assessment ->> 'adultRestricted'");
        assertThat(source).contains("uv.adult_assessment ->> 'level'");
        assertThat(source).doesNotContain("coalesce(uv.content_visibility");
    }

    private static Path sourceFile() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path relative = Path.of("services/ircs-portal-service/src/main/java/com/prodigalgal/ircs/portal/JdbcPortalRepository.java");
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        Path moduleFile = current.resolve("src/main/java/com/prodigalgal/ircs/portal/JdbcPortalRepository.java");
        if (Files.isRegularFile(moduleFile)) {
            return moduleFile;
        }
        return relative;
    }
}
