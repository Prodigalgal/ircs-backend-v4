package com.prodigalgal.ircs.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpsPackageStructurePolicyTest {

    private static final Path MAIN_ROOT = sourceRoot();
    private static final Set<String> ROOT_ALLOWLIST = Set.of("IrcsOpsServiceApplication.java");

    @Test
    void opsRootPackageOnlyContainsApplicationEntryPoint() throws IOException {
        List<String> violations;
        try (var paths = Files.list(MAIN_ROOT)) {
            violations = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> !ROOT_ALLOWLIST.contains(fileName))
                    .toList();
        }

        assertThat(violations).isEmpty();
    }

    private static Path sourceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path relative = Path.of("services/ircs-ops-service/src/main/java/com/prodigalgal/ircs/ops");
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path root = candidate.resolve(relative);
            if (Files.isDirectory(root)) {
                return root;
            }
        }
        Path moduleRoot = current.resolve("src/main/java/com/prodigalgal/ircs/ops");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        return relative;
    }
}
