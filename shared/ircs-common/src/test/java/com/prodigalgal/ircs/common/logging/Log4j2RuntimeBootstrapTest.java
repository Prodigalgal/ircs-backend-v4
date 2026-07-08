package com.prodigalgal.ircs.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Log4j2RuntimeBootstrapTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(Log4j2RuntimeBootstrap.ALLOWED_PROTOCOLS_PROPERTY);
    }

    @Test
    void appliesNativeSafeAllowedProtocolsWhenUnset() {
        System.clearProperty(Log4j2RuntimeBootstrap.ALLOWED_PROTOCOLS_PROPERTY);

        Log4j2RuntimeBootstrap.configureBeforeSpringApplicationRun();

        assertThat(System.getProperty(Log4j2RuntimeBootstrap.ALLOWED_PROTOCOLS_PROPERTY))
                .isEqualTo(Log4j2RuntimeBootstrap.NATIVE_SAFE_ALLOWED_PROTOCOLS);
    }

    @Test
    void preservesExplicitDeploymentOverride() {
        System.setProperty(Log4j2RuntimeBootstrap.ALLOWED_PROTOCOLS_PROPERTY, "file,jar");

        Log4j2RuntimeBootstrap.configureBeforeSpringApplicationRun();

        assertThat(System.getProperty(Log4j2RuntimeBootstrap.ALLOWED_PROTOCOLS_PROPERTY))
                .isEqualTo("file,jar");
    }
}
