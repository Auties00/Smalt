package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the samples and the sample entry of 3GPP timed text ({@code tx3g}, 3GPP TS
 * 26.245), the subtitle format of MP4 files.
 *
 * <p>A sample is a 16-bit big-endian byte count, the text (UTF-8, or UTF-16 when it starts with a
 * byte order mark), then modifier boxes. Of those, {@code styl} style records map to the bold,
 * italic and underline styles of {@link Markup}, counting characters as Unicode code points; the
 * other boxes (highlight, karaoke, scrolling, hyperlinks, text box, blinking) have no counterpart
 * in the other formats and are ignored when reading and not written.
 */
public final class TimedText {
    /**
     * The {@code styl} box type.
     */
    private static final int STYL = 0x7374796C;

    /**
     * The font size of the default style and of the written style records.
     */
    private static final int FONT_SIZE = 16;

    /**
     * The sample that clears the screen: an empty text.
     */
    private static final byte[] EMPTY = {0, 0};

    /**
     * The body of the default {@code tx3g} sample entry after its eight leading bytes: no display
     * flags, centered at the bottom, an opaque black background color, an empty default text box,
     * the default style (font 1, 16 pixels, opaque white) and a font table naming font 1
     * {@code Arial}.
     *
     * @implNote This implementation uses the values common writers emit, which readers treat as
     * plain text; a reader may turn style records that differ from them into font tags.
     */
    private static final byte[] DEFAULT_CONFIG = {
            0, 0, 0, 0,
            1, (byte) 0xFF,
            0, 0, 0, (byte) 0xFF,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 1, 0, FONT_SIZE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            0, 0, 0, 18, 'f', 't', 'a', 'b', 0, 1, 0, 1, 5, 'A', 'r', 'i', 'a', 'l'
    };

    private TimedText() {
        throw new AssertionError();
    }

    /**
     * Returns the body of the default sample entry.
     *
     * @return a new copy of the configuration
     */
    public static byte[] defaultConfig() {
        return DEFAULT_CONFIG.clone();
    }

    /**
     * Returns the sample that clears the screen.
     *
     * @return a new empty sample
     */
    public static byte[] emptySample() {
        return EMPTY.clone();
    }

    /**
     * Returns whether a sample shows no text.
     *
     * @param sample the 3GPP timed text sample
     * @return {@code true} when the text is empty or the sample is truncated before it
     */
    public static boolean isEmpty(byte[] sample) {
        return sample.length < 3 || ((sample[0] & 0xFF) << 8 | (sample[1] & 0xFF)) == 0;
    }

    /**
     * Decodes a sample into styled runs.
     *
     * @param sample the 3GPP timed text sample
     * @return the runs, empty for a sample without text
     */
    public static List<Markup.Span> decode(byte[] sample) {
        if (isEmpty(sample)) {
            return List.of();
        }
        var length = Math.min((sample[0] & 0xFF) << 8 | (sample[1] & 0xFF), sample.length - 2);
        String text;
        if (length >= 2 && (sample[2] & 0xFF) == 0xFE && (sample[3] & 0xFF) == 0xFF) {
            text = new String(sample, 4, length - 2, StandardCharsets.UTF_16BE);
        } else {
            text = TextFiles.decodeUtf8OrLegacy(sample, 2, length);
        }
        text = TextFiles.normalizeLineBreaks(text);
        var codePoints = text.codePoints().toArray();
        var styles = new int[codePoints.length];
        var position = 2 + length;
        while (position + 8 <= sample.length) {
            var size = ByteArrays.i32be(sample, position);
            var type = ByteArrays.i32be(sample, position + 4);
            if (size < 8 || size > sample.length - position) {
                break;
            }
            if (type == STYL && size >= 10) {
                var count = (sample[position + 8] & 0xFF) << 8 | (sample[position + 9] & 0xFF);
                var record = position + 10;
                for (var entry = 0; entry < count && record + 12 <= position + size; entry++, record += 12) {
                    var start = (sample[record] & 0xFF) << 8 | (sample[record + 1] & 0xFF);
                    var end = Math.min((sample[record + 2] & 0xFF) << 8 | (sample[record + 3] & 0xFF), styles.length);
                    var face = sample[record + 6] & (Markup.BOLD | Markup.ITALIC | Markup.UNDERLINE);
                    for (var index = start; index < end; index++) {
                        styles[index] |= face;
                    }
                }
            }
            position += size;
        }
        var spans = new ArrayList<Markup.Span>();
        var run = new StringBuilder();
        var style = codePoints.length == 0 ? 0 : styles[0];
        for (var index = 0; index < codePoints.length; index++) {
            if (styles[index] != style) {
                spans.add(new Markup.Span(run.toString(), style));
                run.setLength(0);
                style = styles[index];
            }
            run.appendCodePoint(codePoints[index]);
        }
        if (!run.isEmpty()) {
            spans.add(new Markup.Span(run.toString(), style));
        }
        return spans;
    }

