package com.prodigalgal.ircs.credential;

import java.util.List;

public record CredentialTemplateField(
        String key,
        String label,
        String type,
        boolean required,
        String placeholder,
        Object defaultValue,
        String helpText,
        List<Option> options) {

    public record Option(String label, String value) {
    }
}
