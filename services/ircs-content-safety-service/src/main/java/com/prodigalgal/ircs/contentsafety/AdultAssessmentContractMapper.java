package com.prodigalgal.ircs.contentsafety;

import com.prodigalgal.ircs.common.adult.AdultAssessment;
import com.prodigalgal.ircs.common.adult.AdultAssessmentInput;
import com.prodigalgal.ircs.common.adult.AdultAssessmentSignal;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentItem;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentResult;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentSignalDto;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentSourceEvidence;
import java.util.List;

final class AdultAssessmentContractMapper {

    private AdultAssessmentContractMapper() {
    }

    static AdultAssessmentInput toInput(AdultAssessmentItem item) {
        if (item == null) {
            return null;
        }
        return new AdultAssessmentInput(
                item.title(),
                item.aliasTitle(),
                item.description(),
                item.remarks(),
                item.subtitle(),
                item.categoryCode(),
                item.categoryName(),
                item.actorNames(),
                item.directorNames(),
                item.genreCodes(),
                item.sources().stream()
                        .map(AdultAssessmentContractMapper::toSource)
                        .toList());
    }

    static AdultAssessmentResult toResult(
            AdultAssessmentItem item,
            AdultAssessment assessment,
            com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentModelResult model) {
        return new AdultAssessmentResult(
                item.id(),
                assessment.ruleVersion(),
                assessment.level().name(),
                assessment.adultRestricted(),
                assessment.confidence(),
                assessment.signals().stream()
                        .map(AdultAssessmentContractMapper::toSignal)
                        .toList(),
                model);
    }

    static AdultAssessmentSignalDto toSignal(AdultAssessmentSignal signal) {
        return new AdultAssessmentSignalDto(
                signal.source(),
                signal.field(),
                signal.matchedValue(),
                signal.score(),
                signal.reason());
    }

    static AdultAssessmentSignal toSignal(AdultAssessmentSignalDto signal) {
        return new AdultAssessmentSignal(
                signal.source(),
                signal.field(),
                signal.matchedValue(),
                signal.score(),
                signal.reason());
    }

    static List<AdultAssessmentSignal> toSignals(List<AdultAssessmentSignalDto> signals) {
        return signals == null ? List.of() : signals.stream()
                .map(AdultAssessmentContractMapper::toSignal)
                .toList();
    }

    private static AdultAssessmentInput.SourceEvidence toSource(AdultAssessmentSourceEvidence source) {
        return new AdultAssessmentInput.SourceEvidence(
                source.dataSourceName(),
                source.dataSourceAdultRestricted(),
                source.sourceCategoryCode(),
                source.sourceCategoryName(),
                source.sourceDomain(),
                source.rawMetadata());
    }
}
