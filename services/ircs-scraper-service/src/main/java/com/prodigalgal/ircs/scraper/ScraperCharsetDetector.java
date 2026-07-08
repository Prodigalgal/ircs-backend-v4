package com.prodigalgal.ircs.scraper;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.txt.UniversalEncodingDetector;
import org.springframework.stereotype.Component;

@Component
class ScraperCharsetDetector {

    private static final int MARK_LIMIT = 8192;
    private static final Charset GB18030 = Charset.forName("GB18030");

    private final UniversalEncodingDetector detector = new UniversalEncodingDetector();

    Charset detect(byte[] data) {
        return detect(data, null);
    }

    Charset detect(byte[] data, String contentType) {
        if (data == null || data.length == 0) {
            return StandardCharsets.UTF_8;
        }
        Optional<Charset> declared = declaredCharset(contentType);
        if (declared.isPresent()) {
            return declared.get();
        }
        if (decodeStrict(data, StandardCharsets.UTF_8).isPresent()) {
            return StandardCharsets.UTF_8;
        }
        Charset tikaDetected = detectWithTika(data);
        if (isEastAsianCharset(tikaDetected)) {
            return tikaDetected;
        }
        Optional<String> gb18030 = decodeStrict(data, GB18030);
        if (gb18030.filter(this::containsCjkText).isPresent()) {
            return GB18030;
        }
        return tikaDetected == null ? StandardCharsets.UTF_8 : tikaDetected;
    }

    private Optional<Charset> declaredCharset(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(contentType.split(";"))
                .map(String::trim)
                .filter(part -> part.toLowerCase(Locale.ROOT).startsWith("charset="))
                .map(part -> part.substring("charset=".length()).trim())
                .map(value -> value.replace("\"", "").replace("'", ""))
                .filter(Charset::isSupported)
                .map(Charset::forName)
                .findFirst();
    }

    private Charset detectWithTika(byte[] data) {
        try (BufferedInputStream input = new BufferedInputStream(new ByteArrayInputStream(data))) {
            input.mark(MARK_LIMIT);
            Charset charset = detector.detect(input, new Metadata());
            input.reset();
            return charset;
        } catch (IOException ex) {
            return null;
        }
    }

    private Optional<String> decodeStrict(byte[] data, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return Optional.of(decoder.decode(ByteBuffer.wrap(data)).toString());
        } catch (CharacterCodingException ex) {
            return Optional.empty();
        }
    }

    private boolean isEastAsianCharset(Charset charset) {
        if (charset == null) {
            return false;
        }
        String name = charset.name().toLowerCase(Locale.ROOT);
        return name.contains("gb")
                || name.contains("big5")
                || name.contains("shift_jis")
                || name.contains("euc-jp")
                || name.contains("euc-kr")
                || name.contains("ksc");
    }

    private boolean containsCjkText(String text) {
        return text.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
