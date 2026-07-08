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

class ScheduledTriggerModelTest {

    private static final String SCHEDULED_ANNOTATION = "@" + "Scheduled";
    private static final String REQUIRED_TRIGGER_SUBMIT = "ScheduledTriggers.submit(";

    @Test
    void scheduledEntryPointsOnlySubmitTriggers() throws IOException {
        Path backendRoot = findBackendRoot();
        List<Violation> violations = new ArrayList<>();
        for (Path sourceRoot : sourceRoots(backendRoot)) {
            if (Files.isDirectory(sourceRoot)) {
                collectViolations(backendRoot, sourceRoot, violations);
            }
        }

        assertThat(violations)
                .describedAs(() -> "Scheduled entry points must submit to ScheduledTriggers:\n" + format(violations))
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
                String source = Files.readString(file, StandardCharsets.UTF_8);
                collectScheduledAnnotationViolations(backendRoot, file, source, violations);
                collectTaskSchedulerViolations(backendRoot, file, source, violations);
            }
        }
    }

    private static void collectScheduledAnnotationViolations(
            Path backendRoot,
            Path file,
            String source,
            List<Violation> violations) {
        int cursor = source.indexOf(SCHEDULED_ANNOTATION);
        while (cursor >= 0) {
            MethodBody body = methodBodyAfter(source, cursor);
            if (body == null || !body.source().contains(REQUIRED_TRIGGER_SUBMIT)) {
                violations.add(new Violation(
                        relativePath(backendRoot, file),
                        lineNumber(source, cursor),
                        "@Scheduled method does not submit through ScheduledTriggers"));
            }
            cursor = source.indexOf(SCHEDULED_ANNOTATION, cursor + SCHEDULED_ANNOTATION.length());
        }
    }

    private static void collectTaskSchedulerViolations(
            Path backendRoot,
            Path file,
            String source,
            List<Violation> violations) {
        if (!source.contains("org.springframework.scheduling.TaskScheduler")
                && !source.contains("TaskScheduler ")) {
            return;
        }
        int cursor = source.indexOf(".schedule(");
        while (cursor >= 0) {
            MethodBody body = methodBodyAround(source, cursor);
            if (body == null || !body.source().contains(REQUIRED_TRIGGER_SUBMIT)) {
                violations.add(new Violation(
                        relativePath(backendRoot, file),
                        lineNumber(source, cursor),
                        "TaskScheduler.schedule call does not submit through ScheduledTriggers"));
            }
            cursor = source.indexOf(".schedule(", cursor + ".schedule(".length());
        }
    }

    private static MethodBody methodBodyAfter(String source, int offset) {
        int annotationEnd = annotationEnd(source, offset);
        if (annotationEnd < 0) {
            return null;
        }
        int openBrace = source.indexOf('{', annotationEnd);
        if (openBrace < 0) {
            return null;
        }
        int closeBrace = matchingCloseBrace(source, openBrace);
        if (closeBrace < 0) {
            return null;
        }
        return new MethodBody(source.substring(openBrace, closeBrace + 1));
    }

    private static int annotationEnd(String source, int offset) {
        int cursor = offset + SCHEDULED_ANNOTATION.length();
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= source.length() || source.charAt(cursor) != '(') {
            return cursor;
        }
        int depth = 0;
        for (int index = cursor; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '(') {
                depth++;
            } else if (value == ')') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
        }
        return -1;
    }

    private static MethodBody methodBodyAround(String source, int offset) {
        int openBrace = source.lastIndexOf('{', offset);
        if (openBrace < 0) {
            return null;
        }
        int closeBrace = matchingCloseBrace(source, openBrace);
        if (closeBrace < 0 || closeBrace < offset) {
            return null;
        }
        return new MethodBody(source.substring(openBrace, closeBrace + 1));
    }

    private static int matchingCloseBrace(String source, int openBrace) {
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
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
                .map(violation -> violation.file() + ":" + violation.line() + " " + violation.reason())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("(none)");
    }

    private record MethodBody(String source) {
    }

    private record Violation(String file, int line, String reason) {
    }
}
