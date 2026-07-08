package com.prodigalgal.ircs.common.outbound;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

public record OutboundCircuitBreakerConfig(
        boolean enabled,
        int failureThreshold,
        Duration openDuration,
        int halfOpenMaxCalls) {

    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(30);
    private static final int DEFAULT_HALF_OPEN_MAX_CALLS = 1;

    public OutboundCircuitBreakerConfig {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("Outbound circuit failureThreshold must be >= 1");
        }
        if (openDuration == null || openDuration.isNegative()) {
            throw new IllegalArgumentException("Outbound circuit openDuration must be >= 0");
        }
        if (halfOpenMaxCalls < 1) {
            throw new IllegalArgumentException("Outbound circuit halfOpenMaxCalls must be >= 1");
        }
    }

    public static OutboundCircuitBreakerConfig enabledFromEnvironment() {
        return enabledFromEnvironment(null);
    }

    public static OutboundCircuitBreakerConfig enabledFromEnvironment(String callerKey) {
        return new OutboundCircuitBreakerConfig(
                booleanValue(callerKey, "ENABLED", "enabled", DEFAULT_ENABLED, System::getenv, System::getProperty),
                intValue(callerKey, "FAILURE_THRESHOLD", "failure-threshold", DEFAULT_FAILURE_THRESHOLD,
                        System::getenv, System::getProperty),
                durationValue(callerKey, DEFAULT_OPEN_DURATION, System::getenv, System::getProperty),
                intValue(callerKey, "HALF_OPEN_MAX_CALLS", "half-open-max-calls", DEFAULT_HALF_OPEN_MAX_CALLS,
                        System::getenv, System::getProperty));
    }

    public static OutboundCircuitBreakerConfig enabled(
            int failureThreshold,
            Duration openDuration,
            int halfOpenMaxCalls) {
        return new OutboundCircuitBreakerConfig(true, failureThreshold, openDuration, halfOpenMaxCalls);
    }

    public static OutboundCircuitBreakerConfig disabled() {
        return new OutboundCircuitBreakerConfig(false, DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_DURATION,
                DEFAULT_HALF_OPEN_MAX_CALLS);
    }

    static OutboundCircuitBreakerConfig fromSources(
            String callerKey,
            Function<String, String> env,
            Function<String, String> property) {
        return new OutboundCircuitBreakerConfig(
                booleanValue(callerKey, "ENABLED", "enabled", DEFAULT_ENABLED, env, property),
                intValue(callerKey, "FAILURE_THRESHOLD", "failure-threshold", DEFAULT_FAILURE_THRESHOLD, env, property),
                durationValue(callerKey, DEFAULT_OPEN_DURATION, env, property),
                intValue(callerKey, "HALF_OPEN_MAX_CALLS", "half-open-max-calls", DEFAULT_HALF_OPEN_MAX_CALLS,
                        env, property));
    }

    private static boolean booleanValue(
            String callerKey,
            String envSuffix,
            String propertySuffix,
            boolean defaultValue,
            Function<String, String> env,
            Function<String, String> property) {
        String value = configuredValue(callerKey, envSuffix, propertySuffix, env, property);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static int intValue(
            String callerKey,
            String envSuffix,
            String propertySuffix,
            int defaultValue,
            Function<String, String> env,
            Function<String, String> property) {
        String value = configuredValue(callerKey, envSuffix, propertySuffix, env, property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static Duration durationValue(
            String callerKey,
            Duration defaultValue,
            Function<String, String> env,
            Function<String, String> property) {
        String value = configuredDurationValue(callerKey, env, property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Duration.ofMillis(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return parseDuration(value, defaultValue);
        }
    }

    private static String configuredDurationValue(
            String callerKey,
            Function<String, String> env,
            Function<String, String> property) {
        String callerToken = callerToken(callerKey);
        if (callerToken != null) {
            String value = firstPresent(env,
                    "IRCS_OUTBOUND_CIRCUIT_" + callerToken + "_OPEN_DURATION_MS",
                    "IRCS_OUTBOUND_CIRCUIT_" + callerToken + "_OPEN_DURATION");
            if (value != null) {
                return value;
            }
        }
        String value = firstPresent(env, "IRCS_OUTBOUND_CIRCUIT_OPEN_DURATION_MS", "IRCS_OUTBOUND_CIRCUIT_OPEN_DURATION");
        if (value != null) {
            return value;
        }
        if (hasText(callerKey)) {
            value = firstPresent(property,
                    "ircs.outbound.circuit." + callerKey + ".open-duration-ms",
                    "ircs.outbound.circuit." + callerKey + ".open-duration");
            if (value != null) {
                return value;
            }
        }
        return firstPresent(property, "ircs.outbound.circuit.open-duration-ms", "ircs.outbound.circuit.open-duration");
    }

    private static String configuredValue(
            String callerKey,
            String envSuffix,
            String propertySuffix,
            Function<String, String> env,
            Function<String, String> property) {
        String callerToken = callerToken(callerKey);
        if (callerToken != null) {
            String value = value(env, "IRCS_OUTBOUND_CIRCUIT_" + callerToken + "_" + envSuffix);
            if (value != null) {
                return value;
            }
        }
        String value = value(env, "IRCS_OUTBOUND_CIRCUIT_" + envSuffix);
        if (value != null) {
            return value;
        }
        if (hasText(callerKey)) {
            value = value(property, "ircs.outbound.circuit." + callerKey + "." + propertySuffix);
            if (value != null) {
                return value;
            }
        }
        return value(property, "ircs.outbound.circuit." + propertySuffix);
    }

    private static String firstPresent(Function<String, String> source, String... names) {
        for (String name : names) {
            String value = value(source, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String value(Function<String, String> source, String name) {
        String value = source.apply(name);
        return hasText(value) ? value : null;
    }

    private static String callerToken(String callerKey) {
        if (!hasText(callerKey)) {
            return null;
        }
        String token = callerKey.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return hasText(token) ? token : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Duration parseDuration(String value, Duration defaultValue) {
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (trimmed.startsWith("p")) {
                return Duration.parse(value);
            }
            if (trimmed.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2)));
            }
            if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            }
            if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            }
            return Duration.parse(value);
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }
}
