package com.github.auties00.smalt.internal.subtitle;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes the bytes of a text subtitle file and normalizes its line breaks.
 *
 * <p>A byte order mark selects UTF-8, UTF-16 or UTF-32 and is dropped. Without one the bytes are decoded
 * in the encoding given, or in the one {@link #detect(byte[])} recognizes. Line breaks ({@code CRLF}, a
 * lone {@code CR}, {@code LF}) all become a single line feed, and NUL characters are dropped.
 */
public final class TextFiles {
    /**
     * The fallback encoding of files that are not valid UTF-8 and that detection does not recognize.
     */
    private static final Charset LEGACY = charset("windows-1252", StandardCharsets.ISO_8859_1);

    /**
     * The number of leading bytes detection inspects.
     */
    private static final int SAMPLE = 64 * 1024;

    /**
     * Frequent characters of Simplified Chinese text.
     */
    private static final String SIMPLIFIED = "\u7684\u4E00\u662F\u4E0D\u4E86\u5728\u4EBA\u6709\u6211\u4ED6\u8FD9\u4E2A"
            + "\u4EEC\u4E2D\u6765\u4E0A\u5927\u4E3A\u548C\u56FD\u5730\u5230\u4EE5\u8BF4\u65F6\u8981\u5C31\u51FA\u4F1A"
            + "\u53EF\u4E5F\u4F60\u5BF9\u751F\u80FD\u800C\u5B50\u90A3\u5F97\u4E8E\u7740\u4E0B\u81EA\u4E4B\u5E74\u8FC7"
            + "\u53D1\u540E\u4F5C\u91CC\u7528\u9053\u884C\u6240\u7136\u5BB6\u79CD\u4E8B\u6210\u65B9\u591A\u7ECF\u4E48"
            + "\u53BB\u6CD5\u5B66\u5982\u90FD\u540C\u73B0\u5F53\u6CA1\u52A8\u9762\u8D77\u770B\u5B9A\u5929\u597D\u8FD8"
            + "\u4EC0\u5417\u5427\u5462\u554A";

    /**
     * Frequent characters of Traditional Chinese text.
     */
    private static final String TRADITIONAL = "\u7684\u4E00\u662F\u4E0D\u4E86\u5728\u4EBA\u6709\u6211\u4ED6\u9019\u500B"
            + "\u5011\u4E2D\u4F86\u4E0A\u5927\u70BA\u548C\u570B\u5730\u5230\u4EE5\u8AAA\u6642\u8981\u5C31\u51FA\u6703"
            + "\u53EF\u4E5F\u4F60\u5C0D\u751F\u80FD\u800C\u5B50\u90A3\u5F97\u65BC\u8457\u4E0B\u81EA\u4E4B\u5E74\u904E"
            + "\u767C\u5F8C\u4F5C\u88E1\u7528\u9053\u884C\u6240\u7136\u5BB6\u7A2E\u4E8B\u6210\u65B9\u591A\u7D93\u9EBC"
            + "\u53BB\u6CD5\u5B78\u5982\u90FD\u540C\u73FE\u7576\u6C92\u52D5\u9762\u8D77\u770B\u5B9A\u5929\u597D\u9084"
            + "\u4EC0\u55CE\u5427\u5462\u554A";

    private TextFiles() {
        throw new AssertionError();
    }

