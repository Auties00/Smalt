package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts QuickTime text media ({@code text} sample entries, QuickTime File Format "Text Media") into
 * 3GPP timed text, the form of {@link com.github.auties00.smalt.track.Codec#MOV_TEXT} tracks.
 *
 * <p>The sample description becomes a {@code tx3g} sample entry body: its justification, background
 * color, default text box, font number, face and foreground color, with a font table naming the
 * description's font and the classic Macintosh font numbers. A sample's text, in Mac OS Roman or in
 * UTF-16 after a byte order mark, becomes UTF-8; its {@code styl} (TextEdit style scrap), {@code hlit},
 * {@code hclr} and {@code tbox} atoms become the 3GPP boxes, character offsets counted in code points;
 * its other atoms (drop shadow, image font, hypertext, metrics, wrap) are dropped.
 */
public final class QuickTimeText {
    /**
     * The {@code styl} atom type.
     */
    private static final int STYL = 0x7374796C;

    /**
     * The {@code hlit} atom type.
     */
    private static final int HLIT = 0x686C6974;

    /**
     * The {@code hclr} atom type.
     */
    private static final int HCLR = 0x68636C72;

    /**
     * The {@code tbox} atom type.
     */
    private static final int TBOX = 0x74626F78;

    /**
     * The {@code ftab} box type.
     */
    private static final int FTAB = 0x66746162;

    /**
     * The size of a TextEdit style scrap element.
     */
    private static final int STYLE_ELEMENT = 20;

    /**
     * The font size of a description, which has none, in points.
     */
    private static final int DEFAULT_SIZE = 12;

    /**
     * The characters of Mac OS Roman bytes 0x80 to 0xFF.
     */
    private static final String MAC_ROMAN = "\u00C4\u00C5\u00C7\u00C9\u00D1\u00D6\u00DC\u00E1\u00E0\u00E2\u00E4\u00E3"
            + "\u00E5\u00E7\u00E9\u00E8\u00EA\u00EB\u00ED\u00EC\u00EE\u00EF\u00F1\u00F3\u00F2\u00F4\u00F6\u00F5\u00FA"
            + "\u00F9\u00FB\u00FC\u2020\u00B0\u00A2\u00A3\u00A7\u2022\u00B6\u00DF\u00AE\u00A9\u2122\u00B4\u00A8\u2260"
            + "\u00C6\u00D8\u221E\u00B1\u2264\u2265\u00A5\u00B5\u2202\u2211\u220F\u03C0\u222B\u00AA\u00BA\u03A9\u00E6"
            + "\u00F8\u00BF\u00A1\u00AC\u221A\u0192\u2248\u2206\u00AB\u00BB\u2026\u00A0\u00C0\u00C3\u00D5\u0152\u0153"
            + "\u2013\u2014\u201C\u201D\u2018\u2019\u00F7\u25CA\u00FF\u0178\u2044\u20AC\u2039\u203A\uFB01\uFB02\u2021"
            + "\u00B7\u201A\u201E\u2030\u00C2\u00CA\u00C1\u00CB\u00C8\u00CD\u00CE\u00CF\u00CC\u00D3\u00D4\uF8FF\u00D2"
            + "\u00DA\u00DB\u00D9\u0131\u02C6\u02DC\u00AF\u02D8\u02D9\u02DA\u00B8\u02DD\u02DB\u02C7";

    /**
     * The classic Macintosh font numbers and their names.
     */
    private static final Map<Integer, String> MAC_FONTS = macFonts();

    private QuickTimeText() {
        throw new AssertionError();
    }

    /**
     * {@return the classic Macintosh font numbers and their names}
     */
    private static Map<Integer, String> macFonts() {
        var fonts = new LinkedHashMap<Integer, String>();
        fonts.put(0, "Chicago");
        fonts.put(2, "New York");
        fonts.put(3, "Geneva");
        fonts.put(4, "Monaco");
        fonts.put(5, "Venice");
        fonts.put(6, "London");
        fonts.put(7, "Athens");
        fonts.put(8, "San Francisco");
        fonts.put(9, "Toronto");
        fonts.put(11, "Cairo");
        fonts.put(12, "Los Angeles");
        fonts.put(20, "Times");
        fonts.put(21, "Helvetica");
        fonts.put(22, "Courier");
        fonts.put(23, "Symbol");
        fonts.put(24, "Mobile");
        return fonts;
    }

    /**
     * Decodes Mac OS Roman bytes.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the text
     */
    public static String macRoman(byte[] data, int offset, int length) {
        var builder = new StringBuilder(length);
        for (var index = offset; index < offset + length; index++) {
            var value = data[index] & 0xFF;
            builder.append(value < 0x80 ? (char) value : MAC_ROMAN.charAt(value - 0x80));
        }
        return builder.toString();
    }

    /**
     * Converts the body of a {@code text} sample description, after its eight leading bytes, into a
     * {@code tx3g} sample entry body.
     *
     * @param description the description body
     * @return the 3GPP configuration; a truncated description gives the default one
     */
    public static byte[] config(byte[] description) {
        if (description == null || description.length < 43) {
            return TimedText.defaultConfig();
        }
        var justification = ByteArrays.i32be(description, 4);
        var background = rgb16(description, 8);
        var box = Arrays.copyOfRange(description, 14, 22);
        var font = u16(description, 30);
        var face = u16(description, 32) & 7;
        var foreground = rgb16(description, 37);
        var nameLength = description.length > 43 ? description[43] & 0xFF : 0;
        var name = description.length >= 44 + nameLength ? macRoman(description, 44, nameLength) : "";
        var fonts = new LinkedHashMap<Integer, String>();
        if (!name.isEmpty()) {
            fonts.put(font, name);
        }
        for (var entry : MAC_FONTS.entrySet()) {
            fonts.putIfAbsent(entry.getKey(), entry.getValue());
        }
        var out = new ByteWriter(64 + fonts.size() * 16);
        out.u32be(0);
        out.u8(justification == 0 ? 0 : justification < 0 ? 0xFF : 1);
        out.u8(0xFF);
        writeRgba(out, background);
        out.bytes(box);
        out.u16be(0);
        out.u16be(0);
        out.u16be(font);
        out.u8(face);
        out.u8(DEFAULT_SIZE);
        writeRgba(out, foreground);
        out.begin(FTAB);
        out.u16be(fonts.size());
        for (var entry : fonts.entrySet()) {
            var bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            var length = Math.min(bytes.length, 255);
            out.u16be(entry.getKey());
            out.u8(length);
            out.bytes(Arrays.copyOf(bytes, length));
        }
        out.end();
        return out.toByteArray();
    }

    /**
     * Converts a QuickTime text sample into a 3GPP timed text sample.
     *
     * @param sample the QuickTime sample
     * @return the 3GPP sample
     */
    public static byte[] sample(byte[] sample) {
        if (sample.length < 2) {
            return TimedText.emptySample();
        }
        var length = Math.min(u16(sample, 0), sample.length - 2);
        String text;
        var utf16 = length >= 2 && (sample[2] & 0xFF) == 0xFE && (sample[3] & 0xFF) == 0xFF;
        if (utf16) {
            text = new String(sample, 4, length - 2, StandardCharsets.UTF_16BE);
        } else {
            text = macRoman(sample, 2, length);
        }
        var codePoints = text.codePointCount(0, text.length());
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            return TimedText.encode(new StyledText(List.of(StyledText.Run.of(text, 0)), 0, Double.NaN,
                    Double.NaN, ""), TimedText.DEFAULT, null, 0);
        }
        var out = new ByteWriter(bytes.length + 64);
        out.u16be(bytes.length);
        out.bytes(bytes);
        var position = 2 + length;
        while (position + 8 <= sample.length) {
            var size = ByteArrays.i32be(sample, position);
            var type = ByteArrays.i32be(sample, position + 4);
            if (size < 8 || size > sample.length - position) {
                break;
            }
            var end = position + size;
            switch (type) {
                case STYL -> {
                    if (size >= 10) {
                        var count = Math.min(u16(sample, position + 8), (size - 10) / STYLE_ELEMENT);
                        if (count > 0) {
                            out.begin(STYL);
                            out.u16be(count);
                            for (var index = 0; index < count; index++) {
                                var element = position + 10 + index * STYLE_ELEMENT;
                                var start = character(text, utf16, ByteArrays.i32be(sample, element), codePoints);
                                var stop = index + 1 < count
                                        ? character(text, utf16, ByteArrays.i32be(sample, element + STYLE_ELEMENT), codePoints)
                                        : codePoints;
                                out.u16be(Math.min(start, 0xFFFF));
                                out.u16be(Math.min(Math.max(start, stop), 0xFFFF));
                                out.u16be(u16(sample, element + 8));
                                out.u8(sample[element + 10] & 7);
                                out.u8(Math.clamp(u16(sample, element + 12), 1, 255));
                                writeRgba(out, rgb16(sample, element + 14));
                            }
                            out.end();
                        }
                    }
                }
                case HLIT -> {
                    if (size >= 16) {
                        out.begin(HLIT);
                        out.u16be(Math.min(character(text, utf16, ByteArrays.i32be(sample, position + 8), codePoints), 0xFFFF));
                        out.u16be(Math.min(character(text, utf16, ByteArrays.i32be(sample, position + 12), codePoints), 0xFFFF));
                        out.end();
                    }
                }
                case HCLR -> {
                    if (size >= 14) {
                        out.begin(HCLR);
                        writeRgba(out, rgb16(sample, position + 8));
                        out.end();
                    }
                }
                case TBOX -> {
                    if (size >= 16) {
                        out.begin(TBOX);
                        out.bytes(Arrays.copyOfRange(sample, position + 8, position + 16));
                        out.end();
                    }
                }
                default -> {
                }
            }
            position = end;
        }
        return out.toByteArray();
    }

    /**
     * Converts a byte offset of the QuickTime text into a code point offset.
     *
     * @param text the decoded text
     * @param utf16 whether the text was UTF-16, whose offsets count the byte order mark
     * @param offset the byte offset
     * @param codePoints the code points of the text
     * @return the code point offset, at most {@code codePoints}
     */
    private static int character(String text, boolean utf16, int offset, int codePoints) {
        if (offset <= 0) {
            return 0;
        }
        var units = utf16 ? Math.max(0, offset - 2) / 2 : offset;
        if (units >= text.length()) {
            return codePoints;
        }
        return text.codePointCount(0, units);
    }

    /**
     * Reads a QuickTime RGB color of three 16-bit components as an opaque color.
     *
     * @param data the bytes
     * @param offset the index of the red component
     * @return the color as {@code 0xAARRGGBB}
     */
    private static long rgb16(byte[] data, int offset) {
        return 0xFF000000L | (long) (data[offset] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8 | data[offset + 4] & 0xFF;
    }

    /**
     * Writes a color as RGBA bytes.
     *
     * @param out the destination
     * @param color the color as {@code 0xAARRGGBB}
     */
    private static void writeRgba(ByteWriter out, long color) {
        out.u8((int) (color >>> 16 & 0xFF));
        out.u8((int) (color >>> 8 & 0xFF));
        out.u8((int) (color & 0xFF));
        out.u8((int) (color >>> 24 & 0xFF));
    }

    /**
     * Reads an unsigned 16-bit big-endian value.
     *
     * @param data the bytes
     * @param offset the index of the high byte
     * @return the value
     */
    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }
}
