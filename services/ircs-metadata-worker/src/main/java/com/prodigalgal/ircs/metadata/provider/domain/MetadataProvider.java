package com.prodigalgal.ircs.metadata.provider.domain;

import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import java.util.Optional;

public interface MetadataProvider {

    ProviderType getType();

    boolean supports(MetadataSearchContext context);

    Optional<EnrichedMetadataDTO> enrich(MetadataSearchContext context);
}
