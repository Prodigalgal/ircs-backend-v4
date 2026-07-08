package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.contracts.task.TaskRuntimeHotKeys;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
class TaskRuntimeControlService {

    private final StringRedisTemplate redisTemplate;

    TaskRuntimeControlService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    boolean canRun(UUID masterTaskId) {
        if (masterTaskId == null) {
            return false;
        }
        Object status = redisTemplate.opsForHash().get(TaskRuntimeHotKeys.masterState(masterTaskId), "status");
        return !isBlocked(status == null ? null : status.toString());
    }

    private boolean isBlocked(String status) {
        String normalized = status == null || status.isBlank() ? "" : status.trim().toUpperCase();
        return switch (normalized) {
            case "PAUSED", "STOPPING", "FAILED", "COMPLETED", "COMPLETED_WITH_ERRORS" -> true;
            default -> false;
        };
    }
}
