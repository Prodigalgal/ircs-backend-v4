package com.prodigalgal.ircs.metadata.provider.tmdb;

import java.net.URI;
import java.util.Optional;

public interface TmdbHttpClient {

    Optional<String> getJson(URI uri);
}
