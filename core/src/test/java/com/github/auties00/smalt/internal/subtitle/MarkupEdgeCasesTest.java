package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the markup the subtitle formats share in its less common forms: WebVTT cue settings in every
 * combination, SubRip font tag attributes and ASS override blocks inside SubRip, WebVTT timestamps, classes and
 * character references, every ASS override the parser reads, and the ASS writer's color, font, size and karaoke
 * changes.
 */
@DisplayName("subtitle markup edge cases")
class MarkupEdgeCasesTest {
    private static StyledText subRip(String text) {
        return Markup.parse(text, SubtitleMarkup.SUBRIP, Markup.Context.NONE);
    }

    private static StyledText webVtt(String text, long start) {
        return Markup.parse(text, SubtitleMarkup.WEBVTT, new Markup.Context(start, start + 10_000_000, null, ""));
    }

    private static StyledText ass(String text) {
        return Markup.parse(text, SubtitleMarkup.ASS, new Markup.Context(0, 10_000_000, null, ""));
    }

    private static StyledText.Run runOf(StyledText styled, String character) {
        for (var run : styled.runs()) {
            if (run.text().contains(character)) {
                return run;
            }
        }
        throw new AssertionError(character + " not in " + styled.runs());
    }

    @Nested
    @DisplayName("WebVTT cue settings")
    class Settings {
        @ParameterizedTest(name = "alignment {0}")
        @CsvSource(delimiter = '|', value = {"0|''", "2|''", "10|''", "-1|''", "1|align:left", "3|align:right",
                "4|line:50% align:left", "5|line:50%", "6|line:50% align:right", "7|line:0 align:left", "8|line:0",
                "9|line:0 align:right"})
        @DisplayName("place a numpad alignment")
        void alignments(int alignment, String settings) {
            assertEquals(settings, Markup.webVttSettings(alignment));
            if (!settings.isEmpty()) {
                var placed = Markup.withWebVttSettings(StyledText.EMPTY, settings);
                assertEquals(alignment, placed.alignment(), settings);
            }
        }

        @ParameterizedTest(name = "alignment {0}")
        @CsvSource({"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"})
        @DisplayName("anchor a positioned cue and read the anchor back")
        void positions(int alignment) {
            var styled = StyledText.EMPTY.withPlacement(alignment, 0.255, 0.75);
            var settings = Markup.webVttSettings(styled);
            var back = Markup.withWebVttSettings(StyledText.EMPTY, settings);
            assertEquals(alignment == 0 ? 2 : alignment, back.alignment(), settings);
            assertEquals(0.255, back.x(), 1e-9, settings);
            assertEquals(0.75, back.y(), 1e-9, settings);
        }

        @ParameterizedTest(name = "\"{0}\"")
        @CsvSource(delimiter = '|', value = {"align:middle|0", "align:center line:0|8", "line:-1|0", "line:3|8",
                "line:x|0", "line:20%|8", "line:50%|5", "line:90%|0", "line:120%|0", "line:abc%|0",
                "position:30% align:end|3", "nonsense :x line:|0", "align:bogus|0", "region:r vertical:lr|0"})
        @DisplayName("read alignments from lines, aligns and positions that anchor nothing")
        void reads(String settings, int alignment) {
            var placed = Markup.withWebVttSettings(StyledText.EMPTY, settings);
            assertEquals(alignment, placed.alignment(), settings);
            assertFalse(placed.hasPosition(), settings);
        }

        @Test
        @DisplayName("anchor a cue with a line and a position, defaulting the anchor to the align")
        void anchors() {
            var start = Markup.withWebVttSettings(StyledText.EMPTY, "line:10% position:20% align:left");
            assertEquals(7, start.alignment());
            assertEquals(0.2, start.x(), 1e-9);
            assertEquals(0.1, start.y(), 1e-9);
            var center = Markup.withWebVttSettings(StyledText.EMPTY, "line:10%,center position:20%,bogus");
            assertEquals(5, center.alignment());
            var noColumn = Markup.withWebVttSettings(StyledText.EMPTY, "line:10%,end position:20%");
            assertEquals(2, noColumn.alignment());
        }

        @Test
        @DisplayName("leave text that has its own placement alone")
        void keepsOwn() {
            var own = StyledText.EMPTY.withPlacement(9, Double.NaN, Double.NaN);
            assertSame(own, Markup.withWebVttSettings(own, "line:0 align:left"));
            var anchored = StyledText.EMPTY.withPlacement(0, 0.5, 0.5);
            assertSame(anchored, Markup.withWebVttSettings(anchored, "line:0"));
        }
    }

