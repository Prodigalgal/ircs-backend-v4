package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageTest {

    @TempDir
    private Path tempDir;

    @Test
    void deletesOnlyInsideStorageRoot() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir.toString());
        Path image = tempDir.resolve("covers/a.webp");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");

        assertTrue(storage.exists("covers/a.webp"));
        storage.deleteIfExists("covers/a.webp");

        assertFalse(Files.exists(image));
    }

    @Test
    void ignoresTraversalPath() {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir.toString());

        storage.deleteIfExists("../outside.webp");

        assertFalse(storage.exists("../outside.webp"));
    }

    @Test
    void storesAndRetrievesInsideStorageRoot() {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir.toString());
        byte[] data = new byte[] {1, 2, 3};

        storage.store(data, "covers/a.bin", "application/octet-stream");

        assertTrue(storage.exists("covers/a.bin"));
        assertArrayEquals(data, storage.retrieve("covers/a.bin").orElseThrow());
    }
}
