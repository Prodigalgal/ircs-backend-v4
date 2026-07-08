package com.prodigalgal.ircs.common.metadata;

public enum MetadataNameRelation {
    ACTORS("actors"),
    DIRECTORS("directors"),
    AREAS("areas");

    private final String key;

    MetadataNameRelation(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
