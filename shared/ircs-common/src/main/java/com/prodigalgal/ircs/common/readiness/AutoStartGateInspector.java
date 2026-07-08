package com.prodigalgal.ircs.common.readiness;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

public final class AutoStartGateInspector {

    private final Environment environment;

    public AutoStartGateInspector(Environment environment) {
        this.environment = environment;
    }

    public List<AutoStartGateState> inspect(List<AutoStartGate> gates) {
        return gates.stream().map(this::inspect).toList();
    }

    public AutoStartGateState inspect(AutoStartGate gate) {
        Optional<InjectedGateValue> injected = findRuntimeInjected(gate);
        if (injected.isPresent()) {
            boolean enabled = Boolean.parseBoolean(injected.get().value());
            return new AutoStartGateState(
                    gate.name(),
                    gate.propertyKey(),
                    gate.environmentKey(),
                    enabled,
                    "INJECTED",
                    injected.get().key(),
                    !enabled);
        }

        String rawValue = environment.getProperty(gate.propertyKey());
        boolean enabled = StringUtils.hasText(rawValue) ? Boolean.parseBoolean(rawValue) : gate.defaultEnabled();
        return new AutoStartGateState(
                gate.name(),
                gate.propertyKey(),
                gate.environmentKey(),
                enabled,
                "DEFAULT",
                gate.propertyKey(),
                !enabled);
    }

    private Optional<InjectedGateValue> findRuntimeInjected(AutoStartGate gate) {
        if (environment instanceof ConfigurableEnvironment configurableEnvironment) {
            for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
                if (!isRuntimeInjectionSource(source.getName())) {
                    continue;
                }
                for (String key : gateKeys(gate)) {
                    Object value = source.getProperty(key);
                    if (value != null && StringUtils.hasText(value.toString())) {
                        return Optional.of(new InjectedGateValue(key, value.toString()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> gateKeys(AutoStartGate gate) {
        return Arrays.stream(new String[] {
                    gate.propertyKey(),
                    gate.environmentKey(),
                    toEnvironmentVariableName(gate.propertyKey())
                })
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
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

    private static String toEnvironmentVariableName(String key) {
        return key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private record InjectedGateValue(String key, String value) {
    }
}
