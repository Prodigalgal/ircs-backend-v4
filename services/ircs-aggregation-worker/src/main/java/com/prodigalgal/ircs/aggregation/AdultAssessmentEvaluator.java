package com.prodigalgal.ircs.aggregation;

import com.prodigalgal.ircs.common.adult.AdultAssessment;
import com.prodigalgal.ircs.common.adult.AdultAssessmentInput;
import com.prodigalgal.ircs.common.adult.AdultContentAssessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@FunctionalInterface
interface AdultAssessmentEvaluator {

    Map<UUID, AdultAssessment> assessAll(Map<UUID, AdultAssessmentInput> inputs);

    default AdultAssessment assess(UUID id, AdultAssessmentInput input) {
        AdultAssessment fallback = AdultContentAssessor.assess(input);
        if (id == null) {
            return fallback;
        }
        return assessAll(Map.of(id, input)).getOrDefault(id, fallback);
    }

    static AdultAssessmentEvaluator local() {
        return inputs -> {
            Map<UUID, AdultAssessment> results = new LinkedHashMap<>();
            if (inputs == null) {
                return results;
            }
            inputs.forEach((id, input) -> results.put(id, AdultContentAssessor.assess(input)));
            return results;
        };
    }
}
