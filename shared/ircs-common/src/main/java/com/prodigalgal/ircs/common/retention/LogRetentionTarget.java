package com.prodigalgal.ircs.common.retention;

import java.time.Duration;
import java.time.Instant;

public interface LogRetentionTarget {

    String id();

    LogRetentionResult deleteOlderThan(Instant cutoff, Duration retention);
}
