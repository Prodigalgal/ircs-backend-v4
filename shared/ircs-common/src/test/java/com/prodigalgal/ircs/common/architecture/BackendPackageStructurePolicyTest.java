package com.prodigalgal.ircs.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackendPackageStructurePolicyTest {

    @Test
    void migratedFeatureClassesDoNotRegressIntoFlatPackages() throws IOException {
        Path backendRoot = findBackendRoot();
        List<String> violations = migratedRootFiles().stream()
                .filter(relative -> Files.exists(backendRoot.resolve(relative)))
                .sorted()
                .toList();

        assertThat(violations)
                .describedAs(() -> "Migrated classes must stay in layered packages:\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    private static List<String> migratedRootFiles() {
        return List.of(
                "services/ircs-config-service/src/main/java/com/prodigalgal/ircs/config/CommonController.java",
                "services/ircs-config-service/src/main/java/com/prodigalgal/ircs/config/ConfigController.java",
                "services/ircs-config-service/src/main/java/com/prodigalgal/ircs/config/ConfigService.java",
                "services/ircs-config-service/src/main/java/com/prodigalgal/ircs/config/JdbcConfigRepository.java",
                "services/ircs-task-service/src/main/java/com/prodigalgal/ircs/task/CollectionTaskController.java",
                "services/ircs-task-service/src/main/java/com/prodigalgal/ircs/task/TrendDiscoveryInternalController.java",
                "services/ircs-identity-service/src/main/java/com/prodigalgal/ircs/identity/AdminAuthController.java",
                "services/ircs-identity-service/src/main/java/com/prodigalgal/ircs/identity/MemberAdminController.java",
                "services/ircs-identity-service/src/main/java/com/prodigalgal/ircs/identity/MemberAuthController.java",
                "services/ircs-identity-service/src/main/java/com/prodigalgal/ircs/identity/MemberProfileController.java",
                "services/ircs-content-service/src/main/java/com/prodigalgal/ircs/content/auxiliary/AuxiliaryAdminService.java",
                "services/ircs-content-service/src/main/java/com/prodigalgal/ircs/content/people/PeopleAdminService.java",
                "services/ircs-search-service/src/main/java/com/prodigalgal/ircs/search/PortalSearchController.java",
                "services/ircs-search-service/src/main/java/com/prodigalgal/ircs/search/PortalSearchQueryService.java",
                "services/ircs-search-service/src/main/java/com/prodigalgal/ircs/search/SearchPortalReadModelCache.java",
                "services/ircs-metadata-worker/src/main/java/com/prodigalgal/ircs/metadata/dispatch/MetadataDispatchService.java",
                "services/ircs-metadata-worker/src/main/java/com/prodigalgal/ircs/metadata/result/MetadataResultCollectorService.java",
                "services/ircs-ops-service/src/main/java/com/prodigalgal/ircs/ops/DashboardController.java",
                "services/ircs-ops-service/src/main/java/com/prodigalgal/ircs/ops/DlqController.java",
                "services/ircs-ops-service/src/main/java/com/prodigalgal/ircs/ops/MaintenanceController.java",
                "services/ircs-ops-service/src/main/java/com/prodigalgal/ircs/ops/ServiceRestartController.java");
    }

    private static Path findBackendRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isDirectory(current.resolve("shared"))
                    && Files.isDirectory(current.resolve("services"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate backend Gradle root");
    }
}