    /**
     * Decodes a sample into SubRip text.
     *
     * @param sample the 3GPP timed text sample
     * @return the text with SubRip style tags, empty for a sample without text
     */
    public static String decodeText(byte[] sample) {
        return Markup.write(decode(sample), SubtitleMarkup.SUBRIP);
    }

    /**
     * Encodes styled runs as a sample: the text, then a {@code styl} box when a run is styled.
     *
     * <p>Text longer than the 65535 bytes a sample holds is cut at the last whole code point that
     * fits.
     *
     * @param spans the runs
     * @return the sample
     */
    public static byte[] encode(List<Markup.Span> spans) {
        var text = new StringBuilder();
        var records = new ArrayList<int[]>();
        var codePoints = 0;
        for (var span : spans) {
            var count = span.text().codePointCount(0, span.text().length());
            if (span.style() != 0 && count > 0) {
                records.add(new int[]{codePoints, codePoints + count, span.style()});
            }
            text.append(span.text());
            codePoints += count;
        }
        var bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            var cut = 0xFFFF;
            while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
                cut--;
            }
            var kept = new String(bytes, 0, cut, StandardCharsets.UTF_8);
            bytes = kept.getBytes(StandardCharsets.UTF_8);
            var limit = kept.codePointCount(0, kept.length());
            records.removeIf(record -> record[0] >= limit);
            for (var record : records) {
                record[1] = Math.min(record[1], limit);
            }
        }
        records.removeIf(record -> record[0] > 0xFFFF);
        var out = new ByteWriter(bytes.length + 16 + records.size() * 12);
        out.u16be(bytes.length);
        out.bytes(bytes);
        if (!records.isEmpty()) {
            var count = Math.min(records.size(), (0xFFFF - 10) / 12);
            out.begin(STYL);
            out.u16be(count);
            for (var index = 0; index < count; index++) {
                var record = records.get(index);
                out.u16be(record[0]);
                out.u16be(Math.min(record[1], 0xFFFF));
                out.u16be(1);
                out.u8(record[2]);
                out.u8(FONT_SIZE);
                out.u32be(0xFFFFFFFFL);
            }
            out.end();
        }
        return out.toByteArray();
    }

    /**
     * Encodes SubRip text as a sample.
     *
     * @param text the SubRip text
     * @return the sample, {@link #emptySample()} for empty text
     */
    public static byte[] encodeText(String text) {
        return encode(Markup.parse(text, SubtitleMarkup.SUBRIP));
    }

    /**
     * Combines the samples shown at the same time into one sample, their texts on separate lines
     * in the given order.
     *
     * @param samples the 3GPP timed text samples shown together, in order
     * @return the combined sample
     */
    public static byte[] merge(List<byte[]> samples) {
        var spans = new ArrayList<Markup.Span>();
        for (var sample : samples) {
            var decoded = decode(sample);
            if (decoded.isEmpty()) {
                continue;
            }
            if (!spans.isEmpty()) {
                spans.add(new Markup.Span("\n", 0));
            }
            spans.addAll(decoded);
        }
        return encode(spans);
    }
}
