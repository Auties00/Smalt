package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Rational;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the samples and the sample entry of 3GPP timed text ({@code tx3g}, 3GPP TS
 * 26.245), the subtitle format of MP4 files.
 *
 * <p>A sample is a 16-bit big-endian byte count, the text (UTF-8, or UTF-16 when it starts with a
 * byte order mark), then modifier boxes. Characters are counted as Unicode code points, line breaks
 * included. Reading maps:
 * <ul>
 *   <li>{@code styl} records to the bold, italic and underline styles, the text color, the font (named by
 *       the sample entry's {@code ftab}) and the font size of {@link StyledText} runs;</li>
 *   <li>{@code hlit} with {@code hclr} to a background color (the text color, as inverse video, without
 *       {@code hclr});</li>
 *   <li>{@code krok} events to karaoke times;</li>
 *   <li>the sample entry justification to the alignment, and a {@code tbox} to the anchor position,
 *       measured in the region the default text box spans.</li>
 * </ul>
 * The text color, font and size are kept only where they differ from the sample entry's default style
 * (and the color where it is not opaque white), so that a plain track reads as plain text. Writing does
 * the reverse with {@code styl}, {@code hlit}, {@code hclr} and {@code krok}, and with a {@code tbox} whose
 * anchor edge is the position when the text is anchored and the sample entry has a default text box;
 * scrolling, hyperlinks, blinking and wrapping boxes are ignored when reading and not written.
 */
public final class TimedText {
    /**
     * The {@code styl} box type.
     */
    private static final int STYL = 0x7374796C;

    /**
     * The {@code hlit} box type.
     */
    private static final int HLIT = 0x686C6974;

    /**
     * The {@code hclr} box type.
     */
    private static final int HCLR = 0x68636C72;

    /**
     * The {@code krok} box type.
     */
    private static final int KROK = 0x6B726F6B;

    /**
     * The {@code tbox} box type.
     */
    private static final int TBOX = 0x74626F78;

    /**
     * The {@code ftab} box type.
     */
    private static final int FTAB = 0x66746162;

    /**
     * The font size of the default style.
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

    /**
     * The parsed default configuration.
     */
    public static final Config DEFAULT = Config.parse(DEFAULT_CONFIG);

    private TimedText() {
        throw new AssertionError();
    }

    /**
     * Holds the fields of a {@code tx3g} sample entry the conversions use.
     *
     * @param horizontal the horizontal justification: 0 left, 1 center, -1 right
     * @param vertical the vertical justification: 0 top, 1 center, -1 bottom
     * @param box the default text box: top, left, bottom, right
     * @param font the default font identifier
     * @param face the default face flags: 1 bold, 2 italic, 4 underline
     * @param size the default font size in pixels
     * @param color the default text color as {@code 0xAARRGGBB}
     * @param fonts the font names by identifier
     */
    public record Config(int horizontal, int vertical, int[] box, int font, int face, int size, long color,
                         Map<Integer, String> fonts) {
        /**
         * Parses the body of a sample entry, using the default values for a missing or truncated part.
         *
         * @param config the body after the eight leading bytes, or {@code null}
         * @return the configuration
         */
        public static Config parse(byte[] config) {
            if (config == null || config.length < 30) {
                return DEFAULT;
            }
            var horizontal = config[4];
            var vertical = config[5];
            var box = new int[]{s16(config, 10), s16(config, 12), s16(config, 14), s16(config, 16)};
            var font = u16(config, 22);
            var face = config[24] & 0xFF;
            var size = config[25] & 0xFF;
            var color = rgba(config, 26);
            var fonts = new HashMap<Integer, String>();
            var position = 30;
            while (position + 8 <= config.length) {
                var boxSize = ByteArrays.i32be(config, position);
                var type = ByteArrays.i32be(config, position + 4);
                if (boxSize < 8 || boxSize > config.length - position) {
                    break;
                }
                if (type == FTAB && boxSize >= 10) {
                    var count = u16(config, position + 8);
                    var entry = position + 10;
                    var end = position + boxSize;
                    for (var index = 0; index < count && entry + 3 <= end; index++) {
                        var id = u16(config, entry);
                        var length = config[entry + 2] & 0xFF;
                        if (entry + 3 + length > end) {
                            break;
                        }
                        fonts.putIfAbsent(id, fontName(config, entry + 3, length));
                        entry += 3 + length;
                    }
                }
                position += boxSize;
            }
            return new Config(horizontal, vertical, box, font, face, size, color, Map.copyOf(fonts));
        }

        /**
         * {@return the numpad alignment of the justification, {@code 0} for bottom center}
         */
        int alignment() {
            var column = horizontal == 0 ? 0 : horizontal < 0 ? 2 : 1;
            var row = vertical == 0 ? 2 : vertical < 0 ? 0 : 1;
            var alignment = row * 3 + column + 1;
            return alignment == 2 ? 0 : alignment;
        }

        /**
         * Returns the identifier of a font name.
         *
         * @param name the font name
         * @return the identifier the font table gives it, or the default font when it has none
         */
        int fontId(String name) {
            for (var entry : fonts.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(name)) {
                    return entry.getKey();
                }
            }
            return font;
        }
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
        return sample.length < 3 || u16(sample, 0) == 0;
    }

    /**
     * Decodes a sample.
     *
     * @param sample the 3GPP timed text sample
     * @param config the sample entry of the track
     * @param timeBase the time base of the track, which counts the karaoke times
     * @return the styled text, empty for a sample without text
     */
    public static StyledText decode(byte[] sample, Config config, Rational timeBase) {
        if (isEmpty(sample)) {
            return StyledText.EMPTY;
        }
        var length = Math.min(u16(sample, 0), sample.length - 2);
        String text;
        if (length >= 2 && (sample[2] & 0xFF) == 0xFE && (sample[3] & 0xFF) == 0xFF) {
            text = new String(sample, 4, length - 2, StandardCharsets.UTF_16BE);
        } else if (length >= 2 && (sample[2] & 0xFF) == 0xFF && (sample[3] & 0xFF) == 0xFE) {
            text = new String(sample, 4, length - 2, StandardCharsets.UTF_16LE);
        } else {
            text = TextFiles.decodeUtf8OrLegacy(sample, 2, length);
        }
        var codePoints = text.codePoints().toArray();
        var count = codePoints.length;
        var faces = new int[count];
        var colors = new long[count];
        var fonts = new int[count];
        var sizes = new int[count];
        var backgrounds = new long[count];
        var karaoke = new long[count];
        Arrays.fill(faces, config.face() & 7);
        Arrays.fill(colors, config.color());
        Arrays.fill(fonts, config.font());
        Arrays.fill(sizes, config.size());
        Arrays.fill(backgrounds, StyledText.NO_COLOR);
        Arrays.fill(karaoke, StyledText.NO_KARAOKE);
        var highlightStart = -1;
        var highlightEnd = -1;
        var highlightColor = StyledText.NO_COLOR;
        int[] textBox = null;
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
                        var records = u16(sample, position + 8);
                        var record = position + 10;
                        for (var entry = 0; entry < records && record + 12 <= end; entry++, record += 12) {
                            var from = u16(sample, record);
                            var to = Math.min(u16(sample, record + 2), count);
                            for (var index = from; index < to; index++) {
                                fonts[index] = u16(sample, record + 4);
                                faces[index] = sample[record + 6] & 7;
                                sizes[index] = sample[record + 7] & 0xFF;
                                colors[index] = rgba(sample, record + 8);
                            }
                        }
                    }
                }
                case HLIT -> {
                    if (size >= 12 && highlightStart < 0) {
                        highlightStart = u16(sample, position + 8);
                        highlightEnd = Math.min(u16(sample, position + 10), count);
                    }
                }
                case HCLR -> {
                    if (size >= 12) {
                        highlightColor = rgba(sample, position + 8);
                    }
                }
                case KROK -> {
                    if (size >= 14 && timeBase != null) {
                        var time = Integer.toUnsignedLong(ByteArrays.i32be(sample, position + 8));
                        var entries = u16(sample, position + 12);
                        var record = position + 14;
                        for (var entry = 0; entry < entries && record + 8 <= end; entry++, record += 8) {
                            var endTime = Integer.toUnsignedLong(ByteArrays.i32be(sample, record));
                            var from = u16(sample, record + 4);
                            var to = Math.min(u16(sample, record + 6), count);
                            var micros = micros(timeBase, time);
                            for (var index = from; index < to; index++) {
                                karaoke[index] = micros;
                            }
                            time = Math.max(time, endTime);
                        }
                    }
                }
                case TBOX -> {
                    if (size >= 16) {
                        textBox = new int[]{s16(sample, position + 8), s16(sample, position + 10),
                                s16(sample, position + 12), s16(sample, position + 14)};
                    }
                }
                default -> {
                }
            }
            position = end;
        }
        if (highlightStart >= 0) {
            for (var index = highlightStart; index < highlightEnd; index++) {
                backgrounds[index] = highlightColor != StyledText.NO_COLOR ? highlightColor : colors[index];
            }
        }
        var builder = new StyledText.Builder();
        var defaultFont = config.fonts().getOrDefault(config.font(), "");
        for (var index = 0; index < count; index++) {
            var codePoint = codePoints[index];
            if (codePoint == 0) {
                continue;
            }
            if (codePoint == '\r') {
                if (index + 1 < count && codePoints[index + 1] == '\n') {
                    continue;
                }
                codePoint = '\n';
            }
            if (karaoke[index] >= 0 && builder.format().karaoke() != karaoke[index]) {
                builder.karaoke(karaoke[index]);
            }
            var color = colors[index] == 0xFFFFFFFFL ? StyledText.NO_COLOR : colors[index];
            var font = config.fonts().getOrDefault(fonts[index], "");
            var current = builder.format();
            builder.format(new StyledText.Run("", faces[index], color, backgrounds[index],
                    font.equals(defaultFont) ? "" : font, sizes[index] == config.size() ? Double.NaN : sizes[index],
                    current.karaoke()));
            builder.appendCodePoint(codePoint);
        }
        var styled = builder.build();
        var alignment = config.alignment();
        if (textBox != null && config.box()[2] > config.box()[0] && config.box()[3] > config.box()[1]) {
            var regionHeight = config.box()[2];
            var regionWidth = config.box()[3];
            var column = config.horizontal() == 0 ? 0 : config.horizontal() < 0 ? 2 : 1;
            var row = config.vertical() == 0 ? 2 : config.vertical() < 0 ? 0 : 1;
            var x = column == 0 ? textBox[1] : column == 2 ? textBox[3] : (textBox[1] + textBox[3]) / 2.0;
            var y = row == 2 ? textBox[0] : row == 0 ? textBox[2] : (textBox[0] + textBox[2]) / 2.0;
            return styled.withPlacement(row * 3 + column + 1, Math.clamp(x / regionWidth, 0, 1),
                    Math.clamp(y / regionHeight, 0, 1));
        }
        return alignment == 0 ? styled : styled.withPlacement(alignment, Double.NaN, Double.NaN);
    }

    /**
     * Decodes a sample into SubRip text, under the default sample entry.
     *
     * @param sample the 3GPP timed text sample
     * @return the text with SubRip style tags, empty for a sample without text
     */
    public static String decodeText(byte[] sample) {
        return Markup.write(decode(sample, DEFAULT, null), SubtitleMarkup.SUBRIP, Markup.Context.NONE);
    }

    /**
     * Encodes styled text as a sample: the text, then the {@code styl}, {@code hlit}, {@code hclr},
     * {@code krok} and {@code tbox} boxes it needs.
     *
     * <p>The {@code tbox} is written for anchored text under a sample entry with a default text box: the
     * edge the entry's justification anchors (or the middle, for centered justification) lies at the
     * position, measured in the region the default text box spans, so that it reads back to that position.
     *
     * <p>Text longer than the 65535 bytes a sample holds is cut at the last whole code point that
     * fits. A font the sample entry's font table does not name is written as the default font.
     *
     * @param styled the styled text
     * @param config the sample entry of the track
     * @param timeBase the time base of the track, which counts the karaoke times, or {@code null} to write
     * no karaoke
     * @param durationMicros the length of the sample, which ends the last karaoke syllable
     * @return the sample
     */
    public static byte[] encode(StyledText styled, Config config, Rational timeBase, long durationMicros) {
        // TODO: write a tbox for anchored cues under a sample entry without a default text box, once data tracks
        //  carry the region (the track size) such a text box is measured in
        var text = new StringBuilder();
        var records = new ArrayList<int[]>();
        var colors = new ArrayList<Long>();
        var highlightStart = -1;
        var highlightEnd = -1;
        var highlightColor = StyledText.NO_COLOR;
        var syllables = new ArrayList<long[]>();
        var codePoints = 0;
        var karaokeRuns = styled.hasKaraoke();
        var lastSyllable = -1L;
        for (var run : styled.runs()) {
            var count = run.text().codePointCount(0, run.text().length());
            if (count == 0) {
                continue;
            }
            var color = run.color() != StyledText.NO_COLOR ? run.color() : 0xFFFFFFFFL;
            var font = run.font().isEmpty() ? config.font() : config.fontId(run.font());
            var size = Double.isNaN(run.size()) ? config.size() : (int) Math.clamp(Math.round(run.size()), 1, 255);
            var face = run.style() & 7;
            if (face != (config.face() & 7) || color != config.color() || font != config.font() || size != config.size()) {
                records.add(new int[]{codePoints, codePoints + count, font, face, size});
                colors.add(color);
            }
            if (run.background() != StyledText.NO_COLOR) {
                if (highlightStart < 0) {
                    highlightStart = codePoints;
                    highlightColor = run.background();
                }
                if (highlightEnd < 0 || highlightEnd == codePoints) {
                    highlightEnd = codePoints + count;
                }
            }
            if (karaokeRuns) {
                var start = Math.max(0, run.karaoke() >= 0 ? run.karaoke() : lastSyllable);
                if (syllables.isEmpty() || start != lastSyllable) {
                    syllables.add(new long[]{start, codePoints, codePoints + count});
                    lastSyllable = start;
                } else {
                    syllables.getLast()[2] = codePoints + count;
                }
            }
            text.append(run.text());
            codePoints += count;
        }
        var bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        var limit = codePoints;
        if (bytes.length > 0xFFFF) {
            var cut = 0xFFFF;
            while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
                cut--;
            }
            var kept = new String(bytes, 0, cut, StandardCharsets.UTF_8);
            bytes = kept.getBytes(StandardCharsets.UTF_8);
            limit = kept.codePointCount(0, kept.length());
        }
        var out = new ByteWriter(bytes.length + 64 + records.size() * 12 + syllables.size() * 8);
        out.u16be(bytes.length);
        out.bytes(bytes);
        var styles = new ArrayList<Integer>();
        for (var index = 0; index < records.size(); index++) {
            if (records.get(index)[0] < limit && records.get(index)[0] <= 0xFFFF) {
                styles.add(index);
            }
        }
        if (!styles.isEmpty()) {
            var count = Math.min(styles.size(), (0xFFFF - 10) / 12);
            out.begin(STYL);
            out.u16be(count);
            for (var index = 0; index < count; index++) {
                var record = records.get(styles.get(index));
                out.u16be(record[0]);
                out.u16be(Math.min(Math.min(record[1], limit), 0xFFFF));
                out.u16be(record[2]);
                out.u8(record[3]);
                out.u8(record[4]);
                writeRgba(out, colors.get(styles.get(index)));
            }
            out.end();
        }
        if (highlightStart >= 0 && highlightStart < limit) {
            out.begin(HLIT);
            out.u16be(Math.min(highlightStart, 0xFFFF));
            out.u16be(Math.min(Math.min(highlightEnd, limit), 0xFFFF));
            out.end();
            out.begin(HCLR);
            writeRgba(out, highlightColor);
            out.end();
        }
        if (karaokeRuns && timeBase != null && !syllables.isEmpty()) {
            var entries = new ArrayList<long[]>();
            for (var syllable : syllables) {
                if (syllable[1] < limit) {
                    entries.add(syllable);
                }
            }
            if (!entries.isEmpty()) {
                var count = Math.min(entries.size(), (0xFFFF - 14) / 8);
                out.begin(KROK);
                out.u32be(units(timeBase, entries.getFirst()[0]));
                out.u16be(count);
                for (var index = 0; index < count; index++) {
                    var syllable = entries.get(index);
                    var end = index + 1 < entries.size() ? entries.get(index + 1)[0] : Math.max(syllable[0], durationMicros);
                    out.u32be(units(timeBase, end));
                    out.u16be((int) Math.min(syllable[1], 0xFFFF));
                    out.u16be((int) Math.min(Math.min(syllable[2], limit), 0xFFFF));
                }
                out.end();
            }
        }
        var region = config.box();
        if (styled.hasPosition() && region[2] > region[0] && region[3] > region[1]) {
            var width = region[3];
            var height = region[2];
            var x = (int) Math.round(Math.clamp(styled.x(), 0, 1) * width);
            var y = (int) Math.round(Math.clamp(styled.y(), 0, 1) * height);
            var column = config.horizontal() == 0 ? 0 : config.horizontal() < 0 ? 2 : 1;
            var row = config.vertical() == 0 ? 2 : config.vertical() < 0 ? 0 : 1;
            var halfWidth = Math.max(0, Math.min(x, width - x));
            var halfHeight = Math.max(0, Math.min(y, height - y));
            var left = column == 0 ? x : column == 2 ? 0 : x - halfWidth;
            var right = column == 0 ? width : column == 2 ? x : x + halfWidth;
            var top = row == 2 ? y : row == 0 ? 0 : y - halfHeight;
            var bottom = row == 2 ? height : row == 0 ? y : y + halfHeight;
            out.begin(TBOX);
            out.u16be(s16Clamp(top));
            out.u16be(s16Clamp(left));
            out.u16be(s16Clamp(bottom));
            out.u16be(s16Clamp(right));
            out.end();
        }
        return out.toByteArray();
    }

    /**
     * Clamps a coordinate to a signed 16-bit value.
     *
     * @param value the coordinate
     * @return the coordinate as the unsigned 16-bit pattern of its clamped signed value
     */
    private static int s16Clamp(int value) {
        return Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE) & 0xFFFF;
    }

    /**
     * Encodes SubRip text as a sample under the default sample entry.
     *
     * @param text the SubRip text
     * @return the sample, {@link #emptySample()} for empty text
     */
    public static byte[] encodeText(String text) {
        return encode(Markup.parse(text, SubtitleMarkup.SUBRIP, Markup.Context.NONE), DEFAULT, null, 0);
    }

    /**
     * Combines the samples shown at the same time into one sample, their texts on separate lines
     * in the given order.
     *
     * <p>The {@code styl}, {@code hlit}, {@code hclr} and {@code krok} boxes of each sample are moved to its
     * place in the combined text; the combined sample keeps the first highlight, karaoke and text box of the
     * samples.
     *
     * @param samples the 3GPP timed text samples shown together, in order
     * @return the combined sample
     */
    public static byte[] merge(List<byte[]> samples) {
        var text = new StringBuilder();
        var styles = new ByteWriter(64);
        var styleCount = 0;
        byte[] highlight = null;
        byte[] highlightColor = null;
        byte[] karaoke = null;
        byte[] textBox = null;
        var offset = 0;
        for (var sample : samples) {
            if (isEmpty(sample)) {
                continue;
            }
            var length = Math.min(u16(sample, 0), sample.length - 2);
            String part;
            if (length >= 2 && (sample[2] & 0xFF) == 0xFE && (sample[3] & 0xFF) == 0xFF) {
                part = new String(sample, 4, length - 2, StandardCharsets.UTF_16BE);
            } else if (length >= 2 && (sample[2] & 0xFF) == 0xFF && (sample[3] & 0xFF) == 0xFE) {
                part = new String(sample, 4, length - 2, StandardCharsets.UTF_16LE);
            } else {
                part = TextFiles.decodeUtf8OrLegacy(sample, 2, length);
            }
            if (!text.isEmpty()) {
                text.append('\n');
                offset++;
            }
            var count = part.codePointCount(0, part.length());
            var position = 2 + length;
            while (position + 8 <= sample.length) {
                var size = ByteArrays.i32be(sample, position);
                var type = ByteArrays.i32be(sample, position + 4);
                if (size < 8 || size > sample.length - position) {
                    break;
                }
                var end = position + size;
                if (type == STYL && size >= 10) {
                    var records = u16(sample, position + 8);
                    var record = position + 10;
                    for (var entry = 0; entry < records && record + 12 <= end; entry++, record += 12) {
                        styles.u16be(Math.min(0xFFFF, offset + Math.min(u16(sample, record), count)));
                        styles.u16be(Math.min(0xFFFF, offset + Math.min(u16(sample, record + 2), count)));
                        styles.bytes(Arrays.copyOfRange(sample, record + 4, record + 12));
                        styleCount++;
                    }
                } else if (type == HLIT && size >= 12 && highlight == null) {
                    highlight = shifted(sample, position + 8, 2, offset, count);
                } else if (type == HCLR && size >= 12 && highlightColor == null) {
                    highlightColor = Arrays.copyOfRange(sample, position + 8, position + 12);
                } else if (type == KROK && size >= 14 && karaoke == null) {
                    var entries = u16(sample, position + 12);
                    var kept = Math.min(entries, (size - 14) / 8);
                    var box = new ByteWriter(6 + kept * 8);
                    box.bytes(Arrays.copyOfRange(sample, position + 8, position + 12));
                    box.u16be(kept);
                    for (var entry = 0; entry < kept; entry++) {
                        var record = position + 14 + entry * 8;
                        box.bytes(Arrays.copyOfRange(sample, record, record + 4));
                        box.bytes(shifted(sample, record + 4, 2, offset, count));
                    }
                    karaoke = box.toByteArray();
                } else if (type == TBOX && size >= 16 && textBox == null) {
                    textBox = Arrays.copyOfRange(sample, position + 8, position + 16);
                }
                position = end;
            }
            text.append(part);
            offset += count;
        }
        var bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            var plain = new StyledText(List.of(StyledText.Run.of(text.toString(), 0)), 0, Double.NaN, Double.NaN, "");
            return encode(plain, DEFAULT, null, 0);
        }
        var out = new ByteWriter(bytes.length + 64 + styleCount * 12);
        out.u16be(bytes.length);
        out.bytes(bytes);
        if (styleCount > 0) {
            out.begin(STYL);
            out.u16be(Math.min(styleCount, (0xFFFF - 10) / 12));
            var records = styles.toByteArray();
            out.bytes(Arrays.copyOf(records, Math.min(records.length, (0xFFFF - 10) / 12 * 12)));
            out.end();
        }
        if (highlight != null) {
            out.begin(HLIT);
            out.bytes(highlight);
            out.end();
        }
        if (highlightColor != null) {
            out.begin(HCLR);
            out.bytes(highlightColor);
            out.end();
        }
        if (karaoke != null) {
            out.begin(KROK);
            out.bytes(karaoke);
            out.end();
        }
        if (textBox != null) {
            out.begin(TBOX);
            out.bytes(textBox);
            out.end();
        }
        return out.toByteArray();
    }

    /**
     * Copies character offsets, moved by an offset and bounded by the characters of their sample.
     *
     * @param sample the 3GPP timed text sample
     * @param position the index of the first 16-bit offset
     * @param count the number of offsets
     * @param shift the characters before the sample's text in the combined text
     * @param characters the characters of the sample's text
     * @return the shifted offsets, big-endian
     */
    private static byte[] shifted(byte[] sample, int position, int count, int shift, int characters) {
        var result = new byte[count * 2];
        for (var index = 0; index < count; index++) {
            var value = Math.min(0xFFFF, shift + Math.min(u16(sample, position + index * 2), characters + 1));
            result[index * 2] = (byte) (value >>> 8);
            result[index * 2 + 1] = (byte) value;
        }
        return result;
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
     * Reads RGBA bytes as a color.
     *
     * @param data the bytes
     * @param offset the index of the red byte
     * @return the color as {@code 0xAARRGGBB}
     */
    private static long rgba(byte[] data, int offset) {
        return (long) (data[offset + 3] & 0xFF) << 24 | (data[offset] & 0xFF) << 16 | (data[offset + 1] & 0xFF) << 8
                | data[offset + 2] & 0xFF;
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

    /**
     * Reads a signed 16-bit big-endian value.
     *
     * @param data the bytes
     * @param offset the index of the high byte
     * @return the value
     */
    private static int s16(byte[] data, int offset) {
        return (short) u16(data, offset);
    }

    /**
     * Decodes a font name of the font table: UTF-8, or UTF-16 after a byte order mark.
     *
     * @param data the bytes
     * @param offset the index of the name
     * @param length the name length in bytes
     * @return the name
     */
    private static String fontName(byte[] data, int offset, int length) {
        if (length >= 2 && (data[offset] & 0xFF) == 0xFE && (data[offset + 1] & 0xFF) == 0xFF) {
            return new String(data, offset + 2, length - 2, StandardCharsets.UTF_16BE);
        }
        return TextFiles.decodeUtf8OrLegacy(data, offset, length);
    }

    /**
     * Converts a time in track units to microseconds, saturating.
     *
     * @param timeBase the track time base
     * @param units the time
     * @return the time in microseconds
     */
    private static long micros(Rational timeBase, long units) {
        try {
            return timeBase.toMicros(units);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE / 2;
        }
    }

    /**
     * Converts microseconds to track units, clamped to 32 bits.
     *
     * @param timeBase the track time base
     * @param micros the time
     * @return the time in track units
     */
    private static long units(Rational timeBase, long micros) {
        try {
            return Math.clamp(Rational.MICROSECONDS.rescale(micros, timeBase), 0, 0xFFFFFFFFL);
        } catch (ArithmeticException exception) {
            return 0xFFFFFFFFL;
        }
    }
}
