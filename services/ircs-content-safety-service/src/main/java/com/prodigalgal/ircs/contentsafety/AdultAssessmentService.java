package com.prodigalgal.ircs.contentsafety;

import com.prodigalgal.ircs.common.adult.AdultAssessment;
import com.prodigalgal.ircs.common.adult.AdultAssessmentSignal;
import com.prodigalgal.ircs.common.adult.AdultContentAssessor;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentBatchRequest;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentBatchResponse;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentItem;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentModelResult;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class AdultAssessmentService {

    private static final String ENGINE_VERSION = AdultContentAssessor.RULE_VERSION + "+content-safety-v1";

    private final ContentSafetyProperties properties;
    private final SensitiveWordEvidenceService sensitiveWordEvidenceService;
    private final AdultModelClassifierClient modelClassifierClient;

    AdultAssessmentBatchResponse assess(AdultAssessmentBatchRequest request) {
        List<AdultAssessmentItem> items = request == null ? List.of() : request.items();
        Map<UUID, AdultAssessmentModelResult> modelResults = modelClassifierClient.classify(items);
        List<AdultAssessmentResult> results = items.stream()
                .map(item -> assess(item, modelResults.get(item.id())))
                .toList();
        return new AdultAssessmentBatchResponse(ENGINE_VERSION, results);
    }

    private AdultAssessmentResult assess(AdultAssessmentItem item, AdultAssessmentModelResult modelResult) {
        AdultAssessment ruleAssessment =
                AdultContentAssessor.assess(AdultAssessmentContractMapper.toInput(item));
        List<AdultAssessmentSignal> signals = new ArrayList<>(ruleAssessment.signals());
        signals.addAll(sensitiveWordEvidenceService.scan(item, properties.adult().maxTextLength()));
        AdultAssessmentModelResult effectiveModel = modelResult == null
                ? AdultAssessmentModelResult.unavailable(
                        properties.adult().model().name(),
                        properties.adult().model().version())
                : modelResult;
        modelSignal(effectiveModel).forEach(signals::add);
        AdultAssessment merged = AdultContentAssessor.summarizeSignals(signals);
        return AdultAssessmentContractMapper.toResult(item, merged, effectiveModel);
    }

    private List<AdultAssessmentSignal> modelSignal(AdultAssessmentModelResult modelResult) {
        if (modelResult == null || !modelResult.available()) {
            return List.of();
        }
        int score = (int) Math.round(modelResult.adultScore() * 100.0d);
        if (score < Math.round(properties.adult().model().suspectThreshold() * 100.0d)) {
            return List.of();
        }
        String reason = score >= Math.round(properties.adult().model().adultThreshold() * 100.0d)
                ? "模型判定成人内容"
                : "模型判定疑似成人内容";
        return List.of(new AdultAssessmentSignal(
                "model",
                "combinedText",
                modelResult.label(),
                score,
                reason));
    }
}
