package com.github.auties00.smalt.internal.subtitle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Decodes HTML character references the way the HTML tokenizer does in text (HTML Standard, 13.2.5.72 to 13.2.5.80),
 * as the WebVTT cue text tokenizer requires: a named reference is the longest name of the HTML Standard's table
 * that follows the ampersand, with or without its semicolon for the legacy names that have both forms; a numeric
 * reference is decimal or hexadecimal, its semicolon optional, and a zero, surrogate or out-of-range value becomes
 * U+FFFD and a C1 control the Windows-1252 character of its byte.
 *
 * <p>The table ({@code html-entities.tsv}, 2231 names) is loaded on first use.
 */
public final class HtmlEntities {
    /**
     * The characters of the C1 controls 0x80 to 0x9F that a numeric reference replaces, from Windows-1252 (0 where
     * the control is kept).
     */
    private static final char[] C1 = {
            0x20AC, 0, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0, 0x017D, 0,
            0, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0, 0x017E,
            0x0178};

    private HtmlEntities() {
        throw new AssertionError();
    }

    /**
     * Holds the table, loaded on first use.
     */
    private static final class Table {
        /**
         * The code points of each name (the semicolon included when the name has one).
         */
        static final Map<String, String> NAMES = new HashMap<>(4096);

        /**
         * The length of the longest name.
         */
        static final int LONGEST;

        static {
            var longest = 0;
            try (var in = HtmlEntities.class.getResourceAsStream("html-entities.tsv")) {
                if (in == null) {
                    throw new IllegalStateException("html-entities.tsv is missing");
                }
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    var tab = line.indexOf('\t');
                    var text = new StringBuilder(2);
                    for (var code : line.substring(tab + 1).split(" ")) {
                        text.appendCodePoint(Integer.parseInt(code, 16));
                    }
                    NAMES.put(line.substring(0, tab), text.toString());
                    longest = Math.max(longest, tab);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            LONGEST = longest;
        }
    }

    /**
     * Decodes the character reference that starts at an ampersand.
     *
     * @param text the text holding the reference
     * @param ampersand the index of the ampersand
     * @param out the destination of the decoded characters
     * @return the index of the reference's last character, or {@code -1} when no reference starts there (nothing
     *         is appended)
     */
    public static int decode(String text, int ampersand, StringBuilder out) {
        var start = ampersand + 1;
        if (start < text.length() && text.charAt(start) == '#') {
            return numeric(text, start + 1, out);
        }
        var limit = Math.min(text.length(), start + Table.LONGEST);
        for (var end = limit; end > start; end--) {
            var value = Table.NAMES.get(text.substring(start, end));
            if (value != null) {
                out.append(value);
                return end - 1;
            }
        }
        return -1;
    }

    /**
     * Decodes a numeric character reference.
     *
     * @param text the text holding the reference
     * @param at the index after {@code &#}
     * @param out the destination
     * @return the index of the reference's last character, or {@code -1} without digits
     */
    private static int numeric(String text, int at, StringBuilder out) {
        var hex = at < text.length() && (text.charAt(at) == 'x' || text.charAt(at) == 'X');
        var index = hex ? at + 1 : at;
        var digits = index;
        long value = 0;
        while (index < text.length() && Character.digit(text.charAt(index), hex ? 16 : 10) >= 0) {
            value = Math.min(value * (hex ? 16 : 10) + Character.digit(text.charAt(index), hex ? 16 : 10), 0x110000);
            index++;
        }
        if (index == digits) {
            return -1;
        }
        if (index < text.length() && text.charAt(index) == ';') {
            index++;
        }
        int code;
        if (value == 0 || value > 0x10FFFF || value >= 0xD800 && value <= 0xDFFF) {
            code = 0xFFFD;
        } else if (value >= 0x80 && value <= 0x9F && C1[(int) value - 0x80] != 0) {
            code = C1[(int) value - 0x80];
        } else {
            code = (int) value;
        }
        out.appendCodePoint(code);
        return index - 1;
    }
}
