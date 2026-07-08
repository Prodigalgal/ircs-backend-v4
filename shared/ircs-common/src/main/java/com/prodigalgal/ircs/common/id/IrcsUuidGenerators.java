package com.prodigalgal.ircs.common.id;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class IrcsUuidGenerators {

    private static final IrcsUuidGenerator RANDOM = new Generator(IrcsUuidStrategy.RANDOM, UUID::randomUUID);
    private static final IrcsUuidGenerator TIME_BASED_EPOCH =
            new Generator(IrcsUuidStrategy.TIME_BASED_EPOCH, Generators.timeBasedEpochGenerator());

    private IrcsUuidGenerators() {
    }

    public static IrcsUuidGenerator defaultGenerator() {
        return TIME_BASED_EPOCH;
    }

    public static IrcsUuidGenerator random() {
        return RANDOM;
    }

    public static IrcsUuidGenerator timeBasedEpoch() {
        return TIME_BASED_EPOCH;
    }

    public static UUID nextId() {
        return defaultGenerator().nextId();
    }

    private record Generator(IrcsUuidStrategy strategy, Supplier<UUID> supplier) implements IrcsUuidGenerator {

        private Generator {
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(supplier, "supplier");
        }

        private Generator(IrcsUuidStrategy strategy, NoArgGenerator generator) {
            this(strategy, generator::generate);
        }

        @Override
        public UUID nextId() {
            return supplier.get();
        }
    }
}
