package com.prodigalgal.ircs.common.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StandardContentCategoryClassifierTest {

    @Test
    void mapsCommonSourceCategoryNamesToTwelveStableCategoryCodes() {
        assertThat(StandardContentCategoryClassifier.inferCode("2", "国产剧")).contains("series");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "剧情片")).contains("movie");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "大陆综艺")).contains("variety");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "记录片")).contains("documentary");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "国产动漫")).contains("anime");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "现代都市")).contains("short-drama");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "足球")).contains("sports");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "新闻资讯")).contains("news");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "公开课")).contains("education");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "演唱会")).contains("music");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "日本有码")).contains("adult");
        assertThat(StandardContentCategoryClassifier.inferCode(null, "其他片源")).contains("other");
    }

    @Test
    void exposesTwelveStableTopCategoryCodes() {
        assertThat(StandardContentCategoryClassifier.stableCategoryCodes())
                .containsExactly(
                        "movie",
                        "series",
                        "short-drama",
                        "anime",
                        "variety",
                        "documentary",
                        "sports",
                        "news",
                        "education",
                        "music",
                        "adult",
                        "other");
        assertThat(StandardContentCategoryClassifier.isAllowedCode("sports")).isTrue();
        assertThat(StandardContentCategoryClassifier.isAllowedCode("adult")).isTrue();
        assertThat(StandardContentCategoryClassifier.isAllowedCode("other")).isTrue();
    }

    @Test
    void recognizesExplicitCategoryCodesAndAliases() {
        assertThat(StandardContentCategoryClassifier.inferCode("movie", null)).contains("movie");
        assertThat(StandardContentCategoryClassifier.inferCode("tv", null)).contains("series");
        assertThat(StandardContentCategoryClassifier.inferCode("short_drama", null)).contains("short-drama");
    }

    @Test
    void distinguishesTopCategoryNamesFromGenreLikeSourceCategories() {
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("电影")).isTrue();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("电视剧")).isTrue();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("剧集")).isTrue();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("动画")).isTrue();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("体育赛事")).isTrue();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("成人")).isTrue();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("其他")).isTrue();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("剧情片")).isFalse();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("国产剧")).isFalse();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("国产动漫")).isFalse();
        assertThat(StandardContentCategoryClassifier.isTopCategoryName("足球")).isFalse();
    }
}
