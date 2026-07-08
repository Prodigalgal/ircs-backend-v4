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

class VirtualThreadExecutorModelTest {

    private static final String ALLOWED_HELPER =
            "shared/ircs-common/src/main/java/com/prodigalgal/ircs/common/concurrent/VirtualThreadExecutors.java";

    @Test
    void namedVirtualThreadFactoriesAreCentralized() throws IOException {
        Path backendRoot = findBackendRoot();
        List<Violation> violations = new ArrayList<>();
        for (Path sourceRoot : sourceRoots(backendRoot)) {
            if (Files.isDirectory(sourceRoot)) {
                collectViolations(backendRoot, sourceRoot, violations);
            }
        }

        assertThat(violations)
                .describedAs(() -> "Named virtual-thread creation must use VirtualThreadExecutors:\n"
                        + format(violations))
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
                    .forEach(module -> roots.add(module.resolve("src/main/java")));
        }
    }

    private static void collectViolations(Path backendRoot, Path sourceRoot, List<Violation> violations)
            throws IOException {
        try (var stream = Files.walk(sourceRoot)) {
            List<Path> files = stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                String relativePath = relativePath(backendRoot, file);
                if (ALLOWED_HELPER.equals(relativePath)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                collectPatternViolations(relativePath, source, "Thread.ofVirtual().name(", violations);
                collectPatternViolations(relativePath, source, "Executors.newThreadPerTaskExecutor(", violations);
            }
        }
    }

    private static void collectPatternViolations(
            String relativePath,
            String source,
            String pattern,
            List<Violation> violations) {
        int cursor = source.indexOf(pattern);
        while (cursor >= 0) {
            violations.add(new Violation(relativePath, lineNumber(source, cursor), pattern));
            cursor = source.indexOf(pattern, cursor + pattern.length());
        }
    }

    private static int lineNumber(String source, int offset) {
        int line = 1;
        for (int index = 0; index < offset && index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
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

    private static String relativePath(Path backendRoot, Path file) {
        return backendRoot.relativize(file).toString().replace('\\', '/');
    }

    private static String format(List<Violation> violations) {
        return violations.stream()
                .sorted(Comparator.comparing(Violation::file).thenComparingInt(Violation::line))
                .map(violation -> violation.file() + ":" + violation.line() + " direct " + violation.pattern())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("(none)");
    }

    private record Violation(String file, int line, String pattern) {
    }
}
