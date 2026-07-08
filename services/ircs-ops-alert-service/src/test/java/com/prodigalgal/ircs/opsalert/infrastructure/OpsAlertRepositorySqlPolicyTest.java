package com.prodigalgal.ircs.opsalert.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpsAlertRepositorySqlPolicyTest {

    @Test
    void unfilteredListQueriesUseEstimatedTotals() throws IOException {
        String source = Files.readString(sourceFile());

        assertThat(source).contains("totalElements(");
        assertThat(source).contains("parts.filtered()");
        assertThat(source).contains("contentSize >= pageable.getPageSize() ? floor + 1 : floor");
    }

    private static Path sourceFile() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path relative = Path.of("services/ircs-ops-alert-service/src/main/java/com/prodigalgal/ircs/opsalert/infrastructure/OpsAlertRepository.java");
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        Path moduleFile = current.resolve("src/main/java/com/prodigalgal/ircs/opsalert/infrastructure/OpsAlertRepository.java");
        if (Files.isRegularFile(moduleFile)) {
            return moduleFile;
        }
        return relative;
    }
}
