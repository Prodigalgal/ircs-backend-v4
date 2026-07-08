package com.prodigalgal.ircs.common.concurrent;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class VirtualThreadExecutors {

    private VirtualThreadExecutors() {
    }

    public static ExecutorService newPerTaskExecutor(String threadNamePrefix) {
        return Executors.newThreadPerTaskExecutor(threadFactory(threadNamePrefix));
    }

    public static ThreadFactory threadFactory(String threadNamePrefix) {
        return Thread.ofVirtual().name(requirePrefix(threadNamePrefix), 0).factory();
    }

    private static String requirePrefix(String threadNamePrefix) {
        String value = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
        return value;
    }
}
