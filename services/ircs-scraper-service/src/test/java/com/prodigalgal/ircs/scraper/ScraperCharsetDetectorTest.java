package com.prodigalgal.ircs.scraper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScraperCharsetDetectorTest {

    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Charset BIG5 = Charset.forName("Big5");
    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");

    private final ScraperCharsetDetector detector = new ScraperCharsetDetector();

    @Test
    void detectsUtf8WithoutFallingThroughToLegacyDetector() {
        byte[] data = "{\"title\":\"中文 UTF-8\"}".getBytes(StandardCharsets.UTF_8);

        assertEquals(StandardCharsets.UTF_8, detector.detect(data));
    }

    @Test
    void detectsGb18030HtmlFixtureWhenTikaReturnsSingleByteEncoding() {
        byte[] data = """
                <html><head><title>中文标题</title></head><body>香港电影</body></html>
                """.getBytes(GB18030);

        assertEquals(GB18030, detector.detect(data));
    }

    @Test
    void detectsBig5HtmlFixture() {
        byte[] data = """
                <html><head><title>繁體標題</title></head><body>香港電影</body></html>
                """.getBytes(BIG5);

        assertEquals(BIG5, detector.detect(data));
    }

    @Test
    void detectsShiftJisHtmlFixture() {
        byte[] data = """
                <html><head><title>日本映画</title></head><body>東京公開</body></html>
                """.getBytes(SHIFT_JIS);

        assertEquals(SHIFT_JIS, detector.detect(data));
    }

    @Test
    void declaredContentTypeCharsetWinsForShortJsonPayloads() {
        byte[] data = "{\"title\":\"繁體標題\"}".getBytes(BIG5);

        assertEquals(BIG5, detector.detect(data, "application/json; charset=\"Big5\""));
    }

    @Test
    void utf8BomPayloadStaysUtf8() {
        byte[] body = "{\"title\":\"BOM 中文\"}".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[body.length + 3];
        data[0] = (byte) 0xEF;
        data[1] = (byte) 0xBB;
        data[2] = (byte) 0xBF;
        System.arraycopy(body, 0, data, 3, body.length);

        assertEquals(StandardCharsets.UTF_8, detector.detect(data));
    }

    @Test
    void invalidBytesStillReturnSafeFallbackCharset() {
        assertNotNull(detector.detect(new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x02}));
    }

    @Test
    void emptyPayloadFallsBackToUtf8LikeV1NullDetectionFallback() {
        assertEquals(StandardCharsets.UTF_8, detector.detect(new byte[0]));
    }
}
