package com.prodigalgal.ircs.common.work;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.magnet.MagnetWorkTypes;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkTypes;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class SystemConfigWorkSubmissionGateTest {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource());
        jdbcTemplate.execute("""
                create table system_configs (
                    config_key varchar(255) primary key,
                    config_value varchar(255)
                )
                """);
    }

    @Test
    void disablesRuntimeSubmissionWhenAnyRequiredFlagIsFalseInDatabase() {
        insert("app.ai.llm.enabled", "false");

        SystemConfigWorkSubmissionGate gate = gate(new StandardEnvironment());

        assertThat(gate.canSubmitRuntime(LlmCleaningWorkTypes.RAW_TERM)).isFalse();
    }

    @Test
    void keepsCorePipelineRuntimeSubmissionAlwaysOpen() {
        insert("app.metadata.enabled", "false");
        insert("app.metadata.valkey-dispatcher.enabled", "false");
        insert("app.metadata.valkey-provider-worker.enabled", "false");
        insert("app.normalization.valkey-worker.enabled", "false");

        SystemConfigWorkSubmissionGate gate = gate(new StandardEnvironment());

        assertThat(gate.canSubmitRuntime(PipelineRuntimeWorkTypes.NORMALIZE_VIDEO)).isTrue();
        assertThat(gate.canSubmitRuntime(PipelineRuntimeWorkTypes.ENRICH_METADATA)).isTrue();
        assertThat(gate.canSubmitRuntime(PipelineRuntimeWorkTypes.METADATA_PROVIDER)).isTrue();
    }

    @Test
    void keepsCoreRuntimeSubmissionAlwaysOpen() {
        insert("app.aggregation.work-queue.worker.enabled", "false");

        SystemConfigWorkSubmissionGate gate = gate(new StandardEnvironment());

        assertThat(gate.canSubmitRuntime(AggregationWorkTypes.RAW_VIDEO)).isTrue();
    }

    @Test
    void fallsBackToEnvironmentWhenDatabaseHasNoValue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.search.sync.enabled", "false");

        SystemConfigWorkSubmissionGate gate = gate(environment);

        assertThat(gate.canSubmitRuntime(SearchSyncWorkTypes.RAW)).isFalse();
    }

    @Test
    void enhancementRuntimeWorkersDefaultToDeploymentDisabledState() {
        SystemConfigWorkSubmissionGate gate = gate(new StandardEnvironment());

        assertThat(gate.canSubmitRuntime(SearchSyncWorkTypes.RAW)).isFalse();
        assertThat(gate.canSubmitRuntime(SearchSyncWorkTypes.UNIFIED)).isFalse();
        assertThat(gate.canSubmitRuntime(LlmCleaningWorkTypes.RAW_TERM)).isFalse();
    }

    @Test
    void explicitRuntimeWorkerConfigEnablesEnhancementSubmission() {
        insert("app.search.work-queue.worker.enabled", "true");
        SystemConfigWorkSubmissionGate gate = gate(new StandardEnvironment());

        assertThat(gate.canSubmitRuntime(SearchSyncWorkTypes.RAW)).isTrue();
        assertThat(gate.canSubmitRuntime(SearchSyncWorkTypes.UNIFIED)).isTrue();
    }

    @Test
    void magnetSearchSubmissionIsIndependentFromAutoSearchWatchdogSwitch() {
        insert("app.magnet.auto-search.enabled", "false");
        SystemConfigWorkSubmissionGate gate = gate(new StandardEnvironment());

        assertThat(gate.canSubmitRuntime(MagnetWorkTypes.SEARCH)).isTrue();
    }

    @Test
    void canDisableMagnetSearchSubmissionExplicitly() {
        insert("app.magnet.work-queue.submission.enabled", "false");
        SystemConfigWorkSubmissionGate gate = gate(new StandardEnvironment());

        assertThat(gate.canSubmitRuntime(MagnetWorkTypes.SEARCH)).isFalse();
    }

    @Test
    void allowsUnknownWorkTypes() {
        SystemConfigWorkSubmissionGate gate = gate(new StandardEnvironment());

        assertThat(gate.canSubmitRuntime("custom.work")).isTrue();
    }

    private SystemConfigWorkSubmissionGate gate(Environment environment) {
        return new SystemConfigWorkSubmissionGate(
                jdbcTemplate,
                environment,
                emptyRedisProvider(),
                "ircs:test:system-config",
                java.time.Duration.ofMinutes(30),
                java.time.Duration.ofSeconds(1));
    }

    private void insert(String key, String value) {
        jdbcTemplate.update(
                "insert into system_configs(config_key, config_value) values (?, ?)",
                key,
                value);
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:submission_gate_" + java.util.UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> emptyRedisProvider() {
        return org.mockito.Mockito.mock(ObjectProvider.class);
    }
}
