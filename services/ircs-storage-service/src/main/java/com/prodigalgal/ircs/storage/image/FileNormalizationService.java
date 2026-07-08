package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.storage.image.CoverImageDtos.NormalizedFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FileNormalizationService {

    private final ImageSecurityValidator securityValidator;

    public FileNormalizationService(ImageSecurityValidator securityValidator) {
        this.securityValidator = securityValidator;
    }

    public NormalizedFile normalize(byte[] data, String declaredMimeType, String pathPrefix) {
        String mimeType = securityValidator.validateAndGetMimeType(data, declaredMimeType);
        String hash = sha256(data);
        String extension = securityValidator.getExtension(mimeType);
        String safePrefix = StringUtils.hasText(pathPrefix) ? pathPrefix.trim() : "common";
        if (safePrefix.endsWith("/")) {
            safePrefix = safePrefix.substring(0, safePrefix.length() - 1);
        }
        if (safePrefix.startsWith("/")) {
            safePrefix = safePrefix.substring(1);
        }
        return new NormalizedFile(data, hash, mimeType, extension, data.length, safePrefix + "/" + hash + extension);
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}

