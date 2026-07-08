package com.prodigalgal.ircs.storage.image;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ImageSecurityValidator {

    private final Tika tika = new Tika();

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Pattern FILENAME_BLACKLIST = Pattern.compile("[\\u0000-\\u001f\"*<>?|:]|\\.\\.|^\\.|^$");
    private static final Map<String, String> MIME_TO_EXT = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/gif", ".gif",
            "image/avif", ".avif",
            "image/bmp", ".bmp",
            "image/x-icon", ".ico");

    public String validateAndGetMimeType(byte[] data, String declaredMimeType) {
        if (data == null || data.length == 0) {
            throw new SecurityException("File is empty");
        }
        if (data.length > MAX_FILE_SIZE) {
            throw new SecurityException("File exceeds maximum allowed size of 10MB");
        }
        String detected = detect(data).orElseThrow(() -> new SecurityException("Unsupported or unsafe file type"));
        if (!MIME_TO_EXT.containsKey(detected)) {
            throw new SecurityException("Unsupported or unsafe file type: " + detected);
        }
        return detected;
    }

    public void validateFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return;
        }
        if (FILENAME_BLACKLIST.matcher(filename).find()) {
            throw new SecurityException("Filename contains illegal characters or path traversal sequences");
        }
    }

    public String getExtension(String mimeType) {
        return MIME_TO_EXT.getOrDefault(mimeType, ".jpg");
    }

    public Optional<String> getMimeTypeFromExtension(String extension) {
        if (!StringUtils.hasText(extension)) {
            return Optional.empty();
        }
        String dotExt = extension.startsWith(".") ? extension.toLowerCase() : "." + extension.toLowerCase();
        return MIME_TO_EXT.entrySet().stream()
                .filter(entry -> entry.getValue().equals(dotExt))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    Optional<String> detect(byte[] data) {
        try {
            return Optional.ofNullable(normalizeDetectedMimeType(tika.detect(data)));
        } catch (Exception e) {
            throw new SecurityException("Failed to detect file type", e);
        }
    }

    private String normalizeDetectedMimeType(String mimeType) {
        if ("image/vnd.microsoft.icon".equals(mimeType)) {
            return "image/x-icon";
        }
        return mimeType;
    }
}