    @Nested
    @DisplayName("SubRip")
    class SubRip {
        @Test
        @DisplayName("reads font attributes quoted, unquoted, unterminated, empty and out of range")
        void fontAttributes() {
            var styled = subRip("<font color=red size=20 face='Mono'>a</font>"
                    + "<font  color = \"#00ff00\"  size=\"x\">b</font>"
                    + "<font color=\"nope\" size=\"0\" checked face=\"Sans>c</font>"
                    + "<font size=99999 =lonely>d</font></font>e");
            var runs = styled.runs();
            assertEquals(new StyledText.Run("a", 0, 0xFFFF0000L, StyledText.NO_COLOR, "Mono", 20, -1), runs.get(0));
            assertEquals(0xFF00FF00L, runs.get(1).color());
            assertTrue(Double.isNaN(runs.get(1).size()));
            assertEquals("b", runs.get(1).text());
            assertEquals("de", runs.getLast().text(), "an invalid size and a nameless attribute change nothing");
            assertEquals(StyledText.NO_COLOR, runs.getLast().color());
            assertEquals("Sans", runs.get(2).font(), "an unterminated quote runs to the end of the tag");
        }

        @Test
        @DisplayName("applies ASS override blocks and keeps braces that are not one")
        void overrides() {
            var styled = subRip("{\\an8}{\\b1}bold{\\b0} {not} {\\i1 x");
            assertEquals(8, styled.alignment());
            assertEquals(StyledText.BOLD, styled.runs().getFirst().style());
            assertTrue(styled.plain().endsWith("{not} {\\i1 x"), styled.plain());
            assertEquals(5, Markup.subRipAlignment("{\\an5}x"));
            assertEquals(0, Markup.subRipAlignment("<b unclosed"));
        }
    }

    @Nested
    @DisplayName("WebVTT")
    class WebVtt {
        @Test
        @DisplayName("reads timestamps inside the cue as karaoke and ignores those before it")
        void timestamps() {
            var styled = webVtt("a<00:00:05.000>b<00:00:01.000>c<99:99>d<00:00:06.000", 4_000_000);
            var karaoke = styled.runs().stream().mapToLong(StyledText.Run::karaoke).toArray();
            assertTrue(styled.hasKaraoke());
            assertEquals(1_000_000, karaoke[1]);
            assertEquals("abcd", styled.plain());
            var outside = webVtt("a<00:00:05.000>b", -1);
            assertFalse(outside.hasKaraoke());
        }

        @Test
        @DisplayName("reads classes, voices and character references, and drops strikeout tags")
        void tags() {
            var styled = webVtt("<c.red.bg_blue.unknown>r</c><c>plain</c></c><v>x</v><v Speaker  >y</v>"
                    + "<s>z</s>&amp;&lt;&gt;&nbsp;&lrm;&rlm;&quot;&apos;&#65;&#x42;&#X43;&#0;&#xZZ;&bogus;&;&verylongname;", 0);
            assertEquals(0xFFFF0000L, styled.runs().getFirst().color());
            assertEquals(0xFF0000FFL, styled.runs().getFirst().background());
            assertEquals("Speaker", styled.voice());
            for (var run : styled.runs()) {
                assertEquals(0, run.style() & StyledText.STRIKEOUT, run.toString());
            }
            assertTrue(styled.plain().endsWith("&<>\u00A0\u200E\u200F\"'ABC&#0;&#xZZ;&bogus;&;&verylongname;"),
                    styled.plain());
        }
    }

    @Nested
    @DisplayName("ASS overrides")
    class Overrides {
        @Test
        @DisplayName("set and reset the style bits by number, weight and style default")
        void bits() {
            var styled = ass("{\\b700}a{\\b400}b{\\b}c{\\i1\\u1\\s1}d{\\i\\u\\s}e{\\bx}f{\\b1234567}g");
            assertEquals(StyledText.BOLD, runOf(styled, "a").style());
            assertEquals(0, runOf(styled, "b").style());
            assertEquals(0, runOf(styled, "c").style(), "an empty argument takes the style's value");
            assertEquals(StyledText.ITALIC | StyledText.UNDERLINE | StyledText.STRIKEOUT, runOf(styled, "d").style());
            assertEquals(0, runOf(styled, "e").style());
            assertEquals(0, runOf(styled, "f").style(), "an argument that is not a number is ignored");
            assertEquals(0, runOf(styled, "g").style(), "a number longer than six digits is zero");
        }

        @Test
        @DisplayName("set colors and alpha, keeping the color's alpha")
        void colors() {
            var styled = ass("{\\1a&H80&}a{\\c&H0000FF&}b{\\alpha&HFF&\\1c&HFF0000&}c{\\c}d{\\c&Hzz&}e{\\1a&Hzz&}f");
            var runs = styled.runs();
            assertEquals(0x7FFFFFFFL, runs.get(0).color());
            assertEquals(0x7FFF0000L, runs.get(1).color());
            assertEquals(0x000000FFL, runs.get(2).color());
            assertEquals(StyledText.NO_COLOR, runs.get(3).color());
        }

