package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the SAMI, LRC and MicroDVD parsers and writers on the irregular files players accept.
 */
@DisplayName("SAMI, LRC and MicroDVD")
class LegacyTextFormatsTest {
    @Nested
    @DisplayName("SAMI")
    class Sami {
        private static final String FILE = """
                <SAMI>
                <HEAD>
                <TITLE>Demo title</TITLE>
                <STYLE TYPE="text/css">
                <!--
                P { margin-left: 2pt; text-align: center; }
                .ENCC { Name: English; lang: en-US; SAMIType: CC; }
                .KRCC { Name: Korean; lang: ko-KR; SAMIType: CC; }
                -->
                </STYLE>
                </HEAD>
                <BODY>
                <SYNC Start=1000><P Class=ENCC>Hello <b>bold</b><br>second   line
                <P Class=KRCC>\uC548\uB155
                <SYNC Start=2500><P Class=ENCC>&nbsp;
                <P Class=KRCC><font color="#ff0000" face="Gulim">\uBE68\uAC15</font>
                <sync start="4000"><p class="encc">late &amp; <i>last</i></p>
                <SYNC Start=3000><P Class=ENCC>out of order
                </BODY>
                </SAMI>
                """;

        @Test
        @DisplayName("reads one track per language class with SubRip markup, blanks ending cues")
        void parse() {
            assertTrue(SamiText.isSignature("  \n<sami>"));
            assertFalse(SamiText.isSignature("<samiX>"));
            var parsed = SamiText.parse(FILE);
            assertEquals("Demo title", parsed.title());
            assertEquals(2, parsed.tracks().size());
            var english = parsed.tracks().getFirst();
            assertEquals("ENCC", english.name());
            assertEquals("en-US", english.language());
            assertEquals("English", english.title());
            assertEquals(List.of(SubtitleCue.of(1_000_000, 2_500_000, "Hello <b>bold</b>\nsecond line"),
                    SubtitleCue.of(3_000_000, 4_000_000, "out of order"),
                    SubtitleCue.of(4_000_000, 8_000_000, "late & <i>last</i>")), english.cues());
            var korean = parsed.tracks().get(1);
            assertEquals("ko-KR", korean.language());
            assertEquals(List.of(SubtitleCue.of(1_000_000, 2_500_000, "\uC548\uB155"),
                    SubtitleCue.of(2_500_000, 3_000_000, "<font color=\"#ff0000\" face=\"Gulim\">\uBE68\uAC15</font>")),
                    korean.cues());
        }

        @Test
        @DisplayName("finds tags after characters whose lowercase form is longer, such as a dotted capital I")
        void caseMappingLength() {
            // "\u0130" lowercases to two characters, which must not shift the tag positions found in the lowercase copy
            var text = "<SAMI><HEAD><TITLE>\u0130\u0130\u0130\u0130</TITLE></HEAD><BODY>"
                    + "<SYNC Start=1000><P Class=TRCC>\u0130stanbul \u0130zmir<P Class=ENCC>Istanbul"
                    + "<SYNC Start=2000><P Class=TRCC>&nbsp;<P Class=ENCC>&nbsp;</BODY></SAMI>";
            var parsed = SamiText.parse(text);
            assertEquals("\u0130\u0130\u0130\u0130", parsed.title());
            assertEquals(List.of(SubtitleCue.of(1_000_000, 2_000_000, "\u0130stanbul \u0130zmir")),
                    parsed.tracks().getFirst().cues());
            assertEquals(List.of(SubtitleCue.of(1_000_000, 2_000_000, "Istanbul")), parsed.tracks().get(1).cues());
        }

        @Test
        @DisplayName("writes classes and SYNC elements that read back to the same cues")
        void roundTrip() {
            var cues = List.of(SubtitleCue.of(1_000_000, 2_000_000, "<i>one</i> & <two>"),
                    SubtitleCue.of(1_500_000, 3_000_000, "<font color=\"#00ff00\">over</font>\nlap"),
                    SubtitleCue.of(5_000_000, 6_000_000, "a  b"));
            var text = SamiText.write(List.of(new SamiText.Track("ENCC", "en-US", "English", cues)));
            assertTrue(text.contains("charset=utf-8"));
            var back = SamiText.parse(text).tracks().getFirst().cues();
            assertEquals(List.of(SubtitleCue.of(1_000_000, 1_500_000, "<i>one</i> & <two>"),
                    SubtitleCue.of(1_500_000, 2_000_000, "<i>one</i> & <two>\n<font color=\"#00ff00\">over</font>\nlap"),
                    SubtitleCue.of(2_000_000, 3_000_000, "<font color=\"#00ff00\">over</font>\nlap"),
                    SubtitleCue.of(5_000_000, 6_000_000, "a \u00A0b")), back);
        }

