package com.prodigalgal.ircs.ops.dashboard.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DashboardStatsSnapshotRepositorySqlPolicyTest {

    @Test
    void repositoryUsesMaterializedStatsSnapshotTableWithUpsertAndRuntimeTtl() throws IOException {
        String source = Files.readString(sourceFile(
                "services/ircs-ops-service/src/main/java/com/prodigalgal/ircs/ops/dashboard/infrastructure/DashboardStatsSnapshotRepository.java",
                "src/main/java/com/prodigalgal/ircs/ops/dashboard/infrastructure/DashboardStatsSnapshotRepository.java"));

        assertThat(source).contains("ops_dashboard_stats_snapshots");
        assertThat(source).contains("stale_until > now()");
        assertThat(source).contains("ON CONFLICT (snapshot_key) DO UPDATE");
        assertThat(source).contains("app.ops.dashboard.snapshot.fresh-ttl");
        assertThat(source).contains("app.ops.dashboard.snapshot.stale-grace");
    }

    @Test
    void migratorCreatesDashboardStatsSnapshotTableAndMasterIncludesIt() throws IOException {
        String changelog = Files.readString(sourceFile(
                "platform/ircs-migrator/src/main/resources/db/changelog/2026/07/add-dashboard-stats-snapshot.sql",
                "../platform/ircs-migrator/src/main/resources/db/changelog/2026/07/add-dashboard-stats-snapshot.sql"));
        String master = Files.readString(sourceFile(
                "platform/ircs-migrator/src/main/resources/db/changelog/db.changelog-master.yaml",
                "../platform/ircs-migrator/src/main/resources/db/changelog/db.changelog-master.yaml"));

        assertThat(changelog).contains("CREATE TABLE IF NOT EXISTS ops_dashboard_stats_snapshots");
        assertThat(changelog).contains("raw_count_db BIGINT");
        assertThat(changelog).contains("raw_count_es BIGINT");
        assertThat(changelog).contains("generated_at TIMESTAMPTZ");
        assertThat(changelog).contains("expires_at TIMESTAMPTZ");
        assertThat(changelog).contains("stale_until TIMESTAMPTZ");
        assertThat(master).contains("db/changelog/2026/07/add-dashboard-stats-snapshot.sql");
    }

    private static Path sourceFile(String backendRelative, String moduleRelative) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path backendPath = Path.of(backendRelative);
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(backendPath);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        Path moduleFile = current.resolve(moduleRelative);
        if (Files.isRegularFile(moduleFile)) {
            return moduleFile;
        }
        return backendPath;
    }
}