        @Test
        @DisplayName("set fonts and sizes, absolute and relative")
        void fontsAndSizes() {
            var styled = ass("{\\fnArial\\fs36}a{\\fs+2}b{\\fs-20}c{\\fs0}d{\\fs}e{\\fs-1}f{\\fs999999}g{\\fn}h");
            var runs = styled.runs();
            assertEquals("Arial", runs.get(0).font());
            assertEquals(36, runs.get(0).size(), 1e-9);
            assertEquals(43.2, runs.get(1).size(), 1e-9);
            assertTrue(Double.isNaN(runs.get(2).size()), "a relative size below zero resets");
            assertEquals("", runs.getLast().font());
        }

        @Test
        @DisplayName("place with an, legacy a, pos and move, and ignore positions that are not numbers")
        void placement() {
            assertEquals(9, ass("{\\an9}x").alignment());
            assertEquals(AssScript.fromLegacyAlignment(6), ass("{\\a6}x").alignment());
            assertEquals(0, ass("{\\a12}x").alignment());
            var pos = ass("{\\pos(192,144)}x");
            assertEquals(0.5, pos.x(), 1e-9);
            assertEquals(0.5, pos.y(), 1e-9);
            var move = ass("{\\move(96,72,0,0)}x");
            assertEquals(0.25, move.x(), 1e-9);
            assertFalse(ass("{\\pos(a,b)}x").hasPosition());
            assertFalse(ass("{\\pos(5)}x").hasPosition());
        }

        @Test
        @DisplayName("time karaoke syllables and reset with r, ignoring unknown and nested tags")
        void karaokeAndReset() {
            var styled = ass("{\\k50}a{\\K25}b{\\kf-3}c{\\ko10}d{\\b1\\r}e{\\t(\\b1)\\clip(0,0,1,1)\\zz}f\\N\\n\\h\\{g\\}");
            assertEquals(0, runOf(styled, "a").karaoke());
            assertEquals(500_000, runOf(styled, "b").karaoke());
            assertEquals(750_000, runOf(styled, "c").karaoke());
            assertEquals(750_000, runOf(styled, "d").karaoke(), "a negative duration adds no time");
            assertEquals(0, runOf(styled, "e").style(), "r resets to the style");
            assertTrue(styled.plain().endsWith("f\n \u00A0{g}"), styled.plain());
        }
    }

    @Nested
    @DisplayName("ASS writer")
    class Writer {
        @Test
        @DisplayName("writes color, alpha, font and size changes and their resets, and reads them back")
        void roundTrip() {
            var context = new Markup.Context(0, 4_000_000, null, "");
            var styled = new StyledText(List.of(
                    new StyledText.Run("a", StyledText.BOLD, 0xFFFF0000L, StyledText.NO_COLOR, "Arial", 30, -1),
                    new StyledText.Run("b", 0, 0x80FF0000L, StyledText.NO_COLOR, "Arial", 30, -1),
                    new StyledText.Run("c", 0, 0x8000FF00L, StyledText.NO_COLOR, "", Double.NaN, -1),
                    new StyledText.Run("d", StyledText.ITALIC, StyledText.NO_COLOR, StyledText.NO_COLOR, "Mono", 13,
                            -1)), 3, 0.5, 0.25, "");
            var text = Markup.write(styled, SubtitleMarkup.ASS, context);
            var back = Markup.parse(text, SubtitleMarkup.ASS, context);
            assertEquals(styled.runs(), back.runs(), text);
            assertEquals(3, back.alignment(), text);
            assertEquals(0.5, back.x(), 1e-9, text);
            var fractional = styled.withRuns(List.of(new StyledText.Run("x", 0, StyledText.NO_COLOR, StyledText.NO_COLOR,
                    "", 12.5, -1)));
            assertTrue(Markup.write(fractional, SubtitleMarkup.ASS, context).contains("\\fs13"),
                    "ASS font sizes are whole script pixels");
        }

        @Test
        @DisplayName("writes karaoke syllables relative to the cue")
        void karaoke() {
            var context = new Markup.Context(0, 4_000_000, null, "");
            var styled = new StyledText(List.of(
                    new StyledText.Run("one ", 0, StyledText.NO_COLOR, StyledText.NO_COLOR, "", Double.NaN, 0),
                    new StyledText.Run("two", 0, StyledText.NO_COLOR, StyledText.NO_COLOR, "", Double.NaN, 1_250_000)),
                    0, Double.NaN, Double.NaN, "");
            var text = Markup.write(styled, SubtitleMarkup.ASS, context);
            assertTrue(text.contains("\\k"), text);
            var back = Markup.parse(text, SubtitleMarkup.ASS, context);
            assertEquals(1_250_000, back.runs().get(1).karaoke(), text);
            var webVtt = Markup.write(styled, SubtitleMarkup.WEBVTT, new Markup.Context(10_000_000, 14_000_000, null, ""));
            assertTrue(webVtt.contains("<00:00:11.250>"), webVtt);
        }
    }
}
