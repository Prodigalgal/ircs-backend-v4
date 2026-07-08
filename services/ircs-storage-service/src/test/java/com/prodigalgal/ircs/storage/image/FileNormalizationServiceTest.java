package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class FileNormalizationServiceTest {

    private final FileNormalizationService service = new FileNormalizationService(new ImageSecurityValidator());

    @Test
    void normalizesWithDetectedMimeInsteadOfDeclaredMime() {
        var normalized = service.normalize(png(), "text/html", "/covers/");

        assertEquals("image/png", normalized.mimeType());
        assertEquals(".png", normalized.extension());
        assertEquals(png().length, normalized.size());
        assertTrue(normalized.storageKey().startsWith("covers/"));
        assertTrue(normalized.storageKey().endsWith(".png"));
    }

    @Test
    void rejectsPdfHtmlAndOversizedPayloadsBeforeHashing() {
        assertThrows(SecurityException.class,
                () -> service.normalize("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), "image/png", "covers"));
        assertThrows(SecurityException.class,
                () -> service.normalize("<html>not image</html>".getBytes(StandardCharsets.UTF_8), "image/png", "covers"));
        assertThrows(SecurityException.class,
                () -> service.normalize(new byte[(10 * 1024 * 1024) + 1], "image/png", "covers"));
    }

    private static byte[] png() {
        return HexFormat.of().parseHex(
                "89504E470D0A1A0A0000000D4948445200000001000000010802000000907753DE0000000049454E44AE426082");
    }
}
