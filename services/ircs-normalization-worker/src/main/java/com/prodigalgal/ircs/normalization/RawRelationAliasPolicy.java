package com.prodigalgal.ircs.normalization;

import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class RawRelationAliasPolicy {

    private static final Map<String, List<String>> AREA_COMPOSITES = orderedMap(
            Map.entry("港澳台", List.of("香港", "澳门", "台湾")),
            Map.entry("港台", List.of("香港", "台湾")),
            Map.entry("日韩", List.of("日本", "韩国")),
            Map.entry("日韩欧美", List.of("日本", "韩国", "美国")),
            Map.entry("欧美", List.of("美国", "英国", "法国", "德国")),
            Map.entry("英美", List.of("英国", "美国")),
            Map.entry("大陆香港", List.of("中国大陆", "香港")),
            Map.entry("大陆台湾", List.of("中国大陆", "台湾")),
            Map.entry("新马", List.of("新加坡", "马来西亚")),
            Map.entry("新马泰", List.of("新加坡", "马来西亚", "泰国")));
    private static final Map<String, List<String>> AREA_ALIASES = orderedMap(
            Map.entry("大陆", List.of("中国大陆")),
            Map.entry("内地", List.of("中国大陆")),
            Map.entry("国内", List.of("中国大陆")),
            Map.entry("中国", List.of("中国大陆")),
            Map.entry("cn", List.of("中国大陆")),
            Map.entry("china", List.of("中国大陆")),
            Map.entry("prc", List.of("中国大陆")),
            Map.entry("国产", List.of("中国大陆")),
            Map.entry("mainland", List.of("中国大陆")),
            Map.entry("hk", List.of("香港")),
            Map.entry("hongkong", List.of("香港")),
            Map.entry("hong kong", List.of("香港")),
            Map.entry("tw", List.of("台湾")),
            Map.entry("taiwan", List.of("台湾")),
            Map.entry("jp", List.of("日本")),
            Map.entry("japan", List.of("日本")),
            Map.entry("kr", List.of("韩国")),
            Map.entry("korea", List.of("韩国")),
            Map.entry("th", List.of("泰国")),
            Map.entry("thailand", List.of("泰国")),
            Map.entry("sg", List.of("新加坡")),
            Map.entry("singapore", List.of("新加坡")),
            Map.entry("my", List.of("马来西亚")),
            Map.entry("malaysia", List.of("马来西亚")),
            Map.entry("uk", List.of("英国")),
            Map.entry("united kingdom", List.of("英国")),
            Map.entry("us", List.of("美国")),
            Map.entry("usa", List.of("美国")),
            Map.entry("america", List.of("美国")));
    private static final List<String> AREA_SUFFIXES = List.of("地区", "剧", "片");
    private static final Map<String, List<String>> AREA_LOCATION_HINTS = orderedMap(
            Map.entry("东京", List.of("日本")),
            Map.entry("大阪", List.of("日本")),
            Map.entry("京都", List.of("日本")),
            Map.entry("首尔", List.of("韩国")),
            Map.entry("釜山", List.of("韩国")),
            Map.entry("曼谷", List.of("泰国")),
            Map.entry("巴黎", List.of("法国")),
            Map.entry("伦敦", List.of("英国")),
            Map.entry("纽约", List.of("美国")),
            Map.entry("洛杉矶", List.of("美国")),
            Map.entry("台北", List.of("台湾")));
    private static final List<String> AREA_CANONICAL_TOKENS = List.of(
            "中国大陆", "中国香港", "中国台湾", "香港", "台湾", "澳门",
            "日本", "韩国", "美国", "英国", "法国", "德国", "泰国",
            "印度尼西亚", "印尼", "印度");

    private static final Map<String, List<String>> LANGUAGE_COMPOSITES = orderedMap(
            Map.entry("国粤双语", List.of("国语", "粤语")),
            Map.entry("国语粤语", List.of("国语", "粤语")),
            Map.entry("粤语国语", List.of("粤语", "国语")));
    private static final List<String> LANGUAGE_SUFFIXES = List.of("中字", "字幕", "配音");

    private static final Map<String, List<String>> GENRE_SYNONYMS = orderedMap(
            Map.entry("记录", List.of("纪录")),
            Map.entry("记录片", List.of("纪录")),
            Map.entry("纪录片", List.of("纪录")),
            Map.entry("微短剧", List.of("短剧")),
            Map.entry("爽剧", List.of("短剧")),
            Map.entry("动漫", List.of("动画")),
            Map.entry("动画片", List.of("动画")),
            Map.entry("国产动漫", List.of("动画")),
            Map.entry("日本动漫", List.of("动画")),
            Map.entry("日韩动漫", List.of("动画")),
            Map.entry("大陆综艺", List.of("综艺")),
            Map.entry("港台综艺", List.of("综艺")),
            Map.entry("日韩综艺", List.of("综艺")),
            Map.entry("卡通", List.of("动画")),
            Map.entry("二次元", List.of("动画")),
            Map.entry("武打", List.of("动作")),
            Map.entry("功夫", List.of("动作")),
            Map.entry("格斗", List.of("动作")),
            Map.entry("正剧", List.of("剧情")),
            Map.entry("情节", List.of("剧情")),
            Map.entry("纪实", List.of("纪录")),
            Map.entry("推理", List.of("悬疑")),
            Map.entry("解谜", List.of("悬疑")),
            Map.entry("烧脑", List.of("悬疑")),
            Map.entry("惊栗", List.of("惊悚")),
            Map.entry("惊险", List.of("惊悚")),
            Map.entry("综艺节目", List.of("综艺")),
            Map.entry("伦理片", List.of("伦理")),
            Map.entry("情色片", List.of("情色")));
    private static final List<String> GENRE_SUFFIXES = List.of("剧", "片");
    private static final Set<String> PROTECTED_GENRE_TERMS = Set.of(
            "短剧", "微短剧", "韩剧", "美剧", "日剧", "港剧", "台剧", "泰剧", "英剧", "内地剧", "国产剧", "短片");

    Set<String> expandGenres(Set<String> values) {
        Set<String> expanded = new LinkedHashSet<>();
        for (String value : safe(values)) {
            expanded.add(value);
            List<String> synonyms = GENRE_SYNONYMS.get(value);
            if (synonyms != null) {
                expanded.addAll(synonyms);
            }
            if (!PROTECTED_GENRE_TERMS.contains(value)) {
                stripSuffix(value, GENRE_SUFFIXES, expanded);
            }
        }
        return expanded;
    }

    Set<String> sourceCategoryGenreValues(String value) {
        if (!StringUtils.hasText(value) || StandardContentCategoryClassifier.isTopCategoryName(value)) {
            return Set.of();
        }
        return expandGenres(Set.of(value.trim()));
    }

    Set<String> expandAreas(Set<String> values) {
        Set<String> expanded = new LinkedHashSet<>();
        for (String value : safe(values)) {
            expanded.add(value);
            List<String> composite = AREA_COMPOSITES.get(value);
            if (composite != null) {
                expanded.addAll(composite);
            }
            List<String> aliases = AREA_ALIASES.get(value.toLowerCase(java.util.Locale.ROOT));
            if (aliases != null) {
                expanded.addAll(aliases);
            }
            addContainsMatches(value, AREA_CANONICAL_TOKENS, expanded);
            addLocationHints(value, expanded);
            stripSuffix(value, AREA_SUFFIXES, expanded);
        }
        return expanded;
    }

    Set<String> expandLanguages(Set<String> values) {
        Set<String> expanded = new LinkedHashSet<>();
        for (String value : safe(values)) {
            expanded.add(value);
            List<String> composite = LANGUAGE_COMPOSITES.get(value);
            if (composite != null) {
                expanded.addAll(composite);
            }
            stripSuffix(value, LANGUAGE_SUFFIXES, expanded);
        }
        return expanded;
    }

    private static Set<String> safe(Set<String> values) {
        return values == null ? Set.of() : values;
    }

    private static void stripSuffix(String value, List<String> suffixes, Set<String> expanded) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        for (String suffix : suffixes) {
            if (value.endsWith(suffix) && value.length() > suffix.length()) {
                String stripped = value.substring(0, value.length() - suffix.length()).trim();
                if (stripped.length() > 1) {
                    expanded.add(stripped);
                }
            }
        }
    }

    private static void addContainsMatches(String value, List<String> tokens, Set<String> expanded) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        for (String token : tokens) {
            if (!value.equals(token) && value.contains(token)) {
                expanded.add(token);
            }
        }
    }

    private static void addLocationHints(String value, Set<String> expanded) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        AREA_LOCATION_HINTS.forEach((hint, areas) -> {
            if (value.contains(hint)) {
                expanded.addAll(areas);
            }
        });
    }

    @SafeVarargs
    private static <K, V> Map<K, V> orderedMap(Map.Entry<K, V>... entries) {
        Map<K, V> map = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }
}
