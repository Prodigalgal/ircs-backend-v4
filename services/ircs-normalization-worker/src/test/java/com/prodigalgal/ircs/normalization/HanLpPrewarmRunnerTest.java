package com.prodigalgal.ircs.normalization;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class HanLpPrewarmRunnerTest {

    @Test
    void prewarmCompletesWithoutThrowing() {
        HanLpPrewarmRunner runner = new HanLpPrewarmRunner(true);

        assertDoesNotThrow(runner::prewarm);
    }

    @Test
    void disabledRunnerReturnsWithoutThrowing() {
        HanLpPrewarmRunner runner = new HanLpPrewarmRunner(false);

        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
    }
}
