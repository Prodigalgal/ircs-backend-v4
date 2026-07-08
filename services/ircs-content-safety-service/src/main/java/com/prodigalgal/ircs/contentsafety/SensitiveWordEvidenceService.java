package com.prodigalgal.ircs.contentsafety;

import com.prodigalgal.ircs.common.adult.AdultAssessmentSignal;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentItem;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
class SensitiveWordEvidenceService {

    private static final String HELPER_CLASS = "com.github.houbb.sensitive.word.core.SensitiveWordHelper";

    private final Method findFirstMethod;

    SensitiveWordEvidenceService() {
        this.findFirstMethod = findFindFirstMethod();
    }

    List<AdultAssessmentSignal> scan(AdultAssessmentItem item, int maxTextLength) {
        if (item == null || findFirstMethod == null) {
            return List.of();
        }
        List<AdultAssessmentSignal> signals = new ArrayList<>();
        scanField(signals, "unified", "title", item.title(), maxTextLength, 88);
        scanField(signals, "unified", "subtitle", item.subtitle(), maxTextLength, 82);
        scanField(signals, "unified", "aliasTitle", item.aliasTitle(), maxTextLength, 82);
        return signals;
    }

    private void scanField(
            List<AdultAssessmentSignal> signals,
            String source,
            String field,
            String rawValue,
            int maxTextLength,
            int score) {
        String value = truncate(normalize(rawValue), maxTextLength);
        if (!StringUtils.hasText(value)) {
            return;
        }
        List<String> matches = findMatches(value);
        for (String match : matches) {
            if (!StringUtils.hasText(match)) {
                continue;
            }
            signals.add(new AdultAssessmentSignal(
                    "sensitive-word:" + source,
                    field,
                    match,
                    score,
                    "sensitive-word 命中成人内容词"));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> findMatches(String value) {
        try {
            Object result = findFirstMethod.invoke(null, value);
            if (result instanceof String match && StringUtils.hasText(match)) {
                return List.of(match);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.debug("sensitive-word scan failed: {}", ex.getMessage());
        }
        return List.of();
    }

    private static Method findFindFirstMethod() {
        try {
            Class<?> helper = Class.forName(HELPER_CLASS);
            return helper.getMethod("findFirst", String.class);
        } catch (ReflectiveOperationException ex) {
            log.warn("sensitive-word helper is not available: {}", ex.getMessage());
            return null;
        }
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int maxTextLength) {
        int max = maxTextLength <= 0 ? 4096 : maxTextLength;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
