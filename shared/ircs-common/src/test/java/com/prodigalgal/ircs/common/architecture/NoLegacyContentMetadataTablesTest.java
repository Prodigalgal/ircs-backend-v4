package com.prodigalgal.ircs.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NoLegacyContentMetadataTablesTest {

    private static final Pattern LEGACY_TABLE_PATTERN = Pattern.compile(String.join(
            "|",
            "\\braw_genres\\b",
            "\\braw_languages\\b",
            "\\braw_areas\\b",
            "\\braw_category\\b",
            "\\bvideo_raw_[a-z_]+\\b",
            "\\bvideo_actors\\b",
            "\\bvideo_directors\\b",
            "\\bunified_video_actors\\b",
            "\\bunified_video_directors\\b",
            "\\bunified_video_genres\\b",
            "\\bunified_video_standard_languages\\b",
            "\\bunified_video_standard_areas\\b"));

    @Test
    void productionSourceDoesNotReferenceLegacyContentMetadataTables() throws IOException {
        Path backendRoot = findBackendRoot();
        List<Violation> violations = new ArrayList<>();
        for (Path sourceRoot : sourceRoots(backendRoot)) {
            if (Files.isDirectory(sourceRoot)) {
                collectViolations(backendRoot, sourceRoot, violations);
            }
        }

        assertThat(violations)
                .describedAs(() -> "Legacy content metadata table references in production source:\n"
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
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (LEGACY_TABLE_PATTERN.matcher(line).find()) {
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
