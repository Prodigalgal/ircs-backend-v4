package com.prodigalgal.ircs.config.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ConfigConnectivityService {

    private static final Set<String> SUPPORTED_TYPES = Set.of("R2", "PROXY", "MAIL", "LLM");

    public Map<String, String> testConnection(String type, Map<String, Object> params) {
        String normalizedType = normalizeType(type);
        if (!SUPPORTED_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("Unknown test type: " + type);
        }
        int fieldCount = params == null ? 0 : params.size();
        return Map.of(
                "message", "连接成功",
                "type", normalizedType,
                "mode", "dry-run",
                "detail", "未访问外部生产资源，仅完成参数接收与服务端契约校验",
                "fieldCount", String.valueOf(fieldCount));
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Connection test type is required");
        }
        return type.trim().toUpperCase(Locale.ROOT);
    }
}
