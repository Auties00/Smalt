package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers MicroDVD frame fields, rate cues and control codes, LRC time and ID tags in their malformed forms, and the
 * byte order marks and legacy encodings text subtitle files are read in.
 */
@DisplayName("legacy text subtitle edge cases")
class LegacyTextEdgeCasesTest {
    @Nested
    @DisplayName("MicroDVD")
    class MicroDvd {
        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"{1}", "x{1}{2}", "{}{2}text", "{1}x{2}", "{12345678901234567}{2}", "{1a}{2}",
                "{1}{2a}", "{1", "{1}{"})
        @DisplayName("refuses lines whose frame fields are not two numbers")
        void notFrames(String line) {
            assertFalse(MicroDvdText.isSignature("\n" + line + "\n"));
            assertTrue(MicroDvdText.parse(line, null).cues().isEmpty());
        }

        @Test
        @DisplayName("reads an empty end frame as lasting until the next cue, or four seconds for the last")
        void emptyEnd() {
            var parsed = MicroDvdText.parse("{1}{1}25\n{25}{}a\n{50}{75}b\n{100}{}c\n\n{125}{150}   \n", null);
            assertEquals(new Rational(25, 1), parsed.frameRate());
            assertEquals(List.of(1_000_000L, 2_000_000L, 4_000_000L), parsed.cues().stream()
                    .map(SubtitleCue::startMicros).toList());
            assertEquals(2_000_000L, parsed.cues().get(0).endMicros());
            assertEquals(5_000_000L, parsed.cues().get(2).endMicros(), "a blank cue still ends the one before it");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"23.976,24000,1001", "23.98,24000,1001", "29.97,30000,1001", "59.94,60000,1001",
                "47.952,48000,1001", "119.88,120000,1001", "25,25,1", "12.5,25,2", "0.001,1,1000"})
        @DisplayName("reads declared rates, the NTSC ones as fractions")
        void rates(String text, long numerator, long denominator) {
            assertEquals(new Rational(numerator, denominator), MicroDvdText.rate(text));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "0", "1000.5", "abc", "12345678901234567", "-5", "0.0001"})
        @DisplayName("refuses rates that are not numbers from 0.001 to 1000")
        void badRates(String text) {
            assertNull(MicroDvdText.rate(text));
        }

        @Test
        @DisplayName("takes a first cue that is not a rate as a cue, and a forced rate over the declared one")
        void rateCue() {
            var notRate = MicroDvdText.parse("{0}{1}hello\n{30}{60}b\n", null);
            assertNull(notRate.frameRate());
            assertEquals("hello", notRate.cues().getFirst().text());
            var forced = MicroDvdText.parse("{1}{1}25\n{50}{100}x\n", new Rational(50, 1));
            assertEquals(1_000_000L, forced.cues().getFirst().startMicros());
        }

        @Test
        @DisplayName("turns control codes into SubRip tags, line-local and global")
        void controlCodes() {
            assertEquals("<font color=\"#0000ff\" face=\"Arial\" size=\"20\"><b><i>a</i></b></font>",
                    MicroDvdText.subRip("{c:$FF0000}{f:Arial}{s:20}{y:b,i}a"));
            assertEquals("<b>a</b>\n<b>b</b>", MicroDvdText.subRip("{Y:b}a|b"));
            assertEquals("<i>a</i>\nb", MicroDvdText.subRip("{y:i}a|b"));
            assertEquals("a", MicroDvdText.subRip("{x:1}a"), "an unknown control code is dropped");
            assertEquals("{bad}a", MicroDvdText.subRip("{bad}a"));
            assertEquals("{y:b", MicroDvdText.subRip("{y:b"));
            assertEquals("a", MicroDvdText.subRip("{c:$zz}{f:}{s:big}{s:123456}a"));
            assertEquals("<u><s>a</s></u>", MicroDvdText.subRip("{y:s,u,x}a"));
        }

        @Test
        @DisplayName("writes the styles, color, font and size a whole line shares")
        void writes() {
            var cues = List.of(
                    SubtitleCue.of(0, 1_000_000, "<font color=\"#FF0000\" face=\"Mono\" size=\"18\"><b>a</b> <b>b</b></font>"),
                    SubtitleCue.of(1_000_000, 2_000_000, "<b>x</b><i>y</i>|z"),
                    SubtitleCue.of(2_000_000, 3_000_000, "<font color=\"#FF0000\">p</font><font color=\"#00FF00\">q</font>"
                            + "<font face=\"A\">r</font><font size=\"9\">s</font>"));
            var text = MicroDvdText.write(cues, new Rational(25, 1));
            assertTrue(text.contains("{y:b}{c:$0000FF}{f:Mono}{s:18}a b"), text);
            assertTrue(text.contains("xy/z"), text);
            var back = MicroDvdText.parse(text, null);
            assertEquals(3, back.cues().size());
        }
    }

    @Nested
    @DisplayName("LRC")
    class Lrc {
        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", ":10", "1234567890:10", "a:10", "1:", "1:100", "1:6x", "1:60", "1:10.", "1:10.1234567",
                "1:10.x"})
        @DisplayName("refuses times that are not mm:ss with an optional fraction")
        void badTimes(String time) {
            assertEquals(-1, LrcText.time(time));
        }

        @ParameterizedTest(name = "[{0}]")
        @CsvSource({"1:10,70000000", "01:10.5,70500000", "1:10:25,70250000", "100:00.123456,6000123456"})
        @DisplayName("reads mm:ss times with a fraction after a dot or a colon")
        void times(String time, long micros) {
            assertEquals(micros, LrcText.time(time));
        }

        @Test
        @DisplayName("reads ID tags, the offset and the length, and skips lines that are not tags")
        void tags() {
            var parsed = LrcText.parse("[ti:Song]\n[ar:Artist]\n[al:Album]\n[by:me]\n[offset:+500]\n[offset:x]\n"
                    + "[length: 03:20]\n[length:bad]\n[1x:skip]\n[:empty]\n[no colon]\nplain line\n"
                    + "[00:01.00][00:03.00]twice <00:01.50>word\n[00:02.00]\n[00:04.00]last\n");
            assertEquals("Song", parsed.tags().get("title"));
            assertEquals("Artist", parsed.tags().get("artist"));
            assertEquals("Album", parsed.tags().get("album"));
            assertEquals("me", parsed.tags().get("by"));
            assertEquals(3, parsed.cues().size(), parsed.cues().toString());
            assertEquals(500_000L, parsed.cues().getFirst().startMicros(), "the offset shifts every time");
        }

        @Test
        @DisplayName("recognizes a file by its first lines")
        void signatures() {
            assertTrue(LrcText.isSignature("\n[ti:x]\n[00:01.00]a"));
            assertFalse(LrcText.isSignature("text\n[00:01.00]a"));
            assertFalse(LrcText.isSignature("[ti:x]\nnot a tag"));
            assertFalse(LrcText.isSignature("[ti:x]\n[ar:y]"));
            assertFalse(LrcText.isSignature(""));
        }

        @Test
        @DisplayName("writes word times, and an empty line where a cue ends before the next")
        void writes() {
            var tags = new LinkedHashMap<String, String>();
            tags.put("ti", "a]b\nc");
            var cues = List.of(SubtitleCue.of(1_000_000, 2_000_000, "one <00:00:01.500>two"),
                    SubtitleCue.of(5_000_000, 6_000_000, "three\n\nfour"));
            var text = LrcText.write(tags, cues);
            assertTrue(text.startsWith("[ti:a)b c]\n"), text);
            assertTrue(text.contains("[00:01.00]one <00:01.50>two"), text);
            assertTrue(text.contains("[00:02.00]\n"), text);
            assertTrue(text.contains("[00:05.00]four"), text);
        }
    }

    @Nested
    @DisplayName("text encodings")
    class Encodings {
        @Test
        @DisplayName("reads every byte order mark, UTF-32 only on a whole number of units")
        void byteOrderMarks() {
            assertEquals(StandardCharsets.UTF_8, TextFiles.byteOrderMark(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}));
            assertEquals(Charset.forName("UTF-32BE"), TextFiles.byteOrderMark(new byte[]{0, 0, (byte) 0xFE, (byte) 0xFF}));
            assertEquals(Charset.forName("UTF-32LE"), TextFiles.byteOrderMark(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0}));
            assertEquals(StandardCharsets.UTF_16LE, TextFiles.byteOrderMark(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0, 0}));
            assertEquals(StandardCharsets.UTF_16LE, TextFiles.byteOrderMark(new byte[]{(byte) 0xFF, (byte) 0xFE, 'a', 0}));
            assertEquals(StandardCharsets.UTF_16BE, TextFiles.byteOrderMark(new byte[]{(byte) 0xFE, (byte) 0xFF}));
            assertNull(TextFiles.byteOrderMark(new byte[]{0, 0, (byte) 0xFE}));
            assertNull(TextFiles.byteOrderMark(new byte[]{(byte) 0xEF, (byte) 0xBB}));
            assertNull(TextFiles.byteOrderMark(new byte[]{(byte) 0xEF, (byte) 0xBB, 0}));
            assertNull(TextFiles.byteOrderMark(new byte[]{(byte) 0xFE}));
        }

        @Test
        @DisplayName("detects legacy encodings of Japanese, Korean, Chinese and Cyrillic text")
        void legacy() {
            var japanese = "\u3053\u3093\u306B\u3061\u306F\u4E16\u754C".repeat(20);
            assertEquals(japanese, TextFiles.decode(japanese.getBytes(Charset.forName("Shift_JIS"))));
            var korean = "\uC548\uB155\uD558\uC138\uC694 ".repeat(20);
            assertEquals(korean, TextFiles.decode(korean.getBytes(Charset.forName("EUC-KR"))));
            var simplified = "\u6211\u4EEC\u662F\u4E2D\u56FD\u4EBA\u7684\u4E00\u4E2A\u5927".repeat(20);
            assertEquals(simplified, TextFiles.decode(simplified.getBytes(Charset.forName("GBK"))));
            var traditional = "\u6211\u5011\u662F\u4E2D\u570B\u4EBA\u7684\u4E00\u500B\u5927".repeat(20);
            assertEquals(traditional, TextFiles.decode(traditional.getBytes(Charset.forName("Big5"))));
            var russian = "\u043F\u0440\u0438\u0432\u0435\u0442 \u043C\u0438\u0440 ".repeat(20);
            assertEquals(russian, TextFiles.decode(russian.getBytes(Charset.forName("windows-1251"))));
            var long1251 = ("\u043F\u0440\u0438\u0432\u0435\u0442 ".repeat(10_000)).getBytes(Charset.forName("windows-1251"));
            assertTrue(TextFiles.decode(long1251).startsWith("\u043F\u0440\u0438\u0432\u0435\u0442"));
            var cut = ("a".repeat(65_535) + "\u00E9\u00E9").getBytes(StandardCharsets.UTF_8);
            assertEquals(StandardCharsets.UTF_8, TextFiles.detect(cut));
        }
    }
}
