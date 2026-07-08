package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ImageSecurityValidatorTest {

    private final ImageSecurityValidator validator = new ImageSecurityValidator();

    @ParameterizedTest
    @MethodSource("validImages")
    void acceptsV1ImageMimeAllowlist(String expectedMime, String expectedExtension, byte[] data) {
        assertEquals(expectedMime, validator.validateAndGetMimeType(data, "application/octet-stream"));
        assertEquals(expectedExtension, validator.getExtension(expectedMime));
    }

    @ParameterizedTest
    @MethodSource("unsafePayloads")
    void rejectsSpoofedAndNonImageBytes(String declaredMimeType, byte[] data) {
        assertThrows(SecurityException.class, () -> validator.validateAndGetMimeType(data, declaredMimeType));
    }

    @Test
    void ignoresDeclaredMimeWhenDetectedContentIsSafe() {
        assertEquals("image/png", validator.validateAndGetMimeType(png(), "text/html"));
    }

    @Test
    void rejectsEmptyOversizeAndUnsafeFilename() {
        assertThrows(SecurityException.class, () -> validator.validateAndGetMimeType(new byte[0], "image/png"));
        assertThrows(SecurityException.class, () -> validator.validateAndGetMimeType(new byte[(10 * 1024 * 1024) + 1], "image/png"));
        assertThrows(SecurityException.class, () -> validator.validateFilename("../x.png"));
        assertThrows(SecurityException.class, () -> validator.validateFilename(".hidden"));
        assertThrows(SecurityException.class, () -> validator.validateFilename("cover?.png"));
        assertDoesNotThrow(() -> validator.validateFilename("cover.png"));
    }

    private static Stream<Arguments> validImages() {
        return Stream.of(
                Arguments.of("image/png", ".png", png()),
                Arguments.of("image/jpeg", ".jpg", jpeg()),
                Arguments.of("image/webp", ".webp", webp()),
                Arguments.of("image/gif", ".gif", "GIF89a".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("image/avif", ".avif", avif()),
                Arguments.of("image/bmp", ".bmp", bmp()),
                Arguments.of("image/x-icon", ".ico", ico()));
    }

    private static Stream<Arguments> unsafePayloads() {
        return Stream.of(
                Arguments.of("image/png", "<html><body>not an image</body></html>".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("image/jpeg", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("image/png", hex("504B0304140000000000")),
                Arguments.of("image/png", "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("image/png", new byte[] {1, 2, 3, 4, 5, 6}));
    }

    private static byte[] png() {
        return hex("89504E470D0A1A0A0000000D4948445200000001000000010802000000907753DE0000000049454E44AE426082");
    }

    private static byte[] jpeg() {
        return hex("FFD8FFE000104A46494600010101006000600000FFDB004300FFD9");
    }

    private static byte[] webp() {
        return "RIFF\u001A\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] avif() {
        return hex("00000018667479706176696600000000617669666D696631");
    }

    private static byte[] bmp() {
        return hex("424D4600000000000000360000002800000001000000010000000100180000000000100000000000000000000000000000000000000000000000");
    }

    private static byte[] ico() {
        return hex("00000100010010100000010020006804000016000000");
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
