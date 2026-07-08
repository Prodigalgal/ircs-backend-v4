package com.prodigalgal.ircs.platformapi;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class IrcsPlatformApiRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("*.properties");
        hints.resources().registerPattern("*.yml");
        hints.resources().registerPattern("*.yaml");
        hints.resources().registerPattern("log4j2*.xml");
        hints.resources().registerPattern("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat");
        hints.resources().registerPattern("**/Log4j2Plugins.dat");
        hints.resources().registerPattern("META-INF/services/*");
        hints.resources().registerPattern("**/*.sql");
        hints.resources().registerPattern("**/*.lua");
        hints.resources().registerPattern("templates/**");
        hints.resources().registerPattern("static/**");
    }
}
