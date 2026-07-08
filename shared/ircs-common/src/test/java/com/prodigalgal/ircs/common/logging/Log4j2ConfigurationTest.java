package com.prodigalgal.ircs.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Log4j2ConfigurationTest {

    @Test
    void logPathPropertyDoesNotSelfReferenceEnvironmentFallback() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("log4j2-spring.xml")) {
            assertThat(input).isNotNull();
            var xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(xml).doesNotContain("name=\"LOG_PATH\"");
            assertThat(xml).doesNotContain("${LOG_PATH}");
            assertThat(xml).contains("name=\"IRCS_RESOLVED_LOG_PATH\"");
            assertThat(xml).contains("${env:LOG_PATH:-./logs}");
            assertThat(xml).contains("${IRCS_RESOLVED_LOG_PATH}/info.log");
            assertThat(xml).contains("${IRCS_RESOLVED_LOG_PATH}/warn.log");
            assertThat(xml).contains("${IRCS_RESOLVED_LOG_PATH}/error.log");
            assertThat(xml).contains("${IRCS_RESOLVED_LOG_PATH}/ingestion-trace.log");
            assertThat(xml).doesNotContain("%clr{");
            assertThat(xml).contains("%throwable");
        }
    }
}
