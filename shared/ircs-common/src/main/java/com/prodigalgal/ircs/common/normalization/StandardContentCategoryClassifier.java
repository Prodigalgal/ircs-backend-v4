package com.prodigalgal.ircs.common.normalization;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class StandardContentCategoryClassifier {

    public static final String MOVIE = "movie";
    public static final String SERIES = "series";
    public static final String SHORT_DRAMA = "short-drama";
    public static final String ANIME = "anime";
    public static final String VARIETY = "variety";
    public static final String DOCUMENTARY = "documentary";
    public static final String SPORTS = "sports";
    public static final String NEWS = "news";
    public static final String EDUCATION = "education";
    public static final String MUSIC = "music";
    public static final String ADULT = "adult";
    public static final String OTHER = "other";

    private static final List<Match> STABLE_CATEGORIES = List.of(
            new Match(MOVIE, "电影"),
            new Match(SERIES, "剧集"),
            new Match(SHORT_DRAMA, "短剧"),
            new Match(ANIME, "动漫"),
            new Match(VARIETY, "综艺"),
            new Match(DOCUMENTARY, "纪录片"),
            new Match(SPORTS, "体育赛事"),
            new Match(NEWS, "新闻资讯"),
            new Match(EDUCATION, "教育知识"),
            new Match(MUSIC, "音乐演出"),
            new Match(ADULT, "成人"),
            new Match(OTHER, "其他"));

    private static final Set<String> ALLOWED_CODES = Set.of(
            MOVIE,
            SERIES,
            SHORT_DRAMA,
            ANIME,
            VARIETY,
            DOCUMENTARY,
            SPORTS,
            NEWS,
            EDUCATION,
            MUSIC,
            ADULT,
            OTHER);
    private static final Map<String, String> CANONICAL_NAMES = STABLE_CATEGORIES.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Match::code, Match::name));
    private static final Set<String> TOP_CATEGORY_NAMES = Set.of(
            "movie", "film", "cinema", "电影", "影片",
            "series", "tv", "teleplay", "电视剧", "连续剧", "剧集",
            "short-drama", "shortdrama", "shorts", "短剧", "微短剧",
            "anime", "animation", "cartoon", "动漫", "动画", "番剧",
            "variety", "综艺",
            "documentary", "docu", "纪录", "纪录片", "记录", "记录片",
            "sports", "sport", "体育", "体育赛事", "赛事",
            "news", "资讯", "新闻", "新闻资讯",
            "education", "知识", "教育", "公开课", "课程", "教程",
            "music", "音乐", "演唱会", "mv", "音乐演出",
            "adult", "成人", "成人视频", "情色", "伦理",
            "other", "其他", "其它", "未分类", "综合");
    private static final List<CategoryRule> RULES = List.of(
            rule(SHORT_DRAMA, "短剧", 100,
                    Set.of("shortdrama", "shorts", "短剧", "微短剧", "爽剧", "爽文", "漫剧", "ai漫剧",
                            "逆袭", "女频", "男频", "重生", "赘婿", "脑洞", "闪婚", "都市",
                            "总裁", "言情", "穿越", "年代", "复仇", "战神", "神医", "萌宝",
                            "甜宠", "虐恋", "仙侠", "反转", "霸总", "龙王", "兵王"),
                    Set.of("电影", "影片", "电视剧", "连续剧", "剧集", "动漫", "动画", "番剧")),
            rule(ANIME, "动漫", 95,
                    Set.of("anime", "animation", "cartoon", "动漫", "动画", "番剧", "国漫", "日漫",
                            "卡通", "二次元", "剧场版", "有声动漫", "国产动漫", "日本动漫",
                            "日韩动漫", "欧美动漫"),
                    Set.of("短剧", "真人秀", "综艺")),
            rule(DOCUMENTARY, "纪录片", 95,
                    Set.of("documentary", "docu", "纪录", "纪录片", "记录", "记录片", "纪实"),
                    Set.of("资讯", "新闻")),
            rule(SPORTS, "体育赛事", 92,
                    Set.of("sports", "sport", "体育", "体育赛事", "赛事", "足球", "篮球", "网球", "排球",
                            "乒乓", "羽毛球", "斯诺克", "台球", "赛车", "f1", "nba", "cba", "世界杯",
                            "欧冠", "英超", "西甲", "中超", "搏击", "拳击", "ufc", "wwe", "电竞"),
                    Set.of()),
            rule(VARIETY, "综艺", 90,
                    Set.of("variety", "综艺", "大陆综艺", "港台综艺", "日韩综艺", "欧美综艺",
                            "真人秀", "晚会", "访谈", "脱口秀", "选秀", "相声", "小品"),
                    Set.of("纪录", "记录")),
            rule(NEWS, "新闻资讯", 88,
                    Set.of("news", "新闻", "资讯", "新闻资讯", "时政", "财经", "军事", "社会", "国际新闻",
                            "国内新闻", "快讯", "新闻联播"),
                    Set.of()),
            rule(EDUCATION, "教育知识", 86,
                    Set.of("education", "教育", "知识", "公开课", "课程", "教程", "教学", "讲座", "课堂",
                            "学习", "培训", "纪录课堂", "百科"),
                    Set.of()),
            rule(MUSIC, "音乐演出", 84,
                    Set.of("music", "音乐", "演唱会", "mv", "mtv", "音乐会", "歌舞", "live", "现场",
                            "音乐演出", "舞台"),
                    Set.of()),
            rule(SERIES, "剧集", 80,
                    Set.of("series", "tv", "teleplay", "电视剧", "连续剧", "剧集", "剧场", "国产剧",
                            "内地剧", "大陆剧", "国剧", "港剧", "香港剧", "港澳剧", "台剧",
                            "台湾剧", "韩剧", "韩国剧", "日剧", "日本剧", "美剧", "欧美剧",
                            "英剧", "海外剧", "泰剧", "马泰剧", "网剧", "自制剧"),
                    Set.of("短剧", "微短剧", "动漫", "动画", "电影", "影片", "纪录", "记录")),
            rule(MOVIE, "电影", 70,
                    Set.of("movie", "film", "cinema", "电影", "影片", "大片", "微电影", "短片",
                            "剧情片", "喜剧片", "动作片", "爱情片", "科幻片", "恐怖片", "战争片",
                            "犯罪片", "灾难片", "悬疑片", "惊悚片", "奇幻片", "冒险片", "历史片",
                            "古装片", "家庭片", "西部片", "伦理片", "情色片", "邵氏"),
                    Set.of("电视剧", "连续剧", "剧集", "短剧", "微短剧", "动漫", "动画", "综艺",
                            "纪录", "记录")),
            rule(ADULT, "成人", 98,
                    Set.of("adult", "成人", "成人视频", "情色", "伦理", "伦理片", "情色片", "写真", "女优",
                            "有码", "无码", "中文字幕", "制服诱惑", "国产传媒", "国产主播", "网曝黑料",
                            "网红头条", "抖阴", "av", "vr视角", "强奸乱伦", "sm调教", "明星换脸"),
                    Set.of()),
            rule(OTHER, "其他", 10,
                    Set.of("other", "others", "其他", "其它", "未分类", "综合", "综合片源", "片源", "未知"),
                    Set.of()));

    private StandardContentCategoryClassifier() {
    }

    public static boolean isAllowedCode(String code) {
        return ALLOWED_CODES.contains(normalizeCode(code));
    }

    public static List<Match> stableCategories() {
        return STABLE_CATEGORIES;
    }

    public static List<String> stableCategoryCodes() {
        return STABLE_CATEGORIES.stream().map(Match::code).toList();
    }

    public static String canonicalName(String code) {
        return CANONICAL_NAMES.getOrDefault(normalizeCode(code), code);
    }

    public static Optional<Match> classify(String sourceCode, String sourceName) {
        Optional<Match> byCode = classifySingle(sourceCode);
        if (byCode.isPresent()) {
            return byCode;
        }
        return classifySingle(sourceName);
    }

    public static Optional<Match> classifySingle(String value) {
        String normalized = normalizeForMatch(value);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        String code = normalizeCode(value);
        if (ALLOWED_CODES.contains(code)) {
            return Optional.of(matchForCode(code));
        }
        if ("tv".equals(code) || "teleplay".equals(code)) {
            return Optional.of(matchForCode(SERIES));
        }
        if ("shortdrama".equals(normalized) || "shorts".equals(normalized)) {
            return Optional.of(matchForCode(SHORT_DRAMA));
        }

        Match best = null;
        int bestScore = 0;
        for (CategoryRule rule : RULES) {
            int score = rule.score(normalized);
            if (score > bestScore) {
                bestScore = score;
                best = new Match(rule.code(), rule.name());
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<String> inferCode(String sourceCode, String sourceName) {
        return classify(sourceCode, sourceName).map(Match::code);
    }

    public static Optional<String> inferName(String sourceName) {
        return classifySingle(sourceName).map(Match::name);
    }

    public static boolean isTopCategoryName(String value) {
        String normalized = normalizeForMatch(value);
        if (normalized.isBlank()) {
            return false;
        }
        return TOP_CATEGORY_NAMES.stream()
                .map(StandardContentCategoryClassifier::normalizeForMatch)
                .anyMatch(normalized::equals);
    }

    private static CategoryRule rule(String code, String name, int weight, Set<String> keywords, Set<String> negatives) {
        return new CategoryRule(code, name, weight, keywords, negatives);
    }

    private static Match matchForCode(String code) {
        return switch (code) {
            case MOVIE -> new Match(MOVIE, "电影");
            case SERIES -> new Match(SERIES, "剧集");
            case SHORT_DRAMA -> new Match(SHORT_DRAMA, "短剧");
            case ANIME -> new Match(ANIME, "动漫");
            case VARIETY -> new Match(VARIETY, "综艺");
            case DOCUMENTARY -> new Match(DOCUMENTARY, "纪录片");
            case SPORTS -> new Match(SPORTS, "体育赛事");
            case NEWS -> new Match(NEWS, "新闻资讯");
            case EDUCATION -> new Match(EDUCATION, "教育知识");
            case MUSIC -> new Match(MUSIC, "音乐演出");
            case ADULT -> new Match(ADULT, "成人");
            case OTHER -> new Match(OTHER, "其他");
            default -> throw new IllegalArgumentException("Unsupported content category code: " + code);
        };
    }

    private static String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private static String normalizeForMatch(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-:/|，,。·.]+", "");
    }

    public record Match(String code, String name) {
    }

    private record CategoryRule(String code, String name, int weight, Set<String> keywords, Set<String> negatives) {

        private int score(String normalizedInput) {
            for (String negative : negatives) {
                if (normalizedInput.contains(normalizeForMatch(negative))) {
                    return -1;
                }
            }
            int best = 0;
            for (String keyword : keywords) {
                String normalizedKeyword = normalizeForMatch(keyword);
                if (normalizedInput.equals(normalizedKeyword)) {
                    best = Math.max(best, weight + 50);
                } else if (normalizedInput.contains(normalizedKeyword)) {
                    best = Math.max(best, weight);
                }
            }
            return best;
        }
    }
}
