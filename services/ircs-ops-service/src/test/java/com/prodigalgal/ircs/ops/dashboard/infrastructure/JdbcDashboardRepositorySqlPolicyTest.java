package com.prodigalgal.ircs.ops.dashboard.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JdbcDashboardRepositorySqlPolicyTest {

    @Test
    void dashboardStatsUsesSingleAggregateQueryInsteadOfRepeatedCounts() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("public DashboardStatsResponse loadStats()");
        assertThat(source).contains("CROSS JOIN");
        assertThat(source).contains("count(*) FILTER");
        assertThat(source).contains("pg_stat_user_tables");
        assertThat(source).contains("table_estimates");
        assertThat(source).doesNotContain("SELECT count(*) AS unified_count");
        assertThat(source).doesNotContain("SELECT count(*) AS task_count");
        assertThat(source).doesNotContain("count(\"raw_videos\")");
        assertThat(source).doesNotContain("countRawByStatus(");
        assertThat(source).doesNotContain("countCoverByStatus(");
    }

    private static Path sourceFile() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path relative = Path.of(
                "services/ircs-ops-service/src/main/java/com/prodigalgal/ircs/ops/dashboard/infrastructure/JdbcDashboardRepository.java");
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        Path moduleFile = current.resolve(
                "src/main/java/com/prodigalgal/ircs/ops/dashboard/infrastructure/JdbcDashboardRepository.java");
        if (Files.isRegularFile(moduleFile)) {
            return moduleFile;
        }
        return relative;
    }
}
