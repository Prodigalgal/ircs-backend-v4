package com.prodigalgal.ircs.platformapi;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

public class IrcsPlatformApiRuntimeHints implements RuntimeHintsRegistrar {

    private static final MemberCategory[] JSON_BINDING_MEMBER_CATEGORIES = {
            MemberCategory.INTROSPECT_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INTROSPECT_PUBLIC_METHODS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.DECLARED_FIELDS
    };

    private static final String[] SHARED_JSON_BINDING_TYPES = {
            "com.prodigalgal.ircs.common.aggregation.AggregationWorkPayload",
            "com.prodigalgal.ircs.common.audit.AuditReplicationWorkPayload",
            "com.prodigalgal.ircs.common.maintenance.MaintenanceGateDecision",
            "com.prodigalgal.ircs.common.normalization.LlmCleaningWorkPayload",
            "com.prodigalgal.ircs.common.search.SearchSyncWorkPayload",
            "com.prodigalgal.ircs.common.storage.StorageWorkPayload",
            "com.prodigalgal.ircs.contracts.aggregation.AggregationMaintenanceRunResponse",
            "com.prodigalgal.ircs.contracts.aggregation.AggregationResetStepResponse",
            "com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent",
            "com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent$Action",
            "com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentBatchRequest",
            "com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentBatchResponse",
            "com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentItem",
            "com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentModelResult",
            "com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentResult",
            "com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentSignalDto",
            "com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentSourceEvidence",
            "com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease",
            "com.prodigalgal.ircs.contracts.ingestion.IngestionItem",
            "com.prodigalgal.ircs.contracts.ingestion.PlaylistSyncMessage",
            "com.prodigalgal.ircs.contracts.interaction.WatchProgressMessage",
            "com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateChangedEvent",
            "com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext",
            "com.prodigalgal.ircs.contracts.normalization.NormalizationMaintenanceRunResponse",
            "com.prodigalgal.ircs.contracts.notification.MailMessageDTO",
            "com.prodigalgal.ircs.contracts.search.AdminVideoSearchResult",
            "com.prodigalgal.ircs.contracts.search.AdminVideoSearchResult$TotalRelation",
            "com.prodigalgal.ircs.contracts.search.SearchEntityType",
            "com.prodigalgal.ircs.contracts.search.SearchIndexMaintenanceResponse",
            "com.prodigalgal.ircs.contracts.search.SearchSyncMessage",
            "com.prodigalgal.ircs.contracts.search.SearchSyncTaskBatchEnqueueRequest",
            "com.prodigalgal.ircs.contracts.search.SearchSyncTaskEnqueueRequest",
            "com.prodigalgal.ircs.contracts.search.SearchSyncTaskEnqueueResponse",
            "com.prodigalgal.ircs.contracts.search.SyncOperation",
            "com.prodigalgal.ircs.contracts.task.TaskDetailDoneMessage",
            "com.prodigalgal.ircs.contracts.task.TaskDetailMessage",
            "com.prodigalgal.ircs.contracts.task.TaskMasterDoneMessage",
            "com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot",
            "com.prodigalgal.ircs.contracts.task.TaskPageDiscoveredMessage",
            "com.prodigalgal.ircs.contracts.task.TaskPageFailedMessage",
            "com.prodigalgal.ircs.contracts.task.TaskPageMessage",
            "com.prodigalgal.ircs.contracts.task.TaskScrapeOptions",
            "com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleResponse",
            "com.prodigalgal.ircs.contracts.trend.TrendSyncApplyResponse",
            "com.prodigalgal.ircs.contracts.trend.TrendSyncRunResponse"
    };

    private static final String[] PLATFORM_JSON_BINDING_TYPES = {
            "com.prodigalgal.ircs.identity.infrastructure.AvatarStorageClient$StoredAvatar",
            "com.prodigalgal.ircs.task.infrastructure.ScraperTaskExecutionLog",
            "com.prodigalgal.ircs.task.infrastructure.ScraperTaskExecutionResult"
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerResourceHints(hints);
        registerJsonBindingHints(hints, SHARED_JSON_BINDING_TYPES);
        registerJsonBindingHints(hints, PLATFORM_JSON_BINDING_TYPES);
    }

    private static void registerResourceHints(RuntimeHints hints) {
        hints.resources().registerPattern("*.properties");
        hints.resources().registerPattern("*.yml");
        hints.resources().registerPattern("*.yaml");
        hints.resources().registerPattern("log4j2*.xml");
        hints.resources().registerPattern("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat");
        hints.resources().registerPattern("**/Log4j2Plugins.dat");
        hints.resources().registerPattern("META-INF/services/*");
        hints.resources().registerPattern("**/*.sql");
        hints.resources().registerPattern("**/*.lua");
        hints.resources().registerPattern("templates/**");
        hints.resources().registerPattern("static/**");
    }

    private static void registerJsonBindingHints(RuntimeHints hints, String... typeNames) {
        for (String typeName : typeNames) {
            hints.reflection().registerType(TypeReference.of(typeName), JSON_BINDING_MEMBER_CATEGORIES);
        }
    }
}
