package com.github.auties00.smalt.internal.subtitle;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes the bytes of a text subtitle file and normalizes its line breaks.
 *
 * <p>A byte order mark selects UTF-8, UTF-16LE or UTF-16BE and is dropped. Without one the bytes
 * are decoded as UTF-8 when they are valid UTF-8, and as Windows-1252 otherwise, the encoding of
 * most legacy SubRip files. Line breaks ({@code CRLF}, a lone {@code CR}, {@code LF}) all become a
 * single line feed, and NUL characters are dropped.
 */
public final class TextFiles {
    /**
     * The fallback encoding of files that are not valid UTF-8.
     */
    private static final Charset LEGACY = legacyCharset();

    private TextFiles() {
        throw new AssertionError();
    }

    /**
     * Returns Windows-1252 when the runtime has it, and ISO 8859-1 otherwise.
     *
     * @return the charset
     */
    private static Charset legacyCharset() {
        try {
            return Charset.forName("windows-1252");
        } catch (RuntimeException exception) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /**
     * Decodes a text file.
     *
     * @param data the file bytes
     * @return the text with line feeds only, without a byte order mark
     */
    public static String decode(byte[] data) {
        String text;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            text = new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        } else if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            text = new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        } else if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            text = new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        } else {
            text = decodeUtf8OrLegacy(data, 0, data.length);
        }
        return normalizeLineBreaks(text);
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
