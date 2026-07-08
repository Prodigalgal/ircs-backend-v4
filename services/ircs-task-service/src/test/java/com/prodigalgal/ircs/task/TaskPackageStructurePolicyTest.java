package com.prodigalgal.ircs.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskPackageStructurePolicyTest {

    private static final Path MAIN_ROOT = sourceRoot();
    private static final Set<String> ALLOWED_ROOT_FILES = Set.of("IrcsTaskServiceApplication.java");

    @Test
    void taskRootPackageOnlyContainsApplicationEntryPoint() throws IOException {
        List<String> rootFiles;
        try (var paths = Files.list(MAIN_ROOT)) {
            rootFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> !ALLOWED_ROOT_FILES.contains(fileName))
                    .sorted()
                    .toList();
        }

        assertThat(rootFiles).isEmpty();
    }

    private static Path sourceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path relative = Path.of("services/ircs-task-service/src/main/java/com/prodigalgal/ircs/task");
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path root = candidate.resolve(relative);
            if (Files.isDirectory(root)) {
                return root;
            }
        }
        Path moduleRoot = current.resolve("src/main/java/com/prodigalgal/ircs/task");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        return relative;
    }
}
