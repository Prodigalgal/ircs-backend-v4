package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StorageMediaWebConfigTest {

    @Test
    void defaultsBlankPublicPathToMediaPattern() {
        assertEquals("/media/**", StorageMediaWebConfig.mediaPattern(""));
        assertEquals("/media/**", StorageMediaWebConfig.mediaPattern("/media"));
        assertEquals("/assets/**", StorageMediaWebConfig.mediaPattern("assets"));
    }

    @Test
    void resolvesStorageBasePathAsFileLocation() {
        assertTrue(StorageMediaWebConfig.fileLocation("./storage").startsWith("file:///"));
    }
}
