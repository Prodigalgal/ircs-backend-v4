package com.prodigalgal.ircs.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IrcsUuidGeneratorsTest {

    @Test
    void defaultGeneratorUsesV1TimeBasedEpochStrategy() {
        IrcsUuidGenerator generator = IrcsUuidGenerators.defaultGenerator();

        UUID first = generator.nextId();
        UUID second = generator.nextId();

        assertThat(generator.strategy()).isEqualTo(IrcsUuidStrategy.TIME_BASED_EPOCH);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void timeBasedEpochGeneratorIsAvailableForExplicitMigrationSlices() {
        IrcsUuidGenerator generator = IrcsUuidGenerators.timeBasedEpoch();

        UUID first = generator.nextId();
        UUID second = generator.nextId();

        assertThat(generator.strategy()).isEqualTo(IrcsUuidStrategy.TIME_BASED_EPOCH);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void randomGeneratorRemainsAvailableForTokensAndBoundaries() {
        IrcsUuidGenerator generator = IrcsUuidGenerators.random();

        UUID value = generator.nextId();

        assertThat(generator.strategy()).isEqualTo(IrcsUuidStrategy.RANDOM);
        assertThat(value.version()).isEqualTo(4);
    }
}
