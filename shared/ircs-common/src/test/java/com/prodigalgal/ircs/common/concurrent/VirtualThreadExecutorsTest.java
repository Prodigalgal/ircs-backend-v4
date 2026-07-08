package com.prodigalgal.ircs.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class VirtualThreadExecutorsTest {

    @Test
    void createsNamedVirtualThreads() throws Exception {
        try (ExecutorService executor = VirtualThreadExecutors.newPerTaskExecutor("ircs-test-vt-")) {
            Future<String> threadName = executor.submit(() -> Thread.currentThread().getName());

            assertThat(threadName.get()).startsWith("ircs-test-vt-");
        }
    }

    @Test
    void rejectsBlankThreadNamePrefix() {
        assertThatThrownBy(() -> VirtualThreadExecutors.threadFactory(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("threadNamePrefix must not be blank");
    }
}