        @Test
        @DisplayName("finds the charset a META tag declares")
        void charset() {
            var bytes = "<SAMI><HEAD><META http-equiv=\"Content-Type\" content=\"text/html; charset=EUC-KR\">"
                    .getBytes(StandardCharsets.US_ASCII);
            assertEquals("EUC-KR", SamiText.declaredCharset(bytes).name());
            assertNull(SamiText.declaredCharset("<SAMI>".getBytes(StandardCharsets.US_ASCII)));
            assertNull(SamiText.declaredCharset("charset=no-such-charset".getBytes(StandardCharsets.US_ASCII)));
        }
    }

    @Nested
    @DisplayName("LRC")
    class Lrc {
        @Test
        @DisplayName("reads time tags, repeated lines, word times, offsets and ID tags")
        void parse() {
            var text = """
                    [ti:Song]
                    [ar:Singer]
                    [al:Album]
                    [by:someone]
                    [offset:+500]
                    [length:00:20.00]
                    [00:01.00]first line
                    [00:03.50][00:10.00]chorus
                    [00:03.50]second voice
                    [00:05.00]
                    [00:07.00]<00:07.00>word <00:07.80>by <00:08.20>word & more
                    not a tag line
                    [00:12.5]short fraction
                    """;
            assertTrue(LrcText.isSignature(text));
            var parsed = LrcText.parse(text);
            assertEquals(Map.of("title", "Song", "artist", "Singer", "album", "Album", "by", "someone", "offset", "+500",
                    "length", "00:20.00"), parsed.tags());
            assertEquals(List.of(
                    SubtitleCue.of(500_000, 3_000_000, "first line"),
                    SubtitleCue.of(3_000_000, 4_500_000, "chorus\nsecond voice"),
                    SubtitleCue.of(6_500_000, 9_500_000, "<00:00:06.500>word <00:00:07.300>by <00:00:07.700>word &amp; more"),
                    SubtitleCue.of(9_500_000, 12_000_000, "chorus"),
                    SubtitleCue.of(12_000_000, 19_500_000, "short fraction")), parsed.cues());
            // offsets beyond any time saturate instead of overflowing the shift in microseconds
            assertEquals(List.of(SubtitleCue.of(0, 0, "x")),
                    LrcText.parse("[offset:+9223372036854775807]\n[00:01.00]x\n").cues());
            assertEquals(List.of(SubtitleCue.of(1_000_000_001_000_000L, 1_000_000_006_000_000L, "x")),
                    LrcText.parse("[offset:-9223372036854775807]\n[00:01.00]x\n").cues());
        }

        @Test
        @DisplayName("writes lines at the cue starts, clearing lines and word times")
        void write() {
            var cues = List.of(SubtitleCue.of(1_000_000, 2_000_000, "one\ntwo"),
                    SubtitleCue.of(2_000_000, 3_000_000, "a <00:00:02.500>b &amp; c"),
                    SubtitleCue.of(65_000_000, 66_000_000, "<b>late</b>"));
            var tags = new LinkedHashMap<String, String>();
            tags.put("ti", "T");
            var text = LrcText.write(tags, cues);
            assertEquals("""
                    [ti:T]
                    [00:01.00]one
                    [00:01.00]two
                    [00:02.00]a <00:02.50>b & c
                    [00:03.00]
                    [01:05.00]late
                    [01:06.00]
                    """, text);
            assertEquals(List.of(SubtitleCue.of(1_000_000, 2_000_000, "one\ntwo"),
                    SubtitleCue.of(2_000_000, 3_000_000, "a <00:00:02.500>b &amp; c"),
                    SubtitleCue.of(65_000_000, 66_000_000, "late")), LrcText.parse(text).cues());
        }

        @Test
        @DisplayName("rejects text that does not start with an LRC tag")
        void signature() {
            assertFalse(LrcText.isSignature("hello\n[00:01.00]x"));
            assertFalse(LrcText.isSignature("[Script Info]\n"));
            assertTrue(LrcText.isSignature("\n[ar:x]\n[00:01]y"));
            assertEquals(-1, LrcText.time("1:60"));
            assertEquals(61_250_000, LrcText.time("01:01.25"));
            assertEquals(61_250_000, LrcText.time("01:01:25"));
        }
    }

