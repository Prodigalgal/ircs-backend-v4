package com.prodigalgal.ircs.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentItem;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentSourceEvidence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SensitiveWordEvidenceServiceTest {

    private final SensitiveWordEvidenceService service = new SensitiveWordEvidenceService();

    @Test
    void scansOnlyTitleSubtitleAndAliasTitle() {
        AdultAssessmentItem item = new AdultAssessmentItem(
                UUID.randomUUID(),
                "无码普通标题",
                "普通别名",
                "无码人妻偷拍视频合集",
                "深喉口交",
                "普通子标题",
                "other",
                "其他",
                List.of("无码演员"),
                List.of("无码导演"),
                List.of("无码题材"),
                List.of(new AdultAssessmentSourceEvidence(
                        "无码资源站",
                        false,
                        "adult",
                        "无码分类",
                        "jav.example",
                        "无码人妻偷拍视频合集")));

        assertThat(service.scan(item, 512))
                .isNotEmpty()
                .allSatisfy(signal -> assertThat(List.of("title", "subtitle", "aliasTitle"))
                        .contains(signal.field()));
    }
}
