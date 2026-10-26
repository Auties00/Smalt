package com.github.auties00.smalt.internal.subtitle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the decoding of subtitle files: byte order marks, the encoding detection of legacy files and the
 * explicit encoding that overrides it.
 */
@DisplayName("Subtitle text decoding")
class TextFilesTest {
    /** Subtitle lines in several scripts, long enough to be recognized but as short as real cues. */
    private static final String RUSSIAN = "1\n00:00:01,000 --> 00:00:02,000\n\u041F\u0440\u0438\u0432\u0435\u0442, "
            + "\u043A\u0430\u043A \u0434\u0435\u043B\u0430? \u042F \u0434\u043E\u043C\u0430.\n";
    private static final String JAPANESE = "1\n00:00:01,000 --> 00:00:02,000\n\u3053\u3093\u306B\u3061\u306F\u3001"
            + "\u5143\u6C17\u3067\u3059\u304B\uFF1F\n";
    private static final String KOREAN = "1\n00:00:01,000 --> 00:00:02,000\n\uC548\uB155\uD558\uC138\uC694, "
            + "\uC798 \uC9C0\uB0B4\uC694?\n";
    private static final String SIMPLIFIED = "1\n00:00:01,000 --> 00:00:02,000\n\u6211\u4EEC\u5728\u8FD9\u91CC"
            + "\u662F\u4E0D\u662F\u6709\u4EBA\uFF1F\u4ED6\u8BF4\u4E86\u3002\n";
    private static final String TRADITIONAL = "1\n00:00:01,000 --> 00:00:02,000\n\u6211\u5011\u5728\u9019\u88E1"
            + "\u662F\u4E0D\u662F\u6709\u4EBA\uFF1F\u4ED6\u8AAA\u4E86\u3002\n";
    private static final String WESTERN = "1\n00:00:01,000 --> 00:00:02,000\nC'est l'\u00E9t\u00E9, \u00FCber "
            + "na\u00EFve fa\u00E7ade.\n";

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "windows-1251, RUSSIAN",
            "KOI8-R, RUSSIAN",
            "Shift_JIS, JAPANESE",
            "EUC-JP, JAPANESE",
            "x-windows-949, KOREAN",
            "GBK, SIMPLIFIED",
            "Big5, TRADITIONAL",
            "windows-1252, WESTERN",
            "UTF-8, RUSSIAN",
            "UTF-16LE, JAPANESE",
            "UTF-16BE, KOREAN"
    })
    @DisplayName("detects the encoding of a legacy file and decodes it exactly")
    void detect(String charsetName, String text) {
        var charset = Charset.forName(charsetName);
        var expected = text(text);
        var data = expected.getBytes(charset);
        var detected = TextFiles.detect(data);
        assertEquals(expected, new String(data, detected), charsetName + " detected as " + detected);
        assertEquals(expected, TextFiles.decode(data));
    }

    @Test
    @DisplayName("uses the given encoding instead of detecting one, and a byte order mark over both")
    void explicit() {
        var greek = "1\n00:00:01,000 --> 00:00:02,000\n\u039A\u03B1\u03BB\u03B7\u03BC\u03AD\u03C1\u03B1\n";
        var data = greek.getBytes(Charset.forName("windows-1253"));
        assertEquals(greek, TextFiles.decode(data, Charset.forName("windows-1253")));
        var cyrillic = RUSSIAN.getBytes(Charset.forName("windows-1251"));
        assertEquals(new String(cyrillic, Charset.forName("windows-1252")),
                TextFiles.decode(cyrillic, Charset.forName("windows-1252")));
        var bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertEquals("hi", TextFiles.decode(bom, Charset.forName("windows-1251")));
        var utf32 = concat(new byte[]{0, 0, (byte) 0xFE, (byte) 0xFF}, "ok".getBytes(Charset.forName("UTF-32BE")));
        assertEquals("ok", TextFiles.decode(utf32));
        assertEquals("a\nb\nc", TextFiles.decode("a\r\nb\rc\0".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("reads a Windows-1252 file whose accents stand alone, and plain ASCII as UTF-8")
    void fallbacks() {
        assertEquals(Charset.forName("windows-1252"), TextFiles.detect(WESTERN.getBytes(Charset.forName("windows-1252"))));
        assertEquals(StandardCharsets.UTF_8, TextFiles.detect("plain".getBytes(StandardCharsets.US_ASCII)));
        // the inspected prefix ends inside a two-byte character, which must not make the text invalid UTF-8
        var accents = ("a" + "\u00e9".repeat(40_000)).getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, TextFiles.detect(accents));
        var ascii16 = "1\n00:00:01,000 --> 00:00:02,000\nplain\n".getBytes(StandardCharsets.UTF_16LE);
        assertEquals(StandardCharsets.UTF_16LE, TextFiles.detect(ascii16));
    }

    private static String text(String name) {
        return switch (name) {
            case "RUSSIAN" -> RUSSIAN;
            case "JAPANESE" -> JAPANESE;
            case "KOREAN" -> KOREAN;
            case "SIMPLIFIED" -> SIMPLIFIED;
            case "TRADITIONAL" -> TRADITIONAL;
            default -> WESTERN;
        };
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var result = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
