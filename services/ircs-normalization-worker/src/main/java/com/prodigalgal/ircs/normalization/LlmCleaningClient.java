package com.prodigalgal.ircs.normalization;

import java.util.List;
import java.util.Set;

interface LlmCleaningClient {

    List<LlmCleaningDecision> analyzeAndMap(
            List<String> rawItems,
            Set<String> validStandardItems,
            LlmCleaningKind kind,
            LlmCredential credential);
}