    /**
     * Returns a charset when the runtime has it.
     *
     * @param name the charset name
     * @param fallback the charset returned when the runtime lacks it, or {@code null}
     * @return the charset, or {@code fallback}
     */
    private static Charset charset(String name, Charset fallback) {
        try {
            return Charset.forName(name);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    /**
     * Decodes a text file, detecting its encoding.
     *
     * @param data the file bytes
     * @return the text with line feeds only, without a byte order mark
     */
    public static String decode(byte[] data) {
        return decode(data, null);
    }

    /**
     * Decodes a text file.
     *
     * @param data the file bytes
     * @param charset the encoding of a file without a byte order mark, or {@code null} to detect it
     * @return the text with line feeds only, without a byte order mark; bytes the encoding cannot map
     * become replacement characters
     */
    public static String decode(byte[] data, Charset charset) {
        var bom = byteOrderMark(data);
        String text;
        if (bom != null) {
            var skip = bom == StandardCharsets.UTF_8 ? 3 : bom == StandardCharsets.UTF_16LE || bom == StandardCharsets.UTF_16BE ? 2 : 4;
            text = new String(data, skip, data.length - skip, bom);
        } else {
            var chosen = charset != null ? charset : detect(data);
            text = new String(data, 0, data.length, chosen);
        }
        return normalizeLineBreaks(text);
    }

    /**
     * Returns the encoding a byte order mark at the start of the bytes selects.
     *
     * @param data the bytes
     * @return UTF-8, UTF-16LE, UTF-16BE, UTF-32LE or UTF-32BE, or {@code null} without a byte order mark
     */
    public static Charset byteOrderMark(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (data.length >= 4 && data.length % 4 == 0 && data[0] == 0 && data[1] == 0 && (data[2] & 0xFF) == 0xFE
                && (data[3] & 0xFF) == 0xFF) {
            return charset("UTF-32BE", null);
        }
        if (data.length >= 4 && data.length % 4 == 0 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE
                && data[2] == 0 && data[3] == 0) {
            return charset("UTF-32LE", null);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    /**
     * Detects the encoding of text without a byte order mark.
     *
     * <p>In order: UTF-16 when every other byte of the leading bytes is mostly zero; UTF-8 when the bytes are
     * valid UTF-8; Shift_JIS and EUC-JP when the bytes decode without error and hold kana; EUC-KR
     * (Windows-949) when they decode without error and are mostly Hangul; GBK or Big5 when they decode
     * without error, the one whose decoding holds more frequent Chinese characters; Windows-1251 and KOI8-R
     * when the bytes above 127 come in runs, as the letters of Cyrillic words do, in the case pattern of
     * lowercase Russian text; Windows-1252 otherwise. Only the leading bytes are inspected, and an encoding
     * the runtime lacks is never chosen.
     *
     * @param data the bytes
     * @return the encoding
     */
    public static Charset detect(byte[] data) {
        var length = Math.min(data.length, SAMPLE);
        var utf16 = utf16(data, length);
        if (utf16 != null) {
            return utf16;
        }
        if (validUtf8(data, length)) {
            return StandardCharsets.UTF_8;
        }
        var cut = length;
        if (length < data.length) {
            while (cut > 0 && length - cut < 4 && (data[cut - 1] & 0x80) != 0) {
                cut--;
            }
        }
        var japanese = japanese(data, cut);
        if (japanese != null) {
            return japanese;
        }
        var korean = charset("x-windows-949", charset("EUC-KR", null));
        var decoded = korean == null ? null : strict(korean, data, cut);
        if (decoded != null && ratio(decoded, 0xAC00, 0xD7A3) >= 0.9) {
            return korean;
        }
        var gbk = charset("GBK", null);
        var big5 = charset("Big5", null);
        var simplified = gbk == null ? null : strict(gbk, data, cut);
        var traditional = big5 == null ? null : strict(big5, data, cut);
        if (simplified != null || traditional != null) {
            var simplifiedScore = simplified == null ? -1 : frequent(simplified, SIMPLIFIED);
            var traditionalScore = traditional == null ? -1 : frequent(traditional, TRADITIONAL);
            if (Math.max(simplifiedScore, traditionalScore) > 0) {
                return simplifiedScore >= traditionalScore ? gbk : big5;
            }
        }
        var cyrillic = cyrillic(data, length);
        return cyrillic != null ? cyrillic : LEGACY;
    }

    /**
     * Returns whether bytes are valid UTF-8.
     *
     * @param data the bytes
     * @param length the number of leading bytes to check; a sequence cut by the end is accepted
     * @return {@code true} when every complete sequence is valid
     */
    private static boolean validUtf8(byte[] data, int length) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var input = ByteBuffer.wrap(data, 0, length);
        var output = CharBuffer.allocate(length + 1);
        var result = decoder.decode(input, output, length == data.length);
        return !result.isError();
    }

    /**
     * Recognizes UTF-16 without a byte order mark by its zero bytes.
     *
     * @param data the bytes
     * @param length the number of leading bytes to inspect
     * @return UTF-16LE or UTF-16BE, or {@code null}
     */
    private static Charset utf16(byte[] data, int length) {
        var pairs = length / 2;
        if (pairs < 4) {
            return null;
        }
        var evenZeros = 0;
        var oddZeros = 0;
        for (var index = 0; index + 1 < length; index += 2) {
            if (data[index] == 0) {
                evenZeros++;
            }
            if (data[index + 1] == 0) {
                oddZeros++;
            }
        }
        if (oddZeros > pairs * 0.4 && evenZeros < pairs * 0.05) {
            return StandardCharsets.UTF_16LE;
        }
        if (evenZeros > pairs * 0.4 && oddZeros < pairs * 0.05) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    /**
     * Recognizes Japanese text in Shift_JIS or EUC-JP.
     *
     * @param data the bytes
     * @param length the number of leading bytes to inspect, ending at a character boundary
     * @return the encoding, or {@code null}
     */
    private static Charset japanese(byte[] data, int length) {
        for (var name : new String[]{"windows-31j", "Shift_JIS", "EUC-JP"}) {
            var charset = charset(name, null);
            if (charset == null) {
                continue;
            }
            var decoded = strict(charset, data, length);
            if (decoded != null && ratio(decoded, 0x3040, 0x30FF) >= 0.05) {
                return charset;
            }
        }
        return null;
    }

    /**
     * Recognizes Cyrillic text in Windows-1251 or KOI8-R by its runs of bytes above 127.
     *
     * @param data the bytes
     * @param length the number of leading bytes to inspect
     * @return the encoding, or {@code null}
     */
    private static Charset cyrillic(byte[] data, int length) {
        var high = 0;
        var inRuns = 0;
        var upperHalf = 0;
        for (var index = 0; index < length; index++) {
            var value = data[index] & 0xFF;
            if (value < 0xC0) {
                continue;
            }
            high++;
            if (value >= 0xE0) {
                upperHalf++;
            }
            var previous = index > 0 ? data[index - 1] & 0xFF : 0;
            var next = index + 1 < length ? data[index + 1] & 0xFF : 0;
            if (previous >= 0xC0 || next >= 0xC0) {
                inRuns++;
            }
        }
        if (high < 8 || inRuns < high * 0.7) {
            return null;
        }
        if (upperHalf >= high * 0.6) {
            return charset("windows-1251", null);
        }
        if (upperHalf <= high * 0.4) {
            return charset("KOI8-R", null);
        }
        return null;
    }

    /**
     * Decodes bytes, failing on any byte the encoding cannot decode.
     *
     * @param charset the encoding
     * @param data the bytes
     * @param length the number of leading bytes
     * @return the text, or {@code null} when a byte does not decode
     */
    private static String strict(Charset charset, byte[] data, int length) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, 0, length)).toString();
        } catch (CharacterCodingException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Returns the share of the characters above 127 that lie in a range.
     *
     * @param text the characters to examine
     * @param first the first code point of the range
     * @param last the last code point of the range
     * @return the share, {@code 0} when the text has no character above 127
     */
    private static double ratio(String text, int first, int last) {
        var nonAscii = 0;
        var inRange = 0;
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character < 0x80) {
                continue;
            }
            nonAscii++;
            if (character >= first && character <= last) {
                inRange++;
            }
        }
        return nonAscii == 0 ? 0 : (double) inRange / nonAscii;
    }

    /**
     * Counts the characters of a text that belong to a set of frequent characters.
     *
     * @param text the characters to examine
     * @param frequent the frequent characters
     * @return the count
     */
    private static int frequent(String text, String frequent) {
        var count = 0;
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character >= 0x4E00 && frequent.indexOf(character) >= 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Decodes bytes as UTF-8 when they are valid UTF-8, and as Windows-1252 otherwise.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the text
     */
    public static String decodeUtf8OrLegacy(byte[] data, int offset, int length) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data, offset, length)).toString();
        } catch (CharacterCodingException exception) {
            return new String(data, offset, length, LEGACY);
        }
    }

    /**
     * Replaces every {@code CRLF} and lone {@code CR} with a line feed and drops NUL characters.
     *
     * @param text the text to normalize
     * @return the normalized text
     */
    public static String normalizeLineBreaks(String text) {
        if (text.indexOf('\r') < 0 && text.indexOf('\0') < 0) {
            return text;
        }
        var result = new StringBuilder(text.length());
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character == '\r') {
                result.append('\n');
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
            } else if (character != '\0') {
                result.append(character);
            }
        }
        return result.toString();
    }
}
