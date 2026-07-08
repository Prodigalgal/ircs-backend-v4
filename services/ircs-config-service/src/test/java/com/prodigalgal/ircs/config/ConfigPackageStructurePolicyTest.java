package com.prodigalgal.ircs.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigPackageStructurePolicyTest {

    private static final Path MAIN_ROOT = sourceRoot();
    private static final List<String> ALLOWED_ROOT_FILES = List.of("IrcsConfigServiceApplication.java");

    @Test
    void configServiceKeepsFeatureCodeOutOfRootPackage() throws IOException {
        List<String> rootFiles;
        try (var paths = Files.list(MAIN_ROOT)) {
            rootFiles = paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(".java"))
                    .filter(fileName -> !ALLOWED_ROOT_FILES.contains(fileName))
                    .sorted()
                    .toList();
        }

        assertThat(rootFiles).isEmpty();
    }

    private static Path sourceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path relative = Path.of("services/ircs-config-service/src/main/java/com/prodigalgal/ircs/config");
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path root = candidate.resolve(relative);
            if (Files.isDirectory(root)) {
                return root;
            }
        }
        Path moduleRoot = current.resolve("src/main/java/com/prodigalgal/ircs/config");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        return relative;
    }
}
