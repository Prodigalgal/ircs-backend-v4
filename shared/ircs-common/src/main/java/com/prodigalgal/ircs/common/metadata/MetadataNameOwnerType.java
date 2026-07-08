package com.prodigalgal.ircs.common.metadata;

public enum MetadataNameOwnerType {
    RAW("raw"),
    UNIFIED("unified");

    private final String key;

    MetadataNameOwnerType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
