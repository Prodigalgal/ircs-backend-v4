package com.prodigalgal.ircs.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentityPackageStructurePolicyTest {

    private static final Path MAIN_ROOT = sourceRoot();
    private static final Set<String> ALLOWED_ROOT_FILES = Set.of(
            "IrcsIdentityServiceApplication.java",
            "IdentityRedisKeys.java");

    @Test
    void identityRootPackageOnlyContainsEntryPointAndSharedRedisKeys() throws IOException {
        List<String> unexpectedRootFiles;
        try (var paths = Files.list(MAIN_ROOT)) {
            unexpectedRootFiles = paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(".java"))
                    .filter(fileName -> !ALLOWED_ROOT_FILES.contains(fileName))
                    .sorted()
                    .toList();
        }

        assertThat(unexpectedRootFiles).isEmpty();
    }

    private static Path sourceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path relative = Path.of("services/ircs-identity-service/src/main/java/com/prodigalgal/ircs/identity");
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path root = candidate.resolve(relative);
            if (Files.isDirectory(root)) {
                return root;
            }
        }
        Path moduleRoot = current.resolve("src/main/java/com/prodigalgal/ircs/identity");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        return relative;
    }
}
