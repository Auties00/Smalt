package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the text subtitle codecs: SubRip and WebVTT parsing and writing with the irregularities
 * real files have, the markup conversions, 3GPP timed text samples, ISO WebVTT samples and the
 * cutting of overlapping cues into MP4 samples.
 */
@DisplayName("Text subtitle formats")
class SubtitleTextTest {
    private static List<SubtitleCue> srt(String text) {
        return SubRipText.parse(TextFiles.decode(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Nested
    @DisplayName("SubRip")
    class SubRip {
        @Test
        @DisplayName("parses cues with their markup, lines and millisecond times")
        void basic() {
            var cues = srt("""
                    1
                    00:00:01,000 --> 00:00:02,500
                    Hello <i>world</i>
                    second line

                    2
                    00:00:03,000 --> 00:00:04,000
                    {\\an8}Top
                    """);
            assertEquals(2, cues.size());
            assertEquals(SubtitleCue.of(1_000_000, 2_500_000, "Hello <i>world</i>\nsecond line"), cues.get(0));
            assertEquals("{\\an8}Top", cues.get(1).text());
        }

        @Test
        @DisplayName("tolerates a BOM, CRLF and CR line breaks, dots, missing hours and position extensions")
        void irregular() {
            var text = "\uFEFF1\r\n00:00:01.5 --> 00:00:02,25  X1:10 X2:20 Y1:1 Y2:2\r\nA\r\n\r\n2\r00:03,000 --> 00:04,000\rB\r";
            var cues = SubRipText.parse(TextFiles.decode(text.getBytes(StandardCharsets.UTF_8)));
            assertEquals(2, cues.size());
            assertEquals(SubtitleCue.of(1_500_000, 2_250_000, "A"), cues.get(0));
            assertEquals(SubtitleCue.of(3_000_000, 4_000_000, "B"), cues.get(1));
        }

        @Test
        @DisplayName("tolerates missing blank lines, blank lines inside a cue, wrong counters and leading junk")
        void missingSeparators() {
            var cues = srt("""
                    junk before
                    7
                    00:00:01,000 --> 00:00:02,000
                    first
                    3
                    00:00:02,000 --> 00:00:03,000
                    second

                    still second
                    00:00:03,000 --> 00:00:04,000
                    third
                    """);
            assertEquals(List.of("first", "second\n\nstill second", "third"), cues.stream().map(SubtitleCue::text).toList());
        }

        @Test
        @DisplayName("sorts cues by start time and keeps overlapping cues")
        void sortsAndOverlaps() {
            var cues = srt("""
                    1
                    00:00:05,000 --> 00:00:06,000
                    late

                    2
                    00:00:01,000 --> 00:00:09,000
                    long

                    3
                    00:00:02,000 --> 00:00:03,000
                    inside
                    """);
            assertEquals(List.of("long", "inside", "late"), cues.stream().map(SubtitleCue::text).toList());
        }

        @Test
        @DisplayName("reads UTF-16 and Windows-1252 files")
        void encodings() {
            var text = "1\n00:00:01,000 --> 00:00:02,000\nCaf\u00E9 \u00FCber\n";
            assertEquals("Caf\u00E9 \u00FCber", SubRipText.parse(TextFiles.decode(withBom(text, StandardCharsets.UTF_16LE,
                    new byte[]{(byte) 0xFF, (byte) 0xFE}))).getFirst().text());
            assertEquals("Caf\u00E9 \u00FCber", SubRipText.parse(TextFiles.decode(withBom(text, StandardCharsets.UTF_16BE,
                    new byte[]{(byte) 0xFE, (byte) 0xFF}))).getFirst().text());
            assertEquals("Caf\u00E9 \u00FCber", SubRipText.parse(TextFiles.decode(text.getBytes(Charset.forName("windows-1252"))))
                    .getFirst().text());
        }

        @Test
        @DisplayName("keeps unicode text, emoji and right-to-left scripts")
        void unicode() {
            var text = "\u3053\u3093\u306B\u3061\u306F \uD83D\uDE00\n\u05E9\u05DC\u05D5\u05DD";
            var cues = srt("1\n00:00:00,000 --> 00:00:01,000\n" + text + "\n");
            assertEquals(text, cues.getFirst().text());
        }

        @Test
        @DisplayName("formats cues with counters, rounded times, hours above 99 and no blank lines")
        void format() {
            assertEquals("3\n100:00:00,001 --> 100:00:01,000\na\nb\n\n",
                    SubRipText.format(3, SubtitleCue.of(360_000_000_000L + 600, 360_001_000_000L, "a\n\nb")));
            assertEquals("1\n00:00:00,000 --> 00:00:00,000\n\n", SubRipText.format(1, SubtitleCue.of(0, 0, "")));
        }

        @Test
        @DisplayName("returns no cue for text without timing lines and parses huge files")
        void edges() {
            assertTrue(srt("").isEmpty());
            assertTrue(srt("no timing here\n-->\n12:00 --> nonsense\n").isEmpty());
            var builder = new StringBuilder();
            for (var index = 0; index < 100_000; index++) {
                builder.append(SubRipText.format(index + 1, SubtitleCue.of(index * 1000L, index * 1000L + 900, "cue " + index)));
            }
            var cues = srt(builder.toString());
            assertEquals(100_000, cues.size());
            assertEquals("cue 99999", cues.getLast().text());
        }
    }

    @Nested
    @DisplayName("WebVTT")
    class WebVtt {
        @Test
        @DisplayName("parses the header blocks, identifiers, settings and payloads")
        void parse() {
            var document = WebVttText.parse(TextFiles.decode("""
                    WEBVTT - a title
                    Kind: captions

                    STYLE
                    ::cue { color: yellow }

                    NOTE a comment

                    intro
                    00:01.000 --> 00:02.500 align:start   line:10%
                    <v Anna>Hello &amp; welcome</v>

                    00:00:03.000 --> 00:00:04.000
                    two
                    lines

                    NOTE dropped after the first cue
                    """.getBytes(StandardCharsets.UTF_8)));
            assertEquals("WEBVTT - a title\nKind: captions\n\nSTYLE\n::cue { color: yellow }\n\nNOTE a comment", document.header());
            assertEquals(new SubtitleCue(1_000_000, 2_500_000, "<v Anna>Hello &amp; welcome</v>", "intro", "align:start line:10%"),
                    document.cues().get(0));
            assertEquals(SubtitleCue.of(3_000_000, 4_000_000, "two\nlines"), document.cues().get(1));
        }

        @Test
        @DisplayName("rejects text without the signature")
        void signature() {
            assertThrows(MediaException.Malformed.class, () -> WebVttText.parse("WEBVTTX\n"));
            assertThrows(MediaException.Malformed.class, () -> WebVttText.parse("1\n00:01.000 --> 00:02.000\nx\n"));
            assertTrue(WebVttText.isSignature("WEBVTT"));
            assertTrue(WebVttText.isSignature("WEBVTT\tdescription"));
        }

        @Test
        @DisplayName("formats cues and escapes arrows")
        void format() {
            assertEquals("id\n00:00:01.000 --> 00:00:02.000 align:end\na --&gt; b\n\n",
                    WebVttText.format(new SubtitleCue(1_000_000, 2_000_000, "a --> b", "id", "align:end")));
            assertEquals("WEBVTT", WebVttText.header(null));
            assertEquals("WEBVTT", WebVttText.header("garbage"));
            assertEquals("WEBVTT\n\nSTYLE\nx", WebVttText.header("WEBVTT\r\n\r\nSTYLE\r\nx\r\n\r\n"));
        }
    }

    @Nested
    @DisplayName("markup")
    class Markups {
        @ParameterizedTest(name = "{0} -> {1}: {2}")
        @CsvSource(delimiter = '|', value = {
                "SUBRIP | WEBVTT | <b>bold</b> & <i>it</i> <font color=\"red\">red</font> 1 < 2 | <b>bold</b> &amp; <i>it</i> <c.red>red</c> 1 &lt; 2",
                "WEBVTT | SUBRIP | <c.loud><b>Hi</b></c> &amp; <v Bob>bye</v> &lt;3 &#x263A; | <b>Hi</b> & bye <3 \u263A",
                "SUBRIP | ASS | <i>a</i> {\\an8}b | {\\an8}{\\i1}a{\\i0} b",
                "WEBVTT | WEBVTT | <v A>same</v> | <v A>same</v>"
        })
        @DisplayName("converts between formats")
        void convert(SubtitleMarkup from, SubtitleMarkup to, String text, String expected) {
            assertEquals(expected, Markup.convert(text, from, to));
        }

        @Test
        @DisplayName("reads ASS overrides, line breaks and hard spaces")
        void ass() {
            var b = (char) 92;
            var text = "{" + b + "b1" + b + "fs20}x{" + b + "b0}" + b + "Ny" + b + "hz{" + b + "u1}w";
            assertEquals("<font size=\"20\"><b>x</b>" + (char) 10 + "y" + (char) 0xA0 + "z<u>w</u></font>",
                    Markup.convert(text, SubtitleMarkup.ASS, SubtitleMarkup.SUBRIP));
        }

        @Test
        @DisplayName("nests and closes styles properly")
        void nesting() {
            assertEquals("<b>a</b><b><i>b</i></b><i>c</i>", Markup.convert("<b>a<i>b</b>c</i>", SubtitleMarkup.SUBRIP, SubtitleMarkup.WEBVTT));
            assertEquals("open", Markup.plain("<b>open", SubtitleMarkup.SUBRIP));
            assertEquals("x", Markup.plain("<b>x", SubtitleMarkup.WEBVTT));
            assertEquals("<b>x</b>", Markup.convert("<b>x", SubtitleMarkup.WEBVTT, SubtitleMarkup.SUBRIP));
        }

        @Test
        @DisplayName("maps SubRip placements to WebVTT settings")
        void placement() {
            assertEquals(8, Markup.subRipAlignment("{\\an8}top"));
            assertEquals(0, Markup.subRipAlignment("plain"));
            assertEquals("line:0", Markup.webVttSettings(8));
            assertEquals("line:0 align:left", Markup.webVttSettings(7));
            assertEquals("line:50% align:right", Markup.webVttSettings(6));
            assertEquals("", Markup.webVttSettings(2));
            assertThrows(IllegalArgumentException.class, () -> Markup.of(Codec.H264));
            assertEquals(SubtitleMarkup.SUBRIP, Markup.of(Codec.MOV_TEXT));
        }
    }

    @Nested
    @DisplayName("3GPP timed text")
    class TimedTexts {
        @Test
        @DisplayName("encodes style runs as styl records counted in code points and decodes them back")
        void styles() {
            var sample = TimedText.encodeText("\uD83D\uDE00 <b>bold</b> and <i><u>both</u></i>");
            var decoded = TimedText.decodeText(sample);
            assertEquals("\uD83D\uDE00 <b>bold</b> and <i><u>both</u></i>", decoded);
            var textLength = (sample[0] & 0xFF) << 8 | (sample[1] & 0xFF);
            assertEquals("\uD83D\uDE00 bold and both", new String(sample, 2, textLength, StandardCharsets.UTF_8));
            // styl box: size, type, two records, the first starting at code point 2
            var box = 2 + textLength;
            assertEquals("styl", new String(sample, box + 4, 4, StandardCharsets.US_ASCII));
            assertEquals(2, (sample[box + 8] & 0xFF) << 8 | (sample[box + 9] & 0xFF));
            assertEquals(2, (sample[box + 10] & 0xFF) << 8 | (sample[box + 11] & 0xFF));
        }

        @Test
        @DisplayName("treats empty samples as clearing the screen and merges simultaneous samples")
        void emptyAndMerge() {
            assertTrue(TimedText.isEmpty(TimedText.emptySample()));
            assertTrue(TimedText.isEmpty(TimedText.encodeText("")));
            assertFalse(TimedText.isEmpty(TimedText.encodeText("x")));
            assertEquals("", TimedText.decodeText(new byte[]{0}));
            var merged = TimedText.merge(List.of(TimedText.encodeText("<i>a</i>"), TimedText.emptySample(), TimedText.encodeText("b")));
            assertEquals("<i>a</i>\nb", TimedText.decodeText(merged));
        }

        @Test
        @DisplayName("reads UTF-16 samples and survives truncated boxes")
        void robustness() {
            var utf16 = "h\u00E9".getBytes(StandardCharsets.UTF_16);
            var sample = new byte[2 + utf16.length + 6];
            sample[1] = (byte) utf16.length;
            System.arraycopy(utf16, 0, sample, 2, utf16.length);
            sample[2 + utf16.length + 3] = 99;
            assertEquals("h\u00E9", TimedText.decodeText(sample));
            assertEquals("ab", TimedText.decodeText(new byte[]{0, 50, 'a', 'b'}));
            assertEquals(48, TimedText.defaultConfig().length);
        }
    }

    @Nested
    @DisplayName("ISO WebVTT samples and segmenting")
    class Samples {
        @Test
        @DisplayName("round-trips cues through vttc boxes and writes vtte for gaps")
        void boxes() {
            var cues = List.of(new WebVttSamples.Cue("id", "align:start", "one\ntwo"), new WebVttSamples.Cue("", "", "\u00E9"));
            assertEquals(cues, WebVttSamples.decode(WebVttSamples.encode(cues)));
            assertArrayEquals(new byte[]{0, 0, 0, 8, 'v', 't', 't', 'e'}, WebVttSamples.encode(List.of()));
            assertTrue(WebVttSamples.decode(WebVttSamples.encode(List.of())).isEmpty());
            assertTrue(WebVttSamples.decode(new byte[]{0, 0, 0, 99, 'v', 't', 't', 'c'}).isEmpty());
        }

        @Test
        @DisplayName("cuts overlapping cues into gapless segments starting at zero")
        void segments() {
            var segments = new ArrayList<String>();
            var segmenter = new CueSegmenter<String>((start, end, cues) -> segments.add(start + "-" + end + cues));
            segmenter.offer(1000, 5000, "a");
            segmenter.offer(2000, 3000, "b");
            segmenter.offer(2000, 2000, "empty");
            segmenter.offer(7000, 8000, "c");
            segmenter.offer(6000, 9000, "late");
            segmenter.finish();
            assertEquals(List.of("0-1000[]", "1000-2000[a]", "2000-3000[a, b]", "3000-5000[a]", "5000-7000[]", "7000-8000[c, late]",
                    "8000-9000[late]"), segments);
        }

        @Test
        @DisplayName("joins the pieces of split cues")
        void merger() {
            var out = new ArrayList<SubtitleCue>();
            var merger = new CueMerger(out::add);
            merger.offer(SubtitleCue.of(0, 1000, "a"));
            merger.offer(SubtitleCue.of(1000, 2000, "a"));
            merger.offer(SubtitleCue.of(2000, 3000, "b"));
            merger.offer(SubtitleCue.of(4000, 4000, "no duration"));
            merger.offer(SubtitleCue.of(6000, 7000, "c"));
            merger.finish();
            assertEquals(List.of(SubtitleCue.of(0, 2000, "a"), SubtitleCue.of(2000, 3000, "b"), SubtitleCue.of(4000, 6000, "no duration"),
                    SubtitleCue.of(6000, 7000, "c")), out);
        }
    }

    private static byte[] withBom(String text, Charset charset, byte[] bom) {
        var body = text.getBytes(charset);
        var result = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(body, 0, result, bom.length, body.length);
        return result;
    }
}
