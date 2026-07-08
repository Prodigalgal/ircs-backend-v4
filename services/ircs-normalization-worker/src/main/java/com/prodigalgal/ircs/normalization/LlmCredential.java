package com.prodigalgal.ircs.normalization;

record LlmCredential(String apiKey, String baseUrl, String model, String source) {

    @Override
    public String toString() {
        return "LlmCredential[apiKey=[redacted], baseUrl=%s, model=%s, source=%s]"
                .formatted(baseUrl, model, source);
    }
}
