package com.prodigalgal.ircs.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoAutowiredUsageTest {

    private static final String FORBIDDEN_ANNOTATION = "@" + "Autowired";
    private static final String FORBIDDEN_IMPORT =
            "org.springframework.beans.factory.annotation." + "Autowired";

    @Test
    void backendSourceDoesNotUseForbiddenAutowiredInjection() throws IOException {
        Path backendRoot = findBackendRoot();
        List<Violation> violations = new ArrayList<>();
        for (Path sourceRoot : sourceRoots(backendRoot)) {
            if (Files.isDirectory(sourceRoot)) {
                collectViolations(backendRoot, sourceRoot, violations);
            }
        }

        assertThat(violations)
                .describedAs(() -> "Forbidden Spring injection annotation usage:\n" + format(violations))
                .isEmpty();
    }

    private static List<Path> sourceRoots(Path backendRoot) throws IOException {
        List<Path> roots = new ArrayList<>();
        collectModuleSourceRoots(backendRoot.resolve("shared"), roots);
        collectModuleSourceRoots(backendRoot.resolve("services"), roots);
        return roots;
    }

    private static void collectModuleSourceRoots(Path modulesRoot, List<Path> roots) throws IOException {
        if (!Files.isDirectory(modulesRoot)) {
            return;
        }
        try (var stream = Files.list(modulesRoot)) {
            stream.filter(Files::isDirectory)
                    .sorted()
                    .forEach(module -> {
                        roots.add(module.resolve("src/main/java"));
                        roots.add(module.resolve("src/test/java"));
                    });
        }
    }

    private static void collectViolations(Path backendRoot, Path sourceRoot, List<Violation> violations)
            throws IOException {
        try (var stream = Files.walk(sourceRoot)) {
            List<Path> files = stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (line.contains(FORBIDDEN_ANNOTATION) || line.contains(FORBIDDEN_IMPORT)) {
                        violations.add(new Violation(
                                backendRoot.relativize(file).toString().replace('\\', '/'),
                                index + 1,
                                line.trim()));
                    }
                }
            }
        }
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

    private static String format(List<Violation> violations) {
        return violations.stream()
                .sorted(Comparator.comparing(Violation::file).thenComparingInt(Violation::line))
                .map(violation -> violation.file() + ":" + violation.line() + " " + violation.source())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("(none)");
    }

    private record Violation(String file, int line, String source) {
    }
}
