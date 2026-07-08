package com.prodigalgal.ircs.catalog;

import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class CatalogCategoryMappingPolicy {

    String inferCategoryName(String sourceName) {
        if (!StringUtils.hasText(sourceName)) {
            return null;
        }
        return StandardContentCategoryClassifier.inferName(sourceName).orElse(null);
    }
}
