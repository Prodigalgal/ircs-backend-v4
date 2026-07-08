package com.prodigalgal.ircs.task.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.task.dto.TaskItemLogResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TaskLogService {

    private static final String LOG_PREFIX = "task:logs:";
    private static final int MAX_LIMIT = 200;
    private static final int MAX_LOGS_PER_TASK = 5000;
    private static final Duration LOG_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public List<TaskItemLogResponse> getLogs(UUID taskId, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
        List<String> rawLogs = redisTemplate.opsForList()
                .range(key(taskId), safeOffset, (long) safeOffset + safeLimit - 1);
        if (rawLogs == null || rawLogs.isEmpty()) {
            return List.of();
        }
        return rawLogs.stream().map(this::parseLog).toList();
    }

    public void clearLogs(UUID taskId) {
        redisTemplate.delete(key(taskId));
    }

    public void appendLog(UUID taskId, TaskItemLogResponse log) {
        try {
            String key = key(taskId);
            redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(log));
            redisTemplate.opsForList().trim(key, 0, MAX_LOGS_PER_TASK - 1);
            redisTemplate.expire(key, LOG_TTL);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task log payload is not serializable", ex);
        }
    }

    private TaskItemLogResponse parseLog(String raw) {
        try {
            var json = objectMapper.readTree(normalizeJson(raw));
            return new TaskItemLogResponse(
                    text(json, "timestamp"),
                    text(json, "level"),
                    text(json, "sourceVid"),
                    text(json, "message"));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task log payload is not valid JSON", ex);
        }
    }

    private String normalizeJson(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        if (!normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private String text(com.fasterxml.jackson.databind.JsonNode json, String field) {
        var value = json.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String key(UUID taskId) {
        return LOG_PREFIX + taskId;
    }
}
