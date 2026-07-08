package com.prodigalgal.ircs.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ControllerAnnotationParameterNameGuardTest {

    private static final Pattern NAMED_VALUE_ANNOTATION =
            Pattern.compile("@(RequestParam|PathVariable|RequestHeader)\\(([^)]*)\\)");
    private static final Pattern BARE_ANNOTATION =
            Pattern.compile("@(RequestParam|PathVariable|RequestHeader)\\s+(?!\\()");

    @Test
    void controllerNamedValueAnnotationsDeclareExplicitParameterNames() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        for (String sourceRoot : List.of("shared", "services", "platform")) {
            Path path = root.resolve(sourceRoot);
            if (Files.exists(path)) {
                scan(path, root, violations);
            }
        }

        assertThat(violations)
                .as("Spring MVC named-value annotations must not depend on Java reflection parameter names")
                .isEmpty();
    }

    private static void scan(Path sourceRoot, Path repositoryRoot, List<String> violations) throws IOException {
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> file.toString().contains("src\\main\\java")
                            || file.toString().contains("src/main/java"))
                    .toList()) {
                scanFile(path, repositoryRoot, violations);
            }
        }
    }

    private static void scanFile(Path file, Path repositoryRoot, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            var namedMatcher = NAMED_VALUE_ANNOTATION.matcher(line);
            while (namedMatcher.find()) {
                String args = namedMatcher.group(2).trim();
                if (!args.startsWith("\"") && !containsExplicitName(args)) {
                    violations.add(formatViolation(repositoryRoot, file, index + 1, line));
                }
            }
            if (BARE_ANNOTATION.matcher(line).find()) {
                violations.add(formatViolation(repositoryRoot, file, index + 1, line));
            }
        }
    }

    private static boolean containsExplicitName(String args) {
        return Pattern.compile("(^|,|\\s)(name|value)\\s*=").matcher(args).find();
    }

    private static String formatViolation(Path repositoryRoot, Path file, int lineNumber, String line) {
        return repositoryRoot.relativize(file) + ":" + lineNumber + " " + line.trim();
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle"))
                    || Files.exists(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from " + Path.of("").toAbsolutePath());
    }
}
