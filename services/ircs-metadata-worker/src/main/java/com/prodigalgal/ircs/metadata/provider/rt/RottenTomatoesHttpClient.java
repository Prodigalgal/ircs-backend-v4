package com.prodigalgal.ircs.metadata.provider.rt;

import java.net.URI;
import java.util.Optional;

public interface RottenTomatoesHttpClient {

    Optional<String> getHtml(URI uri, Optional<RottenTomatoesCredential> credential);
}
