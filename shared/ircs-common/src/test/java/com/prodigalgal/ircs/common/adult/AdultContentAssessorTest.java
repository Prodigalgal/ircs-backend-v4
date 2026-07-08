package com.prodigalgal.ircs.common.adult;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdultContentAssessorTest {

    @Test
    void marksAdultWhenDataSourceIsRestricted() {
        AdultAssessment assessment = AdultContentAssessor.assess(new AdultAssessmentInput(
                "普通标题",
                null,
                null,
                null,
                null,
                "movie",
                "电影",
                List.of(),
                List.of(),
                List.of(),
                List.of(new AdultAssessmentInput.SourceEvidence(
                        "限制资源站",
                        true,
                        null,
                        null,
                        "https://example.invalid",
                        null))));

        assertThat(assessment.adultRestricted()).isTrue();
        assertThat(assessment.level()).isEqualTo(AdultAssessmentLevel.ADULT);
        assertThat(assessment.signals()).anyMatch(signal -> "dataSourceAdultRestricted".equals(signal.field()));
    }

    @Test
    void treatsStandaloneAvTokenAsAdultButAvoidsCommonFalsePositives() {
        AdultAssessment adult = AdultContentAssessor.assess(new AdultAssessmentInput(
                "JAV sample",
                null,
                null,
                null,
                null,
                "movie",
                "电影",
                List.of(),
                List.of(),
                List.of(),
                List.of()));
        AdultAssessment avatar = AdultContentAssessor.assess(new AdultAssessmentInput(
                "Avatar",
                null,
                null,
                null,
                null,
                "movie",
                "电影",
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        assertThat(adult.adultRestricted()).isTrue();
        assertThat(avatar.adultRestricted()).isFalse();
        assertThat(avatar.signals()).isEmpty();
    }

    @Test
    void keepsAmbiguousEroticTermsAsSuspectEvidence() {
        AdultAssessment assessment = AdultContentAssessor.assess(new AdultAssessmentInput(
                "经典伦理片",
                null,
                null,
                null,
                null,
                "movie",
                "电影",
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        assertThat(assessment.adultRestricted()).isFalse();
        assertThat(assessment.level()).isEqualTo(AdultAssessmentLevel.SUSPECT);
        assertThat(assessment.signals()).isNotEmpty();
    }

    @Test
    void marksExplicitChineseAdultTitlesWithoutCatalogCode() {
        List<String> leakedTitles = List.of(
                "80多斤精瘦小姐姐黑框眼镜大长腿地板上假吊插穴",
                "吊钟奶兔女郎观音坐莲巨乳眼镜妹情趣制服诱惑深喉口交女上位超诱惑",
                "高清街拍偷拍JK学生妹精选合集");

        for (String title : leakedTitles) {
            AdultAssessment assessment = AdultContentAssessor.assess(new AdultAssessmentInput(
                    title,
                    null,
                    title,
                    null,
                    null,
                    "other",
                    "其他",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()));

            assertThat(assessment.adultRestricted())
                    .as(title)
                    .isTrue();
            assertThat(assessment.level())
                    .as(title)
                    .isEqualTo(AdultAssessmentLevel.ADULT);
        }
    }

    @Test
    void keepsOrdinaryOtherContentSafe() {
        AdultAssessment assessment = AdultContentAssessor.assess(new AdultAssessmentInput(
                "那年除夕",
                null,
                "那年除夕一碗肉",
                null,
                "我拆开了二十年的红包",
                "other",
                "其他",
                List.of(),
                List.of(),
                List.of("穿越"),
                List.of()));

        assertThat(assessment.adultRestricted()).isFalse();
        assertThat(assessment.level()).isEqualTo(AdultAssessmentLevel.SAFE);
    }
}
