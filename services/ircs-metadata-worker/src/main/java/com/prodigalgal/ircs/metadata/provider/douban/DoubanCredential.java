package com.prodigalgal.ircs.metadata.provider.douban;

import java.util.UUID;

public record DoubanCredential(
        UUID id,
        String cookie,
        String userAgent,
        Integer rateLimit,
        String rateLimitUnit,
        Integer priority) {
}
