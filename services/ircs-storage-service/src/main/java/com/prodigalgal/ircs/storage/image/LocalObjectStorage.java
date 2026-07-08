package com.prodigalgal.ircs.storage.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class LocalObjectStorage {

    private final Path root;

    public LocalObjectStorage(@Value("${app.storage.base-path:./storage}") String basePath) {
        this.root = Path.of(basePath).normalize().toAbsolutePath();
    }

    public boolean exists(String storagePath) {
        Path target = resolve(storagePath);
        return target != null && Files.exists(target);
    }

    public void store(byte[] data, String storagePath, String contentType) {
        Path target = resolve(storagePath);
        if (target == null) {
            throw new IllegalArgumentException("Invalid local storage path: " + storagePath);
        }
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.getParent().resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.write(temporary, data);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            log.info("Stored local storage object: {} ({} bytes, {})", storagePath, data.length, contentType);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to store local storage object: " + storagePath, ex);
        }
    }

    public Optional<byte[]> retrieve(String storagePath) {
        Path target = resolve(storagePath);
        if (target == null || !Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read local storage object: " + storagePath, ex);
        }
    }

    public void deleteIfExists(String storagePath) {
        Path target = resolve(storagePath);
        if (target == null) {
            log.warn("Skipping local delete for path outside storage root: {}", storagePath);
            return;
        }
        try {
            Files.deleteIfExists(target);
            log.info("Deleted local storage object: {}", storagePath);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete local storage object: " + storagePath, ex);
        }
    }

    private Path resolve(String storagePath) {
        if (!StringUtils.hasText(storagePath) || storagePath.contains("..")) {
            return null;
        }
        Path resolved = root.resolve(storagePath).normalize().toAbsolutePath();
        return resolved.startsWith(root) ? resolved : null;
    }
}
