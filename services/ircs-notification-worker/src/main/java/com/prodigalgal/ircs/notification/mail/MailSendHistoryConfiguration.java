package com.prodigalgal.ircs.notification.mail;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MailSendHistoryConfiguration {

    @Bean
    MailSendHistoryWriter mailSendHistoryWriter(
            @Value("${app.notification.mail.send-history.enabled:false}") boolean enabled,
            @Value("${app.notification.mail.send-history.datasource-url:${SPRING_DATASOURCE_URL:${DB_URL:}}}")
            String datasourceUrl,
            @Value("${app.notification.mail.send-history.username:${SPRING_DATASOURCE_USERNAME:${DB_USERNAME:}}}")
            String username,
            @Value("${app.notification.mail.send-history.password:${SPRING_DATASOURCE_PASSWORD:${DB_PASSWORD:}}}")
            String password) {
        return new JdbcMailSendHistoryWriter(enabled, datasourceUrl, username, password);
    }

    @Bean
    MailCredentialUsageRepository mailCredentialUsageRepository(
            @Value("${app.notification.mail.send-history.datasource-url:${SPRING_DATASOURCE_URL:${DB_URL:}}}")
            String datasourceUrl,
            @Value("${app.notification.mail.send-history.username:${SPRING_DATASOURCE_USERNAME:${DB_USERNAME:}}}")
            String username,
            @Value("${app.notification.mail.send-history.password:${SPRING_DATASOURCE_PASSWORD:${DB_PASSWORD:}}}")
            String password) {
        return new MailCredentialUsageRepository(datasourceUrl, username, password);
    }

    @Bean
    JdbcMailSendHistoryCleanupService mailSendHistoryCleanupService(
            @Value("${app.notification.mail.send-history.datasource-url:${SPRING_DATASOURCE_URL:${DB_URL:}}}")
            String datasourceUrl,
            @Value("${app.notification.mail.send-history.username:${SPRING_DATASOURCE_USERNAME:${DB_USERNAME:}}}")
            String username,
            @Value("${app.notification.mail.send-history.password:${SPRING_DATASOURCE_PASSWORD:${DB_PASSWORD:}}}")
            String password) {
        return new JdbcMailSendHistoryCleanupService(datasourceUrl, username, password, Clock.systemUTC());
    }

    @Bean
    MailSendHistoryCleanupRunner mailSendHistoryCleanupRunner(
            @Value("${app.notification.mail.send-history.cleanup.enabled:false}") boolean enabled,
            @Value("${app.notification.mail.send-history.cleanup.dry-run:true}") boolean dryRun,
            @Value("${app.notification.mail.send-history.cleanup.execute-enabled:false}") boolean executeEnabled,
            @Value("${app.notification.mail.send-history.cleanup.retention-days:180}") int retentionDays,
            @Value("${app.notification.mail.send-history.cleanup.statuses:}") String statuses,
            @Value("${app.notification.mail.send-history.cleanup.batch-size:500}") int batchSize,
            @Value("${app.notification.mail.send-history.cleanup.max-batches:20}") int maxBatches,
            @Value("${app.notification.mail.send-history.cleanup.rate-limit-delay-ms:0}") int rateLimitDelayMs,
            @Value("${app.notification.mail.send-history.cleanup.exit-on-complete:false}") boolean exitOnComplete,
            @Value("${spring.application.name:ircs-notification-worker}") String applicationName,
            @Value("${app.notification.cluster-lock.worker-id:${APP_NOTIFICATION_CLUSTER_LOCK_WORKER_ID:}}") String configuredWorkerId,
            @Value("${app.notification.cluster-lock.enabled:true}") boolean clusterLockEnabled,
            @Value("${app.notification.cluster-lock.ttl:PT10M}") String clusterLockTtl,
            JdbcMailSendHistoryCleanupService cleanupService,
            WorkerJobAuditWriter auditWriter,
            DistributedLockManager lockManager,
            ConfigurableApplicationContext applicationContext) {
        return new MailSendHistoryCleanupRunner(
                enabled,
                dryRun,
                executeEnabled,
                retentionDays,
                statuses,
                batchSize,
                maxBatches,
                rateLimitDelayMs,
                exitOnComplete,
                cleanupService,
                auditWriter,
                lockManager,
                applicationName,
                configuredWorkerId,
                clusterLockEnabled,
                clusterLockTtl,
                applicationContext);
    }
}
