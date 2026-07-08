package com.prodigalgal.ircs.common.worker;

import java.lang.management.ManagementFactory;
import org.springframework.util.StringUtils;

public final class WorkerInstanceIds {

    public static final int MAX_LENGTH = 128;

    private WorkerInstanceIds() {
    }

    public static String resolve(String applicationName, String configuredWorkerId) {
        if (StringUtils.hasText(configuredWorkerId)) {
            return truncate(configuredWorkerId.trim());
        }
        String service = StringUtils.hasText(applicationName) ? applicationName.trim() : "worker";
        String host = firstNonBlank(
                System.getenv("POD_NAME"),
                System.getenv("HOSTNAME"),
                System.getenv("COMPUTERNAME"),
                "local");
        String pid = processId();
        return truncate(service + "@" + host + "#" + pid);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "local";
    }

    private static String processId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int at = runtimeName.indexOf('@');
        if (at > 0) {
            return runtimeName.substring(0, at);
        }
        return runtimeName.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String truncate(String value) {
        return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH);
    }
}
