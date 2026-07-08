package com.prodigalgal.ircs.common.audit;

import java.util.UUID;
import org.springframework.util.StringUtils;

public final class AuditReplicationWorkTypes {

    public static final String ES_REPLICATION = "audit.es.replication";

    private AuditReplicationWorkTypes() {
    }

    public static String taskId(String sourceTable, UUID sourceId) {
        if (!StringUtils.hasText(sourceTable) || sourceId == null) {
            throw new IllegalArgumentException("sourceTable and sourceId are required");
        }
        return sourceTable.trim() + ":" + sourceId;
    }
}
