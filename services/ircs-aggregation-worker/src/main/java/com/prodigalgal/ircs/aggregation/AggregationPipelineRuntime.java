package com.prodigalgal.ircs.aggregation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class AggregationPipelineRuntime {

    private final JdbcAggregationRepository repository;
    private TransactionOperations stageTransaction;
    public AggregationPipelineRuntime(
            JdbcAggregationRepository repository,
            PlatformTransactionManager transactionManager,
            @Value("${app.aggregation.pipeline.stage-transaction-timeout-seconds:30}") int stageTransactionTimeoutSeconds) {
        this.repository = repository;
        this.stageTransaction = nestedStageTransaction(transactionManager, stageTransactionTimeoutSeconds);
    }

    static AggregationPipelineRuntime forTest(
            JdbcAggregationRepository repository,
            TransactionOperations stageTransaction) {
        AggregationPipelineRuntime runtime = new AggregationPipelineRuntime(repository, unusedTransactionManager(), 30);
        runtime.stageTransaction = stageTransaction;
        return runtime;
    }

    public AggregationPipelineExecution run(UUID unifiedVideoId) {
        List<AggregationPipelineStageFailure> failures = new ArrayList<>();
        List<UUID> coverR2SyncImageIds = new ArrayList<>();

        runStage(
                AggregationPipelineStage.BASIC_ATTRIBUTE,
                unifiedVideoId,
                () -> repository.rebuildUnifiedPipelineBasicAttributes(unifiedVideoId),
                failures);
        runStage(
                AggregationPipelineStage.EXTERNAL_ID,
                unifiedVideoId,
                () -> repository.rebuildUnifiedPipelineExternalIds(unifiedVideoId),
                failures);
        runStage(
                AggregationPipelineStage.METADATA,
                unifiedVideoId,
                () -> repository.rebuildUnifiedPipelineMetadataRelations(unifiedVideoId),
                failures);
        runStage(
                AggregationPipelineStage.DYNAMIC_FIELD,
                unifiedVideoId,
                () -> repository.rebuildUnifiedPipelineDynamicFields(unifiedVideoId),
                failures);
        runStage(
                AggregationPipelineStage.CATEGORY,
                unifiedVideoId,
                () -> repository.rebuildUnifiedPipelineCategory(unifiedVideoId),
                failures);
        runStage(
                AggregationPipelineStage.ADULT_RESTRICTION,
                unifiedVideoId,
                () -> repository.rebuildUnifiedAdultAssessment(unifiedVideoId),
                failures);
        coverR2SyncImageIds.addAll(runCoverStage(unifiedVideoId, failures));

        return new AggregationPipelineExecution(unifiedVideoId, coverR2SyncImageIds, failures);
    }

    private void runStage(
            AggregationPipelineStage stage,
            UUID unifiedVideoId,
            Runnable action,
            List<AggregationPipelineStageFailure> failures) {
        try {
            stageTransaction.executeWithoutResult(ignored -> action.run());
        } catch (Exception ex) {
            failures.add(AggregationPipelineStageFailure.from(stage, ex));
            log.error(
                    "Aggregation pipeline handler [{}] failed for unifiedVideoId={}, stage={}",
                    stage.handlerName(),
                    unifiedVideoId,
                    failures.getLast().unifiedStageLabel(),
                    ex);
        }
    }

    private List<UUID> runCoverStage(
            UUID unifiedVideoId,
            List<AggregationPipelineStageFailure> failures) {
        return runReturningStage(
                AggregationPipelineStage.COVER_IMAGE,
                unifiedVideoId,
                () -> repository.rebuildUnifiedPipelineCoverImage(unifiedVideoId),
                failures);
    }

    private List<UUID> runReturningStage(
            AggregationPipelineStage stage,
            UUID unifiedVideoId,
            Supplier<List<UUID>> action,
            List<AggregationPipelineStageFailure> failures) {
        try {
            List<UUID> result = stageTransaction.execute(ignored -> action.get());
            return result == null ? List.of() : List.copyOf(result);
        } catch (Exception ex) {
            failures.add(AggregationPipelineStageFailure.from(stage, ex));
            log.error(
                    "Aggregation pipeline handler [{}] failed for unifiedVideoId={}, stage={}",
                    stage.handlerName(),
                    unifiedVideoId,
                    failures.getLast().unifiedStageLabel(),
                    ex);
            return List.of();
        }
    }

    private static TransactionOperations nestedStageTransaction(
            PlatformTransactionManager transactionManager,
            int timeoutSeconds) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        if (timeoutSeconds > 0) {
            transactionTemplate.setTimeout(timeoutSeconds);
        }
        return transactionTemplate;
    }

    private static PlatformTransactionManager unusedTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                throw new UnsupportedOperationException("test transaction manager should not be used");
            }

            @Override
            public void commit(TransactionStatus status) {
                throw new UnsupportedOperationException("test transaction manager should not be used");
            }

            @Override
            public void rollback(TransactionStatus status) {
                throw new UnsupportedOperationException("test transaction manager should not be used");
            }
        };
    }
}

enum AggregationPipelineStage {
    BASIC_ATTRIBUTE(10, "BasicAttributeHandler"),
    EXTERNAL_ID(20, "ExternalIdHandler"),
    METADATA(30, "MetadataHandler"),
    DYNAMIC_FIELD(40, "DynamicFieldHandler"),
    CATEGORY(50, "CategoryHandler"),
    ADULT_RESTRICTION(60, "AdultRestrictionHandler"),
    COVER_IMAGE(70, "CoverImagePipelineHandler");

    private final int v1Order;
    private final String handlerName;

    AggregationPipelineStage(int v1Order, String handlerName) {
        this.v1Order = v1Order;
        this.handlerName = handlerName;
    }

    int v1Order() {
        return v1Order;
    }

    String handlerName() {
        return handlerName;
    }
}

record AggregationPipelineStageFailure(
        AggregationPipelineStage stage,
        String handlerName,
        int v1Order,
        String message) {

    static AggregationPipelineStageFailure from(AggregationPipelineStage stage, Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return new AggregationPipelineStageFailure(stage, stage.handlerName(), stage.v1Order(), message);
    }

    String unifiedStageLabel() {
        return handlerName + "(" + v1Order + ")";
    }
}

record AggregationPipelineExecution(
        UUID unifiedVideoId,
        List<UUID> coverR2SyncImageIds,
        List<AggregationPipelineStageFailure> failures) {

    AggregationPipelineExecution {
        coverR2SyncImageIds = coverR2SyncImageIds == null ? List.of() : List.copyOf(coverR2SyncImageIds);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    boolean successful() {
        return failures.isEmpty();
    }
}
