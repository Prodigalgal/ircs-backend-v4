package com.prodigalgal.ircs.ops.audit.governance;

import com.prodigalgal.ircs.common.audit.AuditReplicationWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuditGovernanceRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectProvider<RuntimeWorkQueue> workQueueProvider;

    public AuditArchiveSummaryResponse archiveSummary() {
        return jdbcTemplate.queryForObject(
                """
                select count(*) as total,
                       sum(case when audit_class = 'SECURITY' then 1 else 0 end) as security_count,
                       sum(case when audit_class = 'BEHAVIOR' then 1 else 0 end) as behavior_count,
                       sum(case when audit_class = 'SYSTEM' then 1 else 0 end) as system_count,
                       min(created_at) as oldest_archived_at,
                       max(created_at) as newest_archived_at
                  from audit_archive_entries
                """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> new AuditArchiveSummaryResponse(
                        rs.getLong("total"),
                        rs.getLong("security_count"),
                        rs.getLong("behavior_count"),
                        rs.getLong("system_count"),
                        toInstant(rs.getTimestamp("oldest_archived_at")),
                        toInstant(rs.getTimestamp("newest_archived_at"))));
    }

    public AuditReplicationWorkQueueSummaryResponse replicationWorkQueueSummary() {
        RuntimeWorkQueue workQueue = workQueueProvider.getIfAvailable();
        if (workQueue == null) {
            return new AuditReplicationWorkQueueSummaryResponse(
                    AuditReplicationWorkTypes.ES_REPLICATION,
                    false,
                    0,
                    0,
                    0,
                    0);
        }
        RuntimeWorkQueueCounts counts = workQueue.counts(AuditReplicationWorkTypes.ES_REPLICATION);
        return new AuditReplicationWorkQueueSummaryResponse(
                AuditReplicationWorkTypes.ES_REPLICATION,
                true,
                counts.pending() + counts.inflight() + counts.dlq(),
                counts.pending(),
                counts.inflight(),
                counts.dlq());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
