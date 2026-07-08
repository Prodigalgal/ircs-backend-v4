package com.prodigalgal.ircs.interaction;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminMessageService {

    private static final Set<String> STATUSES = Set.of("PENDING", "REPLIED", "CLOSED");

    private final JdbcAdminMessageRepository repository;
    private final InteractionReadModelCache readModelCache;

    @Transactional(readOnly = true)
    public PageResponse<UserMessageResponse> findAll(
            String keyword,
            String status,
            Boolean publicMessage,
            int page,
            int size) {
        return repository.findMessages(
                PageBounds.of(page, size, 20, 100),
                normalizeKeyword(keyword),
                normalizeStatus(status),
                Optional.ofNullable(publicMessage));
    }

    @Transactional
    public UserMessageResponse reply(UUID messageId, AdminReplyRequest request) {
        if (messageId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "留言标识不能为空");
        }
        if (request == null || !StringUtils.hasText(request.reply())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "回复内容不能为空");
        }
        UserMessageResponse response = repository.reply(messageId, request.reply().trim(), Instant.now())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "留言不存在"));
        readModelCache.evictPublicFeedbackWall();
        return response;
    }

    @Transactional
    public UserMessageResponse toggleVisibility(UUID messageId, MessageVisibilityRequest request) {
        if (messageId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "留言标识不能为空");
        }
        if (request == null || request.publicMessage() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "public field missing");
        }
        UserMessageResponse response = repository.toggleVisibility(messageId, request.publicMessage(), Instant.now())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "留言不存在"));
        readModelCache.evictPublicFeedbackWall();
        return response;
    }

    @Transactional
    public void delete(UUID messageId) {
        if (messageId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "留言标识不能为空");
        }
        int deleted = repository.delete(messageId);
        if (deleted == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "留言不存在");
        }
        readModelCache.evictPublicFeedbackWall();
    }

    private Optional<String> normalizeKeyword(String keyword) {
        return Optional.ofNullable(keyword)
                .map(String::trim)
                .filter(StringUtils::hasText);
    }

    private Optional<String> normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return Optional.empty();
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "留言状态无效");
        }
        return Optional.of(normalized);
    }
}
