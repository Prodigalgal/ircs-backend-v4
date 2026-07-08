package com.prodigalgal.ircs.contentsafety;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentBatchRequest;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentItem;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdultAssessmentControllerPathTest {

    @Test
    void registersColonBatchPathWithoutExtraSlash() throws Exception {
        ContentSafetyProperties properties = new ContentSafetyProperties();
        JsonMapper objectMapper = JsonMapper.builder().build();
        AdultAssessmentService service = new AdultAssessmentService(
                properties,
                new SensitiveWordEvidenceService(),
                new AdultModelClassifierClient(objectMapper, properties));
        AdultAssessmentController controller =
                new AdultAssessmentController(new ContentSafetyInternalAccessPolicy(properties), service);

        var request = new AdultAssessmentBatchRequest(List.of(new AdultAssessmentItem(
                UUID.randomUUID(),
                "地球自然纪录片",
                null,
                null,
                null,
                "海洋与森林生态",
                "documentary",
                "纪录片",
                List.of(),
                List.of(),
                List.of("nature"),
                List.of())));

        MockMvcBuilders.standaloneSetup(controller)
                .build()
                .perform(post("/internal/v1/content-safety/adult-assessments:batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].level").value("SAFE"));
    }
}
