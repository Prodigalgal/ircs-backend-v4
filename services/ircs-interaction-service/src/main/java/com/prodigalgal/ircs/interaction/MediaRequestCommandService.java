package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
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
public class MediaRequestCommandService {

    private static final String DAILY_LIMIT_KEY = "member.media-request.daily-limit";
    private static final String POINT_COST_KEY = "member.media-request.point-cost";
    private static final int DEFAULT_DAILY_LIMIT = 5;
    private static final int DEFAULT_POINT_COST = 3;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_EXTRA_LENGTH = 500;
    private static final ZoneId DAILY_LIMIT_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcMediaRequestRepository repository;
    private final MemberPointLedger pointLedger;
    private final SystemConfigRepository systemConfigRepository;
    private final Environment environment;

    @Transactional
    public MediaRequestResponse submit(UUID memberId, MediaRequestSubmitRequest request) {
        if (request == null || !StringUtils.hasText(request.title())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "求片标题不能为空");
        }
        String title = cleanText(request.title(), MAX_TITLE_LENGTH, "求片标题");
        String normalizedTitle = normalizeTitle(title);
        Integer releaseYear = normalizeYear(request.releaseYear());
        String extraInfo = cleanOptionalText(request.extraInfo(), MAX_EXTRA_LENGTH);

        checkDailyLimit(memberId);
        int cost = pointCost();
        pointLedger.spend(memberId, cost, "MEDIA_REQUEST");
        return repository.upsert(
                IrcsUuidGenerators.nextId(),
                memberId,
                title,
                normalizedTitle,
                releaseYear,
                extraInfo,
                Instant.now(),
                cost);
    }

    @Transactional(readOnly = true)
    public PageResponse<MediaRequestResponse> myRequests(UUID memberId, PageBounds bounds) {
        return repository.findMemberRequests(memberId, bounds);
    }

    private void checkDailyLimit(UUID memberId) {
        int limit = dailyLimit();
        if (limit <= 0) {
            return;
        }
        LocalDate today = LocalDate.now(DAILY_LIMIT_ZONE);
        Instant startOfDay = today.atStartOfDay(DAILY_LIMIT_ZONE).toInstant();
        Instant endOfDay = today.plusDays(1).atStartOfDay(DAILY_LIMIT_ZONE).toInstant();
        long count = repository.countMemberRequestsCreatedBetween(memberId, startOfDay, endOfDay);
        if (count >= limit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "今日求片次数已达上限 (" + limit + "次)");
        }
    }

    private int dailyLimit() {
        return configInt(DAILY_LIMIT_KEY, DEFAULT_DAILY_LIMIT);
    }

    private int pointCost() {
        return Math.max(0, configInt(POINT_COST_KEY, DEFAULT_POINT_COST));
    }

    private int configInt(String key, int fallback) {
        Optional<String> injected = RuntimeInjectedConfig.find(environment, key);
        String raw = injected.or(() -> systemConfigRepository.findValue(key)).orElse(null);
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String cleanText(String value, int maxLength, String fieldName) {
        String cleaned = cleanHtml(value == null ? "" : value).trim();
        if (!StringUtils.hasText(cleaned)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldName + "不能为空");
        }
        if (cleaned.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldName + "过长");
        }
        return cleaned;
    }

    private String cleanOptionalText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String cleaned = cleanHtml(value).trim();
        if (cleaned.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "补充信息过长");
        }
        return StringUtils.hasText(cleaned) ? cleaned : null;
    }

    private Integer normalizeYear(Integer value) {
        if (value == null) {
            return null;
        }
        int currentYear = LocalDate.now(DAILY_LIMIT_ZONE).getYear() + 2;
        if (value < 1888 || value > currentYear) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "年份范围无效");
        }
        return value;
    }

    private String normalizeTitle(String title) {
        return title.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
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
