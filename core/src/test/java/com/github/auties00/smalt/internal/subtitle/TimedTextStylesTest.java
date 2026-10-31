package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the 3GPP timed text styling (3GPP TS 26.245): {@code styl} colors, fonts and sizes against the sample
 * entry's default style and font table, {@code hlit}/{@code hclr} highlights, {@code krok} karaoke and
 * {@code tbox} placement, both ways, and the conversion of QuickTime text media into 3GPP samples.
 */
@DisplayName("3GPP timed text styles")
class TimedTextStylesTest {
    private static final char BS = (char) 92;

    private static final Rational MILLIS = Rational.MILLISECONDS;

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] u16(int value) {
        return new byte[]{(byte) (value >> 8), (byte) value};
    }

    static byte[] u32(long value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    static byte[] rgba(long argb) {
        return new byte[]{(byte) (argb >> 16), (byte) (argb >> 8), (byte) argb, (byte) (argb >> 24)};
    }

    static byte[] box(String type, byte[]... parts) {
        var body = concat(parts);
        return concat(u32(8 + body.length), type.getBytes(StandardCharsets.US_ASCII), body);
    }

    static byte[] font(int id, String name) {
        var bytes = name.getBytes(StandardCharsets.UTF_8);
        return concat(u16(id), new byte[]{(byte) bytes.length}, bytes);
    }

    /** A tx3g entry body: justification, an optional default box, white 18-pixel Arial (font 1), Courier as font 2. */
    static byte[] config(int horizontal, int vertical, int boxBottom, int boxRight) {
        return concat(u32(0), new byte[]{(byte) horizontal, (byte) vertical}, rgba(0xFF000000L),
                u16(0), u16(0), u16(boxBottom), u16(boxRight),
                u16(0), u16(0), u16(1), new byte[]{0, 18}, rgba(0xFFFFFFFFL),
                box("ftab", u16(2), font(1, "Arial"), font(2, "Courier")));
    }

    static byte[] sample(String text, byte[]... boxes) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        return concat(u16(bytes.length), bytes, concat(boxes));
    }

    static byte[] styleRecord(int start, int end, int font, int face, int size, long color) {
        return concat(u16(start), u16(end), u16(font), new byte[]{(byte) face, (byte) size}, rgba(color));
    }

    private static final byte[] STYLED = sample("Hello world",
            box("styl", u16(1), styleRecord(0, 5, 2, 1, 24, 0xFFFF0000L)),
            box("hlit", u16(6), u16(11)),
            box("hclr", rgba(0xFF0000FFL)),
            box("krok", u32(0), u16(2), u32(500), u16(0), u16(5), u32(1000), u16(6), u16(11)));

    @Nested
    @DisplayName("reading")
    class Reading {
        @Test
        @DisplayName("maps styl records to styles, colors, fonts and sizes that differ from the default style")
        void styles() {
            var config = TimedText.Config.parse(config(1, -1, 0, 0));
            assertEquals(18, config.size());
            assertEquals("Courier", config.fonts().get(2));
            var styled = TimedText.decode(STYLED, config, MILLIS);
            var runs = styled.runs();
            assertEquals(3, runs.size());
            assertEquals(new StyledText.Run("Hello", StyledText.BOLD, 0xFFFF0000L, StyledText.NO_COLOR, "Courier", 24, 0),
                    runs.get(0));
            assertEquals(new StyledText.Run(" ", 0, StyledText.NO_COLOR, StyledText.NO_COLOR, "", Double.NaN, 0), runs.get(1));
            assertEquals(new StyledText.Run("world", 0, StyledText.NO_COLOR, 0xFF0000FFL, "", Double.NaN, 500_000),
                    runs.get(2));
            assertEquals(0, styled.alignment());
            var subRip = Markup.write(styled, SubtitleMarkup.SUBRIP, Markup.Context.NONE);
            assertEquals("<font color=\"#ff0000\" face=\"Courier\" size=\"24\"><b>Hello</b></font> world", subRip);
            var webVtt = Markup.write(styled, SubtitleMarkup.WEBVTT, new Markup.Context(2_000_000, 4_000_000, null, ""));
            assertEquals("<c.red><b>Hello</b></c> <00:00:02.500><c.bg_blue>world</c>", webVtt);
        }

        @Test
        @DisplayName("places text by the justification and by a tbox inside the default text box")
        void placement() {
            assertEquals(7, TimedText.decode(sample("x"), TimedText.Config.parse(config(0, 0, 0, 0)), MILLIS).alignment());
            assertEquals(6, TimedText.decode(sample("x"), TimedText.Config.parse(config(-1, 1, 0, 0)), MILLIS).alignment());
            var boxed = TimedText.decode(sample("x", box("tbox", u16(0), u16(0), u16(20), u16(400))),
                    TimedText.Config.parse(config(1, -1, 60, 400)), MILLIS);
            assertEquals(2, boxed.alignment());
            assertEquals(0.5, boxed.x(), 1e-9);
            assertEquals(1.0 / 3, boxed.y(), 1e-9);
            var ignored = TimedText.decode(sample("x", box("tbox", u16(0), u16(0), u16(20), u16(400))),
                    TimedText.Config.parse(config(1, -1, 0, 0)), MILLIS);
            assertTrue(!ignored.hasPosition());
        }

        @Test
        @DisplayName("counts characters as code points, line breaks included, and turns CRLF into one line feed")
        void characters() {
            var text = "\uD83D\uDE00\r\nab";
            var sample = sample(text, box("styl", u16(1), styleRecord(3, 4, 1, 2, 16, 0xFFFFFFFFL)));
            var styled = TimedText.decode(sample, TimedText.DEFAULT, MILLIS);
            assertEquals(List.of(StyledText.Run.of("\uD83D\uDE00\n", 0), StyledText.Run.of("a", StyledText.ITALIC),
                    StyledText.Run.of("b", 0)), styled.runs());
        }

        @Test
        @DisplayName("survive truncated and random boxes")
        void robustness() {
            var random = new Random(26245);
            var config = TimedText.Config.parse(config(1, -1, 60, 400));
            for (var round = 0; round < 2000; round++) {
                var bytes = STYLED.clone();
                for (var flips = 0; flips < 4; flips++) {
                    bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
                }
                var cut = java.util.Arrays.copyOf(bytes, random.nextInt(bytes.length + 1));
                assertDoesNotThrow(() -> TimedText.decode(cut, config, MILLIS));
                assertDoesNotThrow(() -> QuickTimeText.sample(cut));
                assertDoesNotThrow(() -> TimedText.Config.parse(cut));
                assertDoesNotThrow(() -> QuickTimeText.config(cut));
            }
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {
        @Test
        @DisplayName("writes styl, hlit, hclr and krok boxes that read back to the same runs")
        void roundTrip() {
            var config = TimedText.Config.parse(config(1, -1, 0, 0));
            var styled = TimedText.decode(STYLED, config, MILLIS);
            var encoded = TimedText.encode(styled, config, MILLIS, 2_000_000);
            assertEquals(styled, TimedText.decode(encoded, config, MILLIS));
            var text = new String(encoded, 2, 11, StandardCharsets.UTF_8);
            assertEquals("Hello world", text);
            var boxes = new String(encoded, StandardCharsets.ISO_8859_1);
            assertTrue(boxes.contains("styl") && boxes.contains("hlit") && boxes.contains("hclr") && boxes.contains("krok"));
        }

        @Test
        @DisplayName("writes a tbox anchored at the position inside the default text box, and none without one")
        void textBox() {
            for (var justification : new int[][]{{0, 0}, {1, -1}, {-1, 1}, {1, 1}}) {
                var config = TimedText.Config.parse(config(justification[0], justification[1], 60, 400));
                var placed = new StyledText(List.of(StyledText.Run.of("x", 0)), 0, 0.25, 0.5, "");
                var encoded = TimedText.encode(placed, config, MILLIS, 0);
                assertTrue(new String(encoded, StandardCharsets.ISO_8859_1).contains("tbox"));
                var back = TimedText.decode(encoded, config, MILLIS);
                assertEquals(0.25, back.x(), 1e-9);
                assertEquals(0.5, back.y(), 1e-9);
                assertEquals(TimedText.decode(sample("x"), config, MILLIS).alignment() == 0 ? 2
                        : TimedText.decode(sample("x"), config, MILLIS).alignment(), back.alignment());
            }
            var boxless = TimedText.Config.parse(config(1, -1, 0, 0));
            var encoded = TimedText.encode(new StyledText(List.of(StyledText.Run.of("x", 0)), 0, 0.25, 0.5, ""), boxless,
                    MILLIS, 0);
            assertTrue(!new String(encoded, StandardCharsets.ISO_8859_1).contains("tbox"));
            var unplaced = TimedText.encode(StyledText.EMPTY.withPlacement(0, Double.NaN, Double.NaN),
                    TimedText.Config.parse(config(1, -1, 60, 400)), MILLIS, 0);
            assertTrue(!new String(unplaced, StandardCharsets.ISO_8859_1).contains("tbox"));
        }

        @Test
        @DisplayName("writes the fonts the font table names and the default font for the others")
        void fonts() {
            var config = TimedText.Config.parse(config(1, -1, 0, 0));
            var styled = Markup.parse("<font face=\"courier\">a</font><font face=\"Comic Sans\">b</font>", SubtitleMarkup.SUBRIP,
                    Markup.Context.NONE);
            var decoded = TimedText.decode(TimedText.encode(styled, config, MILLIS, 0), config, MILLIS);
            assertEquals("ab", decoded.plain());
            assertEquals(List.of(new StyledText.Run("a", 0, StyledText.NO_COLOR, StyledText.NO_COLOR, "Courier", Double.NaN,
                    StyledText.NO_KARAOKE), StyledText.Run.of("b", 0)), decoded.runs());
        }

        @Test
        @DisplayName("encode SubRip, WebVTT and ASS cues into styled samples through the encoder")
        void encoder() {
            var track = SubtitleEncoder.track(Codec.MOV_TEXT, 1);
            var encoder = new SubtitleEncoder(track);
            var decoder = new SubtitleDecoder(track);
            var cue = SubtitleCue.of(1_000_000, 3_000_000, "{" + BS + "k50}Ka{" + BS + "k30}ra{" + BS + "c&H0000FF&" + BS
                    + "s1}oke");
            var packet = encoder.encode(cue, SubtitleMarkup.ASS);
            var back = decoder.decode(packet, SubtitleMarkup.ASS);
            // the third run continues the second syllable, which lasts until the cue ends; 3GPP text has no strikeout
            assertEquals("{" + BS + "k50}Ka{" + BS + "k150}ra{" + BS + "c&H0000FF&}oke", back.text());
            var italic = decoder.decode(encoder.encode(SubtitleCue.of(0, 1_000_000, "<i>it</i>"), SubtitleMarkup.WEBVTT));
            assertEquals("<i>it</i>", italic.text());
        }

        @Test
        @DisplayName("merges simultaneous samples, moving their boxes to their place in the combined text")
        void merge() {
            var first = sample("ab", box("styl", u16(1), styleRecord(0, 2, 1, 1, 16, 0xFFFFFFFFL)));
            var second = sample("cd", box("styl", u16(1), styleRecord(1, 2, 1, 2, 16, 0xFFFFFFFFL)),
                    box("krok", u32(0), u16(1), u32(300), u16(0), u16(2)));
            var merged = TimedText.merge(List.of(first, TimedText.emptySample(), second));
            var styled = TimedText.decode(merged, TimedText.DEFAULT, MILLIS);
            assertEquals("ab\ncd", styled.plain());
            assertEquals("<b>ab</b>\nc<i>d</i>", Markup.write(styled, SubtitleMarkup.SUBRIP, Markup.Context.NONE));
            assertTrue(styled.hasKaraoke());
            assertEquals(0, styled.runs().getLast().karaoke());
        }
    }

    @Nested
    @DisplayName("QuickTime text media")
    class QuickTime {
        static byte[] rgb16(int red, int green, int blue) {
            return concat(u16(red), u16(green), u16(blue));
        }

        static byte[] description() {
            var name = "Geneva".getBytes(StandardCharsets.US_ASCII);
            return concat(u32(0), u32(1), rgb16(0, 0, 0), u16(0), u16(0), u16(40), u16(320), new byte[8], u16(3), u16(1),
                    new byte[3], rgb16(0xFFFF, 0xFFFF, 0), new byte[]{(byte) name.length}, name);
        }

        static byte[] element(int start, int font, int face, int size, int red, int green, int blue) {
            return concat(u32(start), u16(16), u16(12), u16(font), new byte[]{(byte) face, 0}, u16(size),
                    rgb16(red, green, blue));
        }

        @Test
        @DisplayName("decodes Mac OS Roman exactly as the JDK's charset does")
        void macRoman() {
            var bytes = new byte[256];
            for (var index = 0; index < 256; index++) {
                bytes[index] = (byte) index;
            }
            assertEquals(new String(bytes, Charset.forName("x-MacRoman")), QuickTimeText.macRoman(bytes, 0, 256));
        }

        @Test
        @DisplayName("converts the description and the samples, style elements and highlight atoms")
        void convert() {
            var config = QuickTimeText.config(description());
            var parsed = TimedText.Config.parse(config);
            assertEquals(1, parsed.horizontal());
            assertEquals(-1, parsed.vertical());
            assertEquals(3, parsed.font());
            assertEquals(1, parsed.face());
            assertEquals(0xFFFFFF00L, parsed.color());
            assertEquals("Geneva", parsed.fonts().get(3));
            assertEquals("Courier", parsed.fonts().get(22));
            assertArrayEquals(new int[]{0, 0, 40, 320}, parsed.box());
            var text = new byte[]{'C', 'a', 'f', (byte) 0x8E};
            var sample = concat(u16(text.length), text,
                    box("styl", u16(2), element(0, 22, 2, 18, 0xFFFF, 0, 0), element(3, 3, 0, 12, 0xFFFF, 0xFFFF, 0)),
                    box("hlit", u32(0), u32(2)), box("hclr", rgb16(0, 0xFFFF, 0)), box("drpo", u16(1), u16(1)));
            var converted = QuickTimeText.sample(sample);
            var styled = TimedText.decode(converted, parsed, MILLIS);
            assertEquals("Caf\u00E9", styled.plain());
            assertEquals(List.of(
                    new StyledText.Run("Ca", StyledText.ITALIC, 0xFFFF0000L, 0xFF00FF00L, "Courier", 18, -1),
                    new StyledText.Run("f", StyledText.ITALIC, 0xFFFF0000L, StyledText.NO_COLOR, "Courier", 18, -1),
                    new StyledText.Run("\u00E9", 0, 0xFFFFFF00L, StyledText.NO_COLOR, "", Double.NaN, -1)), styled.runs());
            var utf16 = "\uFEFFhi".getBytes(StandardCharsets.UTF_16BE);
            var wide = TimedText.decode(QuickTimeText.sample(concat(u16(utf16.length), utf16,
                    box("styl", u16(1), element(4, 3, 1, 12, 0xFFFF, 0xFFFF, 0)))), parsed, MILLIS);
            assertEquals("hi", wide.plain());
            assertEquals(StyledText.BOLD, wide.runs().getLast().style());
        }
    }
}
