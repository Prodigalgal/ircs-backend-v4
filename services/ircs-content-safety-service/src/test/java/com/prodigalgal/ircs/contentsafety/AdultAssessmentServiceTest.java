package com.prodigalgal.ircs.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentBatchRequest;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentItem;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdultAssessmentServiceTest {

    private final ContentSafetyProperties properties = new ContentSafetyProperties();
    private final AdultAssessmentService service = new AdultAssessmentService(
            properties,
            new SensitiveWordEvidenceService(),
            new AdultModelClassifierClient(JsonMapper.builder().build(), properties));

    @Test
    void marksExplicitChineseAdultTitleWithoutCatalogCode() {
        UUID id = UUID.randomUUID();

        var response = service.assess(new AdultAssessmentBatchRequest(List.of(new AdultAssessmentItem(
                id,
                "吊钟奶兔女郎观音坐莲巨乳眼镜妹情趣制服诱惑深喉口交女上位超诱惑",
                null,
                "36D吊钟奶兔女郎观音坐莲巨乳眼镜妹情趣制服诱惑深喉口交女上位超诱惑",
                null,
                null,
                "other",
                "其他",
                List.of(),
                List.of(),
                List.of(),
                List.of()))));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().id()).isEqualTo(id);
        assertThat(response.items().getFirst().level()).isEqualTo("ADULT");
        assertThat(response.items().getFirst().adultRestricted()).isTrue();
        assertThat(response.items().getFirst().signals()).isNotEmpty();
    }

    @Test
    void keepsOrdinaryOtherTitleSafeWhenModelIsDisabled() {
        UUID id = UUID.randomUUID();

        var response = service.assess(new AdultAssessmentBatchRequest(List.of(new AdultAssessmentItem(
                id,
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
                List.of()))));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().level()).isEqualTo("SAFE");
        assertThat(response.items().getFirst().adultRestricted()).isFalse();
        assertThat(response.items().getFirst().model().available()).isFalse();
    }
}
