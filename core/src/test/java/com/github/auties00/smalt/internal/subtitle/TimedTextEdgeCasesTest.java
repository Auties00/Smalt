package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the 3GPP timed text samples and sample entries the round-trip tests do not write: UTF-16 text and font
 * names, cut and repeated modifier boxes, text boxes measured in the track region, text longer than a sample holds,
 * and the merge of samples carrying every modifier box.
 */
@DisplayName("3GPP timed text edge cases")
class TimedTextEdgeCasesTest {
    private static final Rational MILLIS = Rational.timeBaseOf(1000);

    static byte[] box(String type, int... values) {
        var body = new byte[values.length];
        for (var index = 0; index < values.length; index++) {
            body[index] = (byte) values[index];
        }
        return box(type, body);
    }

    static byte[] box(String type, byte[] body) {
        var out = ByteBuffer.allocate(8 + body.length);
        out.putInt(8 + body.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(body);
        return out.array();
    }

    static byte[] sample(byte[] text, byte[]... boxes) {
        var out = new ByteArrayOutputStream();
        out.write(text.length >> 8);
        out.write(text.length);
        out.writeBytes(text);
        for (var box : boxes) {
            out.writeBytes(box);
        }
        return out.toByteArray();
    }

    static byte[] utf16(String text, boolean bigEndian) {
        var bom = bigEndian ? new byte[]{(byte) 0xFE, (byte) 0xFF} : new byte[]{(byte) 0xFF, (byte) 0xFE};
        var body = text.getBytes(bigEndian ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE);
        var out = new byte[2 + body.length];
        System.arraycopy(bom, 0, out, 0, 2);
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    static byte[] u16s(int... values) {
        var out = new byte[values.length * 2];
        for (var index = 0; index < values.length; index++) {
            out[index * 2] = (byte) (values[index] >> 8);
            out[index * 2 + 1] = (byte) values[index];
        }
        return out;
    }

    static byte[] style(int from, int to, int font, int face, int size, int rgba) {
        var out = ByteBuffer.allocate(12);
        out.putShort((short) from).putShort((short) to).putShort((short) font).put((byte) face).put((byte) size)
                .putInt(rgba);
        return out.array();
    }

    static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * Returns a sample entry body with a justification, a default text box and a font table box.
     */
    static byte[] config(int horizontal, int vertical, int[] box, byte[]... extra) {
        var out = ByteBuffer.allocate(30);
        out.putInt(0).put((byte) horizontal).put((byte) vertical).putInt(0);
        for (var value : box) {
            out.putShort((short) value);
        }
        out.putInt(0).putShort((short) 1).put((byte) 0).put((byte) 18).putInt(0xFFFFFFFF);
        return join(out.array(), join(extra));
    }

    @Test
    @DisplayName("decodes UTF-16 text in either byte order")
    void utf16Text() {
        var big = sample(utf16("caf\u00E9", true));
        var little = sample(utf16("caf\u00E9", false));
        assertEquals("caf\u00E9", TimedText.decode(big, TimedText.DEFAULT, null).plain());
        assertEquals("caf\u00E9", TimedText.decode(little, TimedText.DEFAULT, null).plain());
        var merged = TimedText.decode(TimedText.merge(List.of(big, little, TimedText.emptySample())), TimedText.DEFAULT,
                null);
        assertEquals("caf\u00E9\ncaf\u00E9", merged.plain());
    }

    @Test
    @DisplayName("reads UTF-16 and UTF-8 font names and stops at a cut font table or box")
    void fontTable() {
        var names = join(u16s(3), new byte[]{0, 1, 4}, "Mono".getBytes(StandardCharsets.UTF_8),
                new byte[]{0, 2, 10}, utf16("Sans", true), new byte[]{0, 3, 50});
        var config = TimedText.Config.parse(config(1, -1, new int[]{0, 0, 0, 0}, box("ftab", names),
                new byte[]{0, 0, 0, 4, 'x', 'x', 'x', 'x'}));
        assertEquals("Mono", config.fonts().get(1));
        assertEquals("Sans", config.fonts().get(2));
        assertFalse(config.fonts().containsKey(3));
        var shortTable = TimedText.Config.parse(config(0, 0, new int[]{0, 0, 0, 0}, box("ftab", 0)));
        assertTrue(shortTable.fonts().isEmpty());
        assertEquals(TimedText.DEFAULT, TimedText.Config.parse(new byte[29]));
    }

    @Test
    @DisplayName("places a text box in the default text box, else in the track region, else not at all")
    void textBoxes() {
        var text = "x".getBytes(StandardCharsets.UTF_8);
        var placed = sample(text, box("tbox", u16s(100, 50, 140, 150)));
        var inBox = TimedText.Config.parse(config(0, 0, new int[]{0, 0, 200, 300}));
        var styled = TimedText.decode(placed, inBox, null);
        assertEquals(7, styled.alignment());
        assertEquals(50 / 300.0, styled.x(), 1e-9);
        assertEquals(0.5, styled.y(), 1e-9);
        var noBox = TimedText.Config.parse(config(-1, -1, new int[]{0, 0, 0, 0}));
        var inTrack = TimedText.decode(placed, noBox.withTrackSize(400, 280), null);
        assertEquals(3, inTrack.alignment());
        assertEquals(150 / 400.0, inTrack.x(), 1e-9);
        assertEquals(0.5, inTrack.y(), 1e-9);
        var unknown = TimedText.decode(placed, noBox.withTrackSize(0, 280), null);
        assertFalse(unknown.hasPosition());
        assertEquals(3, unknown.alignment());
        var centered = TimedText.Config.parse(config(1, 1, new int[]{0, 0, 200, 300}));
        var middle = TimedText.decode(placed, centered, null);
        assertEquals(5, middle.alignment());
        assertEquals(100 / 300.0, middle.x(), 1e-9);
        assertEquals(0.6, middle.y(), 1e-9);
        for (var config : List.of(inBox, centered, noBox.withTrackSize(400, 280))) {
            var back = TimedText.decode(TimedText.encode(StyledText.EMPTY.withRuns(List.of(StyledText.Run.of("x", 0)))
                    .withPlacement(5, 0.25, 0.75), config, null, 0), config, null);
            assertEquals(0.25, back.x(), 0.01);
            assertEquals(0.75, back.y(), 0.01);
        }
    }

    @Test
    @DisplayName("ignores cut modifier boxes and keeps the first highlight")
    void cutBoxes() {
        var text = "hello".getBytes(StandardCharsets.UTF_8);
        var sample = sample(text, box("styl", 0), box("hlit", 0), box("hclr", 0),
                box("krok", 0), box("tbox", 0), box("hlit", u16s(0, 2)), box("hlit", u16s(3, 5)),
                box("hclr", 0, 0, 255, 255), new byte[]{0, 0, 0, 2, 'b', 'a', 'd', '!'});
        var styled = TimedText.decode(sample, TimedText.DEFAULT, MILLIS);
        assertEquals("hello", styled.plain());
        assertEquals(0xFF0000FFL, styled.runs().getFirst().background());
        assertEquals("he", styled.runs().getFirst().text());
        var noColor = TimedText.decode(sample(text, box("hlit", u16s(1, 3))), TimedText.DEFAULT, null);
        assertEquals(TimedText.DEFAULT.color(), noColor.runs().get(1).background(),
                "a highlight without a color takes the text color");
        assertEquals("el", noColor.runs().get(1).text());
        assertFalse(TimedText.decode(sample(text, box("krok", 0, 0, 0, 10, 0, 1, 0, 0, 0, 20, 0, 0, 0, 2)),
                TimedText.DEFAULT, null).hasKaraoke(), "karaoke needs a time base");
    }

    @Test
    @DisplayName("merges samples carrying every modifier box, keeping the first of each")
    void mergeBoxes() {
        var first = sample("ab".getBytes(StandardCharsets.UTF_8), box("styl", join(u16s(1), style(0, 1, 1, 1, 18, -1))),
                box("hlit", u16s(0, 2)), box("hclr", 255, 0, 0, 255),
                box("krok", join(new byte[]{0, 0, 0, 0}, u16s(1), new byte[]{0, 0, 0, 100}, u16s(0, 2))),
                box("tbox", u16s(1, 2, 3, 4)));
        var second = sample("cd".getBytes(StandardCharsets.UTF_8), box("styl", join(u16s(1), style(1, 9, 1, 2, 18, -1))),
                box("hlit", u16s(0, 1)), box("hclr", 0, 255, 0, 255),
                box("krok", join(new byte[]{0, 0, 0, 0}, u16s(1), new byte[]{0, 0, 0, 50}, u16s(0, 1))),
                box("tbox", u16s(5, 6, 7, 8)));
        var merged = TimedText.merge(List.of(first, second));
        var styled = TimedText.decode(merged, TimedText.DEFAULT, MILLIS);
        assertEquals("ab\ncd", styled.plain());
        var runs = styled.runs();
        assertEquals(StyledText.BOLD, runs.getFirst().style());
        assertEquals(0xFFFF0000L, runs.getFirst().background());
        assertTrue(styled.hasKaraoke());
        var italic = runs.stream().filter(run -> run.text().contains("d")).findFirst().orElseThrow();
        assertEquals(StyledText.ITALIC, italic.style());
        assertArrayEquals(merged, TimedText.merge(List.of(merged)));
    }

    @Test
    @DisplayName("cuts text longer than a sample holds at a whole code point, with its styles")
    void longText() {
        var text = "\u00E9".repeat(40_000);
        var styled = StyledText.EMPTY.withRuns(List.of(new StyledText.Run(text, StyledText.BOLD, StyledText.NO_COLOR,
                0xFF00FF00L, "", Double.NaN, 0), new StyledText.Run("tail", StyledText.ITALIC, StyledText.NO_COLOR,
                0xFF00FF00L, "", Double.NaN, 500_000)));
        var sample = TimedText.encode(styled, TimedText.DEFAULT, MILLIS, 2_000_000);
        var back = TimedText.decode(sample, TimedText.DEFAULT, MILLIS);
        assertEquals(32767, back.plain().length());
        assertEquals(StyledText.BOLD, back.runs().getFirst().style());
        assertFalse(back.plain().contains("tail"));
        var merged = TimedText.merge(List.of(TimedText.encodeText("\u00E9".repeat(30_000)),
                TimedText.encodeText("\u00E9".repeat(30_000))));
        assertEquals(32768, TimedText.decode(merged, TimedText.DEFAULT, null).plain().length(),
                "30000 characters, a line break, then the 2767 that fit");
    }

    @Test
    @DisplayName("writes a highlight over adjacent runs only, and karaoke syllables of runs without a time")
    void encodeRuns() {
        var styled = StyledText.EMPTY.withRuns(List.of(
                new StyledText.Run("a", 0, StyledText.NO_COLOR, 0xFFFF0000L, "", Double.NaN, 0),
                new StyledText.Run("b", StyledText.BOLD, StyledText.NO_COLOR, 0xFFFF0000L, "", Double.NaN, -1),
                new StyledText.Run("c", 0, StyledText.NO_COLOR, StyledText.NO_COLOR, "", 300, 1_000_000),
                new StyledText.Run("d", 0, StyledText.NO_COLOR, 0xFF00FF00L, "", Double.NaN, 1_000_000),
                new StyledText.Run("", 0, StyledText.NO_COLOR, StyledText.NO_COLOR, "", Double.NaN, -1)));
        var back = TimedText.decode(TimedText.encode(styled, TimedText.DEFAULT, MILLIS, 3_000_000), TimedText.DEFAULT,
                MILLIS);
        assertEquals("abcd", back.plain());
        assertEquals(0xFFFF0000L, back.runs().getFirst().background());
        assertEquals(255, back.runs().stream().filter(run -> run.text().equals("c")).findFirst().orElseThrow().size());
        var noKaraoke = TimedText.decode(TimedText.encode(styled, TimedText.DEFAULT, null, 0), TimedText.DEFAULT, MILLIS);
        assertFalse(noKaraoke.hasKaraoke());
    }
}
