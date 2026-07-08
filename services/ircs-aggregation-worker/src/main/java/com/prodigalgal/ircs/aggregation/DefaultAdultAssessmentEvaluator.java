package com.prodigalgal.ircs.aggregation;

import com.prodigalgal.ircs.common.adult.AdultAssessment;
import com.prodigalgal.ircs.common.adult.AdultAssessmentInput;
import com.prodigalgal.ircs.common.adult.AdultContentAssessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class DefaultAdultAssessmentEvaluator implements AdultAssessmentEvaluator {

    private final ContentSafetyAdultAssessmentClient contentSafetyClient;

    @Override
    public Map<UUID, AdultAssessment> assessAll(Map<UUID, AdultAssessmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AdultAssessment> local = new LinkedHashMap<>();
        inputs.forEach((id, input) -> local.put(id, AdultContentAssessor.assess(input)));
        Map<UUID, AdultAssessment> remote = contentSafetyClient.assess(inputs);
        if (remote.isEmpty()) {
            return local;
        }
        Map<UUID, AdultAssessment> merged = new LinkedHashMap<>(local);
        remote.forEach((id, assessment) -> {
            if (assessment != null) {
                merged.put(id, assessment);
            }
        });
        return merged;
    }
}