    @Nested
    @DisplayName("MicroDVD")
    class MicroDvd {
        @Test
        @DisplayName("reads frame times, the declared rate, control codes and open ends")
        void parse() {
            var text = """
                    {1}{1}25
                    {50}{100}Hello|{y:i}world
                    {125}{}{Y:b}{C:$0000FF}All bold|red{y:u} lines
                    {150}{175}{f:Courier}{s:30}x{P:1}
                    """;
            assertTrue(MicroDvdText.isSignature(text));
            var parsed = MicroDvdText.parse(text, null);
            assertEquals(new Rational(25, 1), parsed.frameRate());
            assertEquals(List.of(
                    SubtitleCue.of(2_000_000, 4_000_000, "Hello\n<i>world</i>"),
                    SubtitleCue.of(5_000_000, 6_000_000, "<font color=\"#ff0000\"><b>All bold</b></font>\n"
                            + "<font color=\"#ff0000\"><b>red{y:u} lines</b></font>"),
                    SubtitleCue.of(6_000_000, 7_000_000, "<font face=\"Courier\" size=\"30\">x{P:1}</font>")), parsed.cues());
            var forced = MicroDvdText.parse(text, new Rational(50, 1));
            assertEquals(1_000_000, forced.cues().getFirst().startMicros());
        }

        @Test
        @DisplayName("reads the NTSC rates and defaults to 24000/1001")
        void rates() {
            assertEquals(new Rational(24000, 1001), MicroDvdText.rate("23.976"));
            assertEquals(new Rational(30000, 1001), MicroDvdText.rate("29.97"));
            assertEquals(new Rational(60000, 1001), MicroDvdText.rate("59.94"));
            assertEquals(new Rational(25, 1), MicroDvdText.rate("25.000"));
            assertEquals(new Rational(239, 10), MicroDvdText.rate("23.9"));
            assertNull(MicroDvdText.rate("fast"));
            assertNull(MicroDvdText.rate("-5"));
            // exponents too small for a frame rate must not reach the exact decimal conversion
            assertNull(MicroDvdText.rate("1E-900"));
            assertNull(MicroDvdText.rate("0"));
            assertEquals(List.of(SubtitleCue.of(0, 2_002_000, "x"), SubtitleCue.of(41_708, 41_708, "1E-900")),
                    MicroDvdText.parse("{1}{1}1E-900\n{0}{48}x\n", null).cues());
            var parsed = MicroDvdText.parse("{24}{48}x\n", null);
            assertNull(parsed.frameRate());
            assertEquals(1_001_000, parsed.cues().getFirst().startMicros());
            assertEquals("23.976", MicroDvdText.rateText(new Rational(24000, 1001)));
        }

        @Test
        @DisplayName("writes the rate cue and per-line codes that read back to the same cues")
        void roundTrip() {
            var rate = new Rational(25, 1);
            var cues = List.of(SubtitleCue.of(2_000_000, 4_000_000, "<i>it</i>\nplain"),
                    SubtitleCue.of(5_000_000, 6_000_000, "<font color=\"#00ff00\"><b>green</b></font>"),
                    SubtitleCue.of(7_000_000, 8_000_000, "<b>mixed</b> part"));
            var text = MicroDvdText.write(cues, rate);
            assertEquals("{1}{1}25.000\n{50}{100}{y:i}it|plain\n{125}{150}{y:b}{c:$00FF00}green\n{175}{200}mixed part\n",
                    text);
            var back = MicroDvdText.parse(text, null).cues();
            assertEquals(List.of(cues.get(0), cues.get(1), SubtitleCue.of(7_000_000, 8_000_000, "mixed part")), back);
        }

        @Test
        @DisplayName("counts frames at the rate the file declares, and rejects rates a file cannot declare")
        void declaredRate() {
            // 25/3 is declared as 8.333, so frames count at 8333/1000 for a reader to find them where they were
            var cue = SubtitleCue.of(3_600_000_000L, 3_601_000_000L, "late");
            var text = MicroDvdText.write(List.of(cue), new Rational(25, 3));
            assertTrue(text.startsWith("{1}{1}8.333\n{29999}{30007}"), text);
            var back = MicroDvdText.parse(text, null);
            assertEquals(new Rational(8333, 1000), back.frameRate());
            assertEquals(3_600_000_000L, back.cues().getFirst().startMicros(), 120_000);
            assertEquals(new Rational(24000, 1001), MicroDvdText.declarable(new Rational(24000, 1001)));
            assertNull(MicroDvdText.declarable(new Rational(2001, 2)));
            assertNull(MicroDvdText.declarable(new Rational(1, 10_000)));
            assertThrows(IllegalArgumentException.class, () -> MicroDvdText.write(List.of(cue), new Rational(5000, 1)));
        }
    }
}
