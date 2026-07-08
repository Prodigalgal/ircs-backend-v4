package com.prodigalgal.ircs.search.support;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class SearchTextHelper {

    private static final int MAX_VARIANT_COUNT = 64;
    private static final Pattern BRACKET_PATTERN = Pattern.compile("[\\[\\]【】()（）{}《》<>「」『』]");
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[\\p{Punct}，。！？、：；·“”‘’]");
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");

    private SearchTextHelper() {
    }

    public static String normalizeTitleForSearch(String input) {
        if (!StringUtils.hasText(input)) {
            return null;
        }
        String normalized = Normalizer.normalize(input.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        normalized = BRACKET_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = PUNCTUATION_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    public static List<String> buildTitleVariants(String title, String aliasTitle, String subtitle, String year, Integer season) {
        Set<String> variants = new LinkedHashSet<>();
        addVariant(variants, title);
        addVariant(variants, aliasTitle);
        addVariant(variants, subtitle);

        if (StringUtils.hasText(title) && StringUtils.hasText(subtitle)) {
            addVariant(variants, title + " " + subtitle);
            addVariant(variants, title + subtitle);
            addVariant(variants, title + ": " + subtitle);
            addVariant(variants, title + " - " + subtitle);
        }
        if (StringUtils.hasText(aliasTitle) && StringUtils.hasText(subtitle)) {
            addVariant(variants, aliasTitle + " " + subtitle);
            addVariant(variants, aliasTitle + subtitle);
            addVariant(variants, aliasTitle + ": " + subtitle);
            addVariant(variants, aliasTitle + " - " + subtitle);
        }

        addSeasonSubtitleVariants(variants, title, subtitle, season);
        addSeasonVariants(variants, title, season);
        addSeasonSubtitleVariants(variants, aliasTitle, subtitle, season);
        addSeasonVariants(variants, aliasTitle, season);

        if (StringUtils.hasText(year)) {
            addVariant(variants, title + " " + year.trim());
            addVariant(variants, aliasTitle + " " + year.trim());
        }

        List<String> result = new ArrayList<>(variants.size());
        for (String variant : variants) {
            if (result.size() >= MAX_VARIANT_COUNT) {
                break;
            }
            result.add(variant);
        }
        return result;
    }

    public static List<String> collectExternalIds(String... ids) {
        Set<String> result = new LinkedHashSet<>();
        if (ids == null) {
            return List.of();
        }
        for (String id : ids) {
            if (StringUtils.hasText(id)) {
                result.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(result);
    }

    private static void addSeasonVariants(Set<String> variants, String title, Integer season) {
        if (!StringUtils.hasText(title) || season == null || season <= 0) {
            return;
        }
        addVariant(variants, title + season);
        addVariant(variants, title + " " + season);
        addVariant(variants, title + " 第" + season + "季");
        addVariant(variants, title + "第" + season + "季");
        addVariant(variants, title + " 第" + toChineseNumber(season) + "季");
        addVariant(variants, title + " s" + season);
    }

    private static void addSeasonSubtitleVariants(Set<String> variants, String title, String subtitle, Integer season) {
        if (!StringUtils.hasText(title) || !StringUtils.hasText(subtitle) || season == null || season <= 0) {
            return;
        }
        addVariant(variants, title + " 第" + season + "季 " + subtitle);
        addVariant(variants, title + "第" + season + "季" + subtitle);
        addVariant(variants, title + " 第" + toChineseNumber(season) + "季 " + subtitle);
        addVariant(variants, title + " Season " + season + " " + subtitle);
        addVariant(variants, title + " S" + season + " " + subtitle);
    }

    private static void addVariant(Set<String> variants, String value) {
        String normalized = normalizeTitleForSearch(value);
        if (normalized != null) {
            variants.add(normalized);
        }
        if (StringUtils.hasText(value)) {
            variants.add(value.trim());
        }
    }

    private static String toChineseNumber(int value) {
        return switch (value) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            case 4 -> "四";
            case 5 -> "五";
            case 6 -> "六";
            case 7 -> "七";
            case 8 -> "八";
            case 9 -> "九";
            case 10 -> "十";
            default -> String.valueOf(value);
        };
    }
}
