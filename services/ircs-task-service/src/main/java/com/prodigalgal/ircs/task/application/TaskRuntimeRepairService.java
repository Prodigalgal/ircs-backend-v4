package com.prodigalgal.ircs.task.application;






import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.runtime.TaskProgressRedisService;
import com.prodigalgal.ircs.task.domain.TaskExecutionPlan;
import com.prodigalgal.ircs.task.domain.TaskRuntimeStatus;
import com.prodigalgal.ircs.task.runtime.MasterProgressState;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskRuntimeRepairService {

    private final JdbcCollectionTaskRepository taskRepository;
    private final TaskProgressRedisService progressService;
    private final TaskQueueDispatchService dispatchService;
    private final TaskSnapshotFlushService snapshotFlushService;

    public TaskRuntimeRepairResult repairStuckActiveMasters(int batchSize) {
        List<UUID> taskIds = taskRepository.findActiveTaskIds(batchSize);
        int repaired = 0;
        int finalized = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID taskId : taskIds) {
            try {
                RepairOutcome outcome = repairOne(taskId);
                switch (outcome) {
                    case REPAIRED -> repaired++;
                    case FINALIZED -> finalized++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Task runtime repair failed: masterTaskId={}", taskId, ex);
            }
        }
        return new TaskRuntimeRepairResult(taskIds.size(), repaired, finalized, skipped, failed);
    }

    private RepairOutcome repairOne(UUID taskId) {
        TaskExecutionPlan plan = taskRepository.findExecutionPlan(taskId).orElse(null);
        if (plan == null || !allowsPageExpansion(plan.status())) {
            return RepairOutcome.SKIPPED;
        }
        MasterProgressState progress = progressService.masterProgress(taskId).orElse(null);
        if (progress == null || !progress.completedCurrentSlice()) {
            return RepairOutcome.SKIPPED;
        }
        int completedPage = completedPage(plan, progress);
        if (completedPage < 1) {
            return RepairOutcome.SKIPPED;
        }
        DispatchNextPageResult result = dispatchService.dispatchNextPageIfNeeded(
                taskId,
                completedPage,
                progress.totalPages(),
                taskId.toString());
        if (result == DispatchNextPageResult.DISPATCHED) {
            return RepairOutcome.REPAIRED;
        }
        if (result == DispatchNextPageResult.NO_MORE_PAGES) {
            snapshotFlushService.flushOne(taskId);
            return RepairOutcome.FINALIZED;
        }
        return RepairOutcome.SKIPPED;
    }

    private int completedPage(TaskExecutionPlan plan, MasterProgressState progress) {
        if (plan.currentPage() != null && plan.currentPage() > 0) {
            return plan.currentPage();
        }
        if (progress.startPage() != null && progress.startPage() > 0) {
            return progress.startPage();
        }
        return plan.startPage() != null && plan.startPage() > 0 ? plan.startPage() : 1;
    }

    private boolean allowsPageExpansion(String status) {
        return TaskRuntimeStatus.allowsPageExpansion(status);
    }

    private enum RepairOutcome {
        REPAIRED,
        FINALIZED,
        SKIPPED
    }
}
