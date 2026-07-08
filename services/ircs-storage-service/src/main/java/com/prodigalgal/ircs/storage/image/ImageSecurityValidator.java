package com.prodigalgal.ircs.storage.image;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ImageSecurityValidator {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
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
        if (startsWith(data, PNG_SIGNATURE)) {
            return Optional.of("image/png");
        }
        if (data.length >= 3
                && unsigned(data[0]) == 0xFF
                && unsigned(data[1]) == 0xD8
                && unsigned(data[2]) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (asciiAt(data, 0, "RIFF") && asciiAt(data, 8, "WEBP")) {
            return Optional.of("image/webp");
        }
        if (asciiAt(data, 0, "GIF87a") || asciiAt(data, 0, "GIF89a")) {
            return Optional.of("image/gif");
        }
        if (asciiAt(data, 4, "ftyp") && (asciiAt(data, 8, "avif") || asciiAt(data, 8, "avis"))) {
            return Optional.of("image/avif");
        }
        if (asciiAt(data, 0, "BM")) {
            return Optional.of("image/bmp");
        }
        if (data.length >= 4
                && data[0] == 0
                && data[1] == 0
                && data[2] == 1
                && data[3] == 0) {
            return Optional.of("image/x-icon");
        }
        return Optional.empty();
    }

    private boolean startsWith(byte[] data, byte[] signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (data[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean asciiAt(byte[] data, int offset, String value) {
        if (data.length < offset + value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (data[offset + index] != (byte) value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }
}
