package com.prodigalgal.ircs.portal;

import java.util.List;

public record PortalMetadataResponse(
        List<CategoryItem> categories,
        List<String> genres,
        List<String> areas,
        List<String> languages,
        List<String> years) {
}
