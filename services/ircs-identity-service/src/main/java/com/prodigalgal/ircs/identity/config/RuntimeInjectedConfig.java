package com.prodigalgal.ircs.identity.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

public final class RuntimeInjectedConfig {

    private RuntimeInjectedConfig() {
    }

    public static Optional<InjectedValue> find(Environment environment, String... keys) {
        if (environment instanceof ConfigurableEnvironment configurableEnvironment) {
            for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
                if (!isRuntimeInjectionSource(source.getName())) {
                    continue;
                }
                for (String key : expandKeys(keys)) {
                    Object value = source.getProperty(key);
                    if (value != null && StringUtils.hasText(value.toString())) {
                        return Optional.of(new InjectedValue(key, value.toString()));
                    }
                }
            }
            return Optional.empty();
        }

        return Arrays.stream(expandKeys(keys))
                .map(key -> {
                    String value = environment.getProperty(key);
                    return StringUtils.hasText(value) ? new InjectedValue(key, value) : null;
                })
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    private static String[] expandKeys(String... keys) {
        return Arrays.stream(keys)
                .filter(StringUtils::hasText)
                .flatMap(key -> java.util.stream.Stream.of(key, toEnvironmentVariableName(key)))
                .distinct()
                .toArray(String[]::new);
    }

    private static boolean isRuntimeInjectionSource(String sourceName) {
        String name = sourceName.toLowerCase(Locale.ROOT);
        return name.equals("commandlineargs")
                || name.equals("systemproperties")
                || name.equals("systemenvironment")
                || name.equals("spring.application.json")
                || name.equals("mockproperties")
                || name.contains("config tree")
                || name.contains("configtree")
                || name.contains("kubernetes")
                || name.contains("configmap")
                || name.contains("secret")
                || name.contains("inlined test properties")
                || name.contains("dynamic test properties");
    }

    public static String toEnvironmentVariableName(String key) {
        return key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    public record InjectedValue(String key, String value) {
    }
}
