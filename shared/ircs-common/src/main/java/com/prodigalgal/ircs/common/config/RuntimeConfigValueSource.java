package com.prodigalgal.ircs.common.config;

import java.util.Optional;

public interface RuntimeConfigValueSource {

    Optional<String> findValue(String key);

    void evict(String key);
}
