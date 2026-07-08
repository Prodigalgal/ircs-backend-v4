package com.prodigalgal.ircs.common.adult;

import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class AdultContentAssessor {

    public static final String RULE_VERSION = "adult-assessment-v2";

    private static final Set<String> HIGH_CONFIDENCE_KEYWORDS = Set.of(
            "adult", "xxx", "r18", "18+", "jav", "fc2", "heyzo", "1pondo", "caribbeancom",
            "tokyo-hot", "pacopacom", "一本道", "东京热", "加勒比", "成人", "成人视频",
            "无码", "有码", "女优", "麻豆", "国产自拍", "国产传媒", "糖心", "天美", "蜜桃",
            "星空", "皇家华人", "91制片", "swag", "玩偶姐姐", "口交", "肛交", "深喉",
            "足交", "性交", "性爱", "做爱", "自慰", "调教", "乱伦", "强奸", "强上",
            "中出", "颜射", "内射", "潮吹", "无套", "约炮", "揉奶", "舔逼", "骚逼",
            "抠逼", "掰穴", "小穴", "鸡巴", "假jb", "爆操", "求操", "假吊",
            "插穴", "插入", "互插", "娇喘", "呻吟", "淫荡", "观音坐莲", "制服诱惑",
            "情趣制服", "女上位", "喷水", "车震", "骑乘", "打桩", "约啪", "骚货",
            "淫乱", "抽插", "高潮");
    private static final Set<String> MEDIUM_CONFIDENCE_KEYWORDS = Set.of(
            "情色", "伦理", "伦理片", "情色片", "写真", "gravure", "自拍泄密",
            "最新流出", "探花", "巨乳", "大奶", "熟女", "少妇", "人妻", "福利",
            "情趣", "露出", "学生妹", "萝莉", "嫩妹", "嫩妹妹", "jk", "大秀",
            "偷拍", "街拍", "外围", "包臀裙", "黑丝", "白丝", "御姐", "约拍");
    private static final Set<String> FALSE_POSITIVE_WORDS = Set.of(
            "avatar", "avengers", "java", "javascript", "travel", "cave", "available");
    private static final Pattern TOKEN_AV = Pattern.compile("(?iu)(?<![a-z0-9])av(?![a-z0-9])");
    private static final Pattern EXPLICIT_CATALOG_CODE = Pattern.compile(
            "(?iu)(?<![a-z0-9])(?:"
                    + "fc2[-_\\s]*(?:ppv[-_\\s]*)?\\d{3,}"
                    + "|heyzo[-_\\s]*\\d{3,}"
                    + "|(?:ssis|ipx|mide|jul|juq|abp|abw|dvaj|stars|fsdss|pred|ipzz|miaa|meyd"
                    + "|adn|sone|waaa|cawd|dvdms|nacr|pppd|ebod|midv|atid|iptd|venu|dass"
                    + "|vec|mukd|mird|rctd|sdde|ymdd|hnd|ngod|shkd|dasd|jufe|mifd)"
                    + "[-_\\s]*\\d{2,5})(?![a-z0-9])");

    private AdultContentAssessor() {
    }

    public static AdultAssessment assess(AdultAssessmentInput input) {
        if (input == null) {
            return AdultAssessment.safe(RULE_VERSION);
        }
        List<AdultAssessmentSignal> signals = new ArrayList<>();
        assessCanonicalCategory(input, signals);
        assessTextField("unified", "title", input.title(), 90, 55, signals);
        assessTextField("unified", "aliasTitle", input.aliasTitle(), 85, 50, signals);
        assessTextField("unified", "description", input.description(), 75, 45, signals);
        assessTextField("unified", "remarks", input.remarks(), 75, 45, signals);
        assessTextField("unified", "subtitle", input.subtitle(), 75, 45, signals);
        assessCollection("unified", "actorNames", input.actorNames(), 70, 40, signals);
        assessCollection("unified", "directorNames", input.directorNames(), 65, 35, signals);
        assessCollection("unified", "genreCodes", input.genreCodes(), 70, 45, signals);
        for (AdultAssessmentInput.SourceEvidence source : input.sources()) {
            assessSource(source, signals);
        }
        return summarizeSignals(signals);
    }

    public static AdultAssessment summarizeSignals(Collection<AdultAssessmentSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return AdultAssessment.safe(RULE_VERSION);
        }
        return summarize(new ArrayList<>(signals));
    }

    private static void assessCanonicalCategory(
            AdultAssessmentInput input,
            List<AdultAssessmentSignal> signals) {
        if (StandardContentCategoryClassifier.ADULT.equalsIgnoreCase(trim(input.categoryCode()))) {
            signals.add(signal("unified", "categoryCode", input.categoryCode(), 96, "标准分类已归为成人"));
        }
        StandardContentCategoryClassifier.inferCode(input.categoryCode(), input.categoryName())
                .filter(StandardContentCategoryClassifier.ADULT::equals)
                .ifPresent(ignored -> signals.add(signal(
                        "unified",
                        "categoryName",
                        input.categoryName(),
                        90,
                        "标准分类名称命中成人类目")));
    }

    private static void assessSource(
            AdultAssessmentInput.SourceEvidence source,
            List<AdultAssessmentSignal> signals) {
        if (source == null) {
            return;
        }
        if (source.dataSourceAdultRestricted()) {
            signals.add(signal(
                    "source",
                    "dataSourceAdultRestricted",
                    source.dataSourceName(),
                    100,
                    "资源站已标记为成人限制资源站"));
        }
        StandardContentCategoryClassifier.inferCode(source.sourceCategoryCode(), source.sourceCategoryName())
                .filter(StandardContentCategoryClassifier.ADULT::equals)
                .ifPresent(ignored -> signals.add(signal(
                        "source",
                        "sourceCategory",
                        firstText(source.sourceCategoryName(), source.sourceCategoryCode()),
                        92,
                        "源站分类命中成人类目")));
        assessTextField("source", "dataSourceName", source.dataSourceName(), 85, 50, signals);
        assessTextField("source", "sourceDomain", source.sourceDomain(), 88, 45, signals);
        assessTextField("source", "rawMetadata", source.rawMetadata(), 70, 35, signals);
    }

    private static void assessCollection(
            String source,
            String field,
            Collection<String> values,
            int highScore,
            int mediumScore,
            List<AdultAssessmentSignal> signals) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            assessTextField(source, field, value, highScore, mediumScore, signals);
        }
    }

    private static void assessTextField(
            String source,
            String field,
            String value,
            int highScore,
            int mediumScore,
            List<AdultAssessmentSignal> signals) {
        String normalized = normalize(value);
        if (!StringUtils.hasText(normalized) || isFalsePositive(normalized)) {
            return;
        }
        for (String keyword : HIGH_CONFIDENCE_KEYWORDS) {
            if (containsKeyword(normalized, keyword)) {
                signals.add(signal(source, field, keyword, highScore, "命中高置信成人关键词"));
                return;
            }
        }
        if (TOKEN_AV.matcher(normalized).find()) {
            signals.add(signal(source, field, "av", Math.min(highScore, 82), "命中独立 AV token"));
            return;
        }
        if (EXPLICIT_CATALOG_CODE.matcher(normalized).find()) {
            signals.add(signal(source, field, "catalog-code", Math.min(highScore, 88), "命中成人站番号格式"));
            return;
        }
        List<String> mediumMatches = new ArrayList<>();
        for (String keyword : MEDIUM_CONFIDENCE_KEYWORDS) {
            if (containsKeyword(normalized, keyword)) {
                mediumMatches.add(keyword);
            }
        }
        List<String> distinctMediumMatches = distinctLongestMatches(mediumMatches);
        if (distinctMediumMatches.size() >= 2) {
            signals.add(signal(
                    source,
                    field,
                    String.join(",", distinctMediumMatches.subList(0, Math.min(3, distinctMediumMatches.size()))),
                    Math.min(highScore, 86),
                    "命中多项成人上下文词"));
        } else if (distinctMediumMatches.size() == 1) {
            signals.add(signal(source, field, distinctMediumMatches.getFirst(), mediumScore, "命中中置信成人倾向词"));
        }
    }

    private static AdultAssessment summarize(List<AdultAssessmentSignal> signals) {
        if (signals.isEmpty()) {
            return AdultAssessment.safe(RULE_VERSION);
        }
        int max = signals.stream()
                .mapToInt(AdultAssessmentSignal::score)
                .max()
                .orElse(0);
        int bonus = Math.min(15, Math.max(0, signals.size() - 1) * 5);
        int confidence = Math.min(100, max + bonus);
        AdultAssessmentLevel level = confidence >= 85
                ? AdultAssessmentLevel.ADULT
                : confidence >= 50 ? AdultAssessmentLevel.SUSPECT : AdultAssessmentLevel.SAFE;
        return new AdultAssessment(
                RULE_VERSION,
                level,
                level == AdultAssessmentLevel.ADULT,
                confidence,
                signals);
    }

    private static boolean containsKeyword(String normalized, String keyword) {
        String normalizedKeyword = normalize(keyword);
        if (!StringUtils.hasText(normalizedKeyword)) {
            return false;
        }
        if (isAsciiWord(normalizedKeyword)) {
            Pattern token = Pattern.compile("(?iu)(?<![a-z0-9])" + Pattern.quote(normalizedKeyword) + "(?![a-z0-9])");
            return token.matcher(normalized).find();
        }
        return normalized.contains(normalizedKeyword);
    }

    private static boolean isAsciiWord(String value) {
        return value.chars().allMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '+');
    }

    private static boolean isFalsePositive(String normalized) {
        for (String word : FALSE_POSITIVE_WORDS) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> distinctLongestMatches(List<String> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        return matches.stream()
                .distinct()
                .filter(match -> matches.stream()
                        .distinct()
                        .noneMatch(other -> !other.equals(match) && other.contains(match)))
                .toList();
    }

    private static AdultAssessmentSignal signal(
            String source,
            String field,
            String matchedValue,
            int score,
            String reason) {
        return new AdultAssessmentSignal(source, field, matchedValue, score, reason);
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('－', '-');
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }
}
