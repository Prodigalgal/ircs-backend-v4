package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FeedbackCommandService {

    private static final String DAILY_LIMIT_KEY = "member.message.daily-limit";
    private static final String POINT_COST_KEY = "member.message.point-cost";
    private static final int DEFAULT_DAILY_LIMIT = 5;
    private static final int DEFAULT_POINT_COST = 1;
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final ZoneId DAILY_LIMIT_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcFeedbackRepository repository;
    private final MemberPointLedger pointLedger;
    private final SystemConfigRepository systemConfigRepository;
    private final Environment environment;

    @Transactional
    public UserMessageResponse submit(UUID memberId, FeedbackSubmitRequest request) {
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "留言内容不能为空");
        }
        String rawContent = request.content().trim();
        if (rawContent.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "留言内容过长 (最多500字)");
        }

        checkDailyLimit(memberId);
        String safeContent = cleanHtml(rawContent).trim();
        if (!StringUtils.hasText(safeContent)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "留言内容无效或包含非法字符");
        }

        pointLedger.spend(memberId, pointCost(), "MESSAGE");
        return repository.insertMessage(IrcsUuidGenerators.nextId(), memberId, safeContent, Instant.now());
    }

    @Transactional(readOnly = true)
    public PageResponse<UserMessageResponse> myFeedback(UUID memberId, PageBounds bounds) {
        return repository.findMemberMessages(memberId, bounds);
    }

    @Transactional
    public void delete(UUID memberId, UUID messageId) {
        if (messageId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "留言标识不能为空");
        }
        repository.deleteMemberMessage(memberId, messageId);
    }

    private void checkDailyLimit(UUID memberId) {
        int limit = dailyLimit();
        if (limit <= 0) {
            return;
        }
        LocalDate today = LocalDate.now(DAILY_LIMIT_ZONE);
        Instant startOfDay = today.atStartOfDay(DAILY_LIMIT_ZONE).toInstant();
        Instant endOfDay = today.plusDays(1).atStartOfDay(DAILY_LIMIT_ZONE).toInstant();
        long count = repository.countMemberMessagesCreatedBetween(memberId, startOfDay, endOfDay);
        if (count >= limit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "今日留言次数已达上限 (" + limit + "次)");
        }
    }

    private int dailyLimit() {
        return configInt(DAILY_LIMIT_KEY, DEFAULT_DAILY_LIMIT);
    }

    private int pointCost() {
        return Math.max(0, configInt(POINT_COST_KEY, DEFAULT_POINT_COST));
    }

    private int configInt(String key, int fallback) {
        Optional<String> injectedLimit = RuntimeInjectedConfig.find(environment, key);
        if (injectedLimit.isPresent()) {
            return parseLimit(injectedLimit.get().trim(), fallback);
        }
        return systemConfigRepository.findValue(key)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(raw -> parseLimit(raw, fallback))
                .orElse(fallback);
    }

    private int parseLimit(String rawValue, int fallback) {
        try {
            return Integer.parseInt(rawValue);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String cleanHtml(String input) {
        String decoded = decodeBasicEntities(input);
        String withoutScripts = decoded.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
        String withoutComments = withoutScripts.replaceAll("(?s)<!--.*?-->", "");
        return withoutComments.replaceAll("(?s)<[^>]*>", "");
    }

    private String decodeBasicEntities(String input) {
        return input.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'");
    }
}
