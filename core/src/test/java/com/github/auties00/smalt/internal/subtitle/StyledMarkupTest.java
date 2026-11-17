package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the styled-run conversions between SubRip, WebVTT and ASS markup: colors, fonts, sizes, strikeout,
 * placements, karaoke times and speakers, and the resolution of ASS styles from the script header.
 */
@DisplayName("Styled subtitle markup")
class StyledMarkupTest {
    private static final char BS = (char) 92;

    /** Replaces every {@code ~} with a backslash, so that ASS overrides read as they are written. */
    private static String ass(String text) {
        return text.replace('~', BS);
    }

    private static SubtitleCue convert(SubtitleCue cue, SubtitleMarkup from, SubtitleMarkup to) {
        return Subtitles.convert(cue, from, to);
    }

    @Nested
    @DisplayName("text conversions")
    class Texts {
        @ParameterizedTest(name = "{0} -> {1}: {2}")
        @CsvSource(delimiter = '|', value = {
                "SUBRIP | WEBVTT | <b>bold</b> & <i>it</i> <font color=\"red\">red</font> 1 < 2 | <b>bold</b> &amp; <i>it</i> <c.red>red</c> 1 &lt; 2",
                "SUBRIP | ASS | <i>a</i> {~an8}b | {~an8}{~i1}a{~i0} b",
                "SUBRIP | ASS | <s>gone</s> kept | {~s1}gone{~s0} kept",
                "SUBRIP | ASS | <font color=\"#00ff00\" face=\"Courier New\" size=\"24\">x</font> | {~c&H00FF00&~fnCourier New~fs24}x",
                "ASS | SUBRIP | {~c&H0000FF&}red{~c} plain | <font color=\"#ff0000\">red</font> plain",
                "ASS | SUBRIP | {~fnTimes New Roman~b1}t | <font face=\"Times New Roman\"><b>t</b></font>",
                "ASS | SUBRIP | {~a7}top right | {~an9}top right",
                "ASS | SUBRIP | {~fs20~fs+5}up | <font size=\"30\">up</font>",
                "ASS | SUBRIP | {~fs20~fs-5}down | <font size=\"10\">down</font>",
                "ASS | SUBRIP | {~fs+5}none | none",
                "ASS | SUBRIP | {~a6}top | {~an8}top",
                "ASS | SUBRIP | {~a10}middle | {~an5}middle",
                "ASS | WEBVTT | {~c&HFFFF00&}cyan{~r} {~i1}x | <c.cyan>cyan</c> <i>x</i>",
                "ASS | SUBRIP | {~t(0,500,~fs40~b1)~bord2~shad1~blur3~be1}anim | anim",
                "WEBVTT | SUBRIP | <c.yellow.bg_black>y</c> <c.loud>n</c> | <font color=\"#ffff00\">y</font> n",
                "WEBVTT | SUBRIP | <c.loud><b>Hi</b></c> &amp; <v Bob>bye</v> &lt;3 &#x263A; | <b>Hi</b> & bye <3 \u263A",
                "SUBRIP | WEBVTT | <font face=\"Arial\" size=\"30\">only</font> <s>strike</s> | only strike",
                "WEBVTT | WEBVTT | <v A>same</v> | <v A>same</v>"
        })
        @DisplayName("convert styles, colors, fonts and sizes as far as the target holds them")
        void convert(SubtitleMarkup from, SubtitleMarkup to, String text, String expected) {
            assertEquals(ass(expected), Markup.convert(ass(text), from, to));
        }

        @Test
        @DisplayName("keep a font tag open across style changes and nest styles properly")
        void nesting() {
            var text = ass("{~b1~fs20}x{~b0}~Ny~hz{~u1}w");
            assertEquals("<font size=\"20\"><b>x</b>\ny\u00A0z<u>w</u></font>", Markup.convert(text, SubtitleMarkup.ASS,
                    SubtitleMarkup.SUBRIP));
            assertEquals("<b>a</b><b><i>b</i></b><i>c</i>", Markup.convert("<b>a<i>b</b>c</i>", SubtitleMarkup.SUBRIP,
                    SubtitleMarkup.WEBVTT));
            assertEquals("<font color=\"#ff0000\">r<font color=\"#0000ff\">b</font>r</font>",
                    Markup.convert("<font color=\"#ff0000\">r<font color=\"#0000ff\">b</font>r</font>", SubtitleMarkup.SUBRIP,
                            SubtitleMarkup.SUBRIP));
            assertEquals("<font color=\"#ff0000\">r</font><font color=\"#0000ff\">b</font><font color=\"#ff0000\">r</font>",
                    Markup.write(Markup.parse("<font color=\"#ff0000\">r<font color=\"#0000ff\">b</font>r</font>",
                            SubtitleMarkup.SUBRIP, Markup.Context.NONE), SubtitleMarkup.SUBRIP, Markup.Context.NONE));
        }

        @Test
        @DisplayName("read ASS alpha, legacy alignment, the first alignment only and soft line breaks")
        void assDetails() {
            var styled = Markup.parse(ass("{~1a&H80&~c&H00FF00&~an1~an9}x~ny"), SubtitleMarkup.ASS, Markup.Context.NONE);
            assertEquals(1, styled.alignment());
            assertEquals(0x7F00FF00L, styled.runs().getFirst().color());
            assertEquals("x y", styled.plain());
            var script = AssScript.parse("[Script Info]\nWrapStyle: 2\n");
            assertEquals("x\ny", Markup.parse(ass("x~ny"), SubtitleMarkup.ASS,
                    new Markup.Context(-1, -1, script, "")).plain());
            assertEquals(ass("{~c&H00FF00&~1a&H80&}x y"), Markup.write(styled.withPlacement(0, Double.NaN, Double.NaN),
                    SubtitleMarkup.ASS, Markup.Context.NONE));
        }

        @Test
        @DisplayName("drop karaoke times when no cue start places them")
        void karaokeWithoutTimes() {
            assertEquals("Karaoke", Markup.convert(ass("{~k50}Ka{~k30}ra{~k20}oke"), SubtitleMarkup.ASS,
                    SubtitleMarkup.WEBVTT));
        }
    }

    @Nested
    @DisplayName("cue conversions")
    class Cues {
        @Test
        @DisplayName("turn ASS karaoke into WebVTT timestamps and back")
        void karaoke() {
            var cue = SubtitleCue.of(1_000_000, 3_000_000, ass("{~k50}Ka{~k30}ra{~k20}oke"));
            var webVtt = convert(cue, SubtitleMarkup.ASS, SubtitleMarkup.WEBVTT);
            assertEquals("Ka<00:00:01.500>ra<00:00:01.800>oke", webVtt.text());
            var back = convert(webVtt, SubtitleMarkup.WEBVTT, SubtitleMarkup.ASS);
            // WebVTT cannot say when the last syllable ends, so it lasts until the cue ends
            assertEquals(ass("{~k50}Ka{~k30}ra{~k120}oke"), back.text());
            var delayed = convert(SubtitleCue.of(0, 2_000_000, "a <00:00:00.250>b"), SubtitleMarkup.WEBVTT,
                    SubtitleMarkup.ASS);
            assertEquals(ass("{~k25}a {~k175}b"), delayed.text());
        }

        @Test
        @DisplayName("move placements between ASS overrides and WebVTT settings")
        void placement() {
            var cue = SubtitleCue.of(0, 1_000_000, ass("{~an7~pos(96,72)}Top left"));
            var webVtt = convert(cue, SubtitleMarkup.ASS, SubtitleMarkup.WEBVTT);
            assertEquals("Top left", webVtt.text());
            assertEquals("line:25%,start position:25%,line-left align:left", webVtt.settings());
            assertEquals(cue.text(), convert(webVtt, SubtitleMarkup.WEBVTT, SubtitleMarkup.ASS).text());
            var top = new SubtitleCue(0, 1_000_000, "x", "id", "align:start line:0");
            assertEquals(ass("{~an7}x"), convert(top, SubtitleMarkup.WEBVTT, SubtitleMarkup.SUBRIP).text());
            var middle = new SubtitleCue(0, 1_000_000, "x", "", "line:50% align:end");
            assertEquals(ass("{~an6}x"), convert(middle, SubtitleMarkup.WEBVTT, SubtitleMarkup.ASS).text());
            var bottom = new SubtitleCue(0, 1_000_000, "x", "", "line:-1 align:center");
            assertEquals("x", convert(bottom, SubtitleMarkup.WEBVTT, SubtitleMarkup.SUBRIP).text());
            assertEquals("line:0", convert(SubtitleCue.of(0, 1, ass("{~an8}x")), SubtitleMarkup.SUBRIP,
                    SubtitleMarkup.WEBVTT).settings());
        }

        @Test
        @DisplayName("carry the speaker between WebVTT voices and the ASS Name field")
        void speaker() {
            var webVtt = SubtitleCue.of(0, 1_000_000, "<v Alice>Hello</v>");
            var assCue = convert(webVtt, SubtitleMarkup.WEBVTT, SubtitleMarkup.ASS);
            assertEquals("Hello", assCue.text());
            assertEquals("0,Default,Alice,0,0,0,", assCue.settings());
            var fromAss = new SubtitleCue(0, 1_000_000, "Hi", "", "0,Main,Bob,10,20,30,Banner");
            assertEquals("<v Bob>Hi</v>", convert(fromAss, SubtitleMarkup.ASS, SubtitleMarkup.WEBVTT).text());
            assertEquals("", convert(SubtitleCue.of(0, 1, "x"), SubtitleMarkup.SUBRIP, SubtitleMarkup.ASS).settings());
        }
    }

    @Nested
    @DisplayName("ASS styles")
    class Styles {
        private static final String HEADER = """
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 1920
                PlayResY: 1080

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, \
                Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, \
                MarginR, MarginV, Encoding
                Style: Default,Arial,54,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
                Style: Sign,Arial,54,&H0000FFFF,&H000000FF,&H00000000,&H00000000,-1,-1,0,0,100,100,0,0,1,2,0,8,10,10,10,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                """;

        private static SubtitleDecoder decoder(String header) {
            var track = DataTrack.builder().id(1).codec(Codec.ASS).timeBase(Rational.MILLISECONDS)
                    .codecConfig(header.getBytes(StandardCharsets.UTF_8)).build();
            return new SubtitleDecoder(track);
        }

        private static Packet event(String block) {
            return new Packet(1, block.getBytes(StandardCharsets.UTF_8), 1000, 1000, 2000, true);
        }

        @Test
        @DisplayName("give an event the bold, italic, color and alignment of its style when converted")
        void resolved() {
            var decoder = decoder(HEADER);
            var packet = event("0,0,Sign,,0,0,0,,Exit");
            var own = decoder.decode(packet);
            assertEquals("Exit", own.text());
            assertEquals("0,Sign,,0,0,0,", own.settings());
            assertEquals(ass("{~an8}<font color=\"#ffff00\"><b><i>Exit</i></b></font>"),
                    decoder.decode(packet, SubtitleMarkup.SUBRIP).text());
            var webVtt = decoder.decode(packet, SubtitleMarkup.WEBVTT);
            assertEquals("<c.yellow><b><i>Exit</i></b></c>", webVtt.text());
            assertEquals("line:0", webVtt.settings());
            assertEquals("Plain", decoder.decode(event("0,0,Default,,0,0,0,,Plain"), SubtitleMarkup.SUBRIP).text());
            assertEquals("<c.yellow><b><i>Fallback</i></b></c>", decoder.decode(event(ass("0,0,Sign,,0,0,0,,{~i0~r}Fallback")),
                    SubtitleMarkup.WEBVTT).text());
        }

        @Test
        @DisplayName("reset to another style with r and scale positions and sizes from the script resolution")
        void resetAndScale() {
            var decoder = decoder(HEADER);
            var cue = decoder.decode(event(ass("0,0,Default,,0,0,0,,{~pos(960,540)~fs108}a{~rSign}b")),
                    SubtitleMarkup.ASS);
            assertEquals(ass("{~pos(960,540)~fs108}a{~rSign}b"), cue.text());
            var converted = decoder.decode(event(ass("0,0,Default,,0,0,0,,{~pos(960,540)~fs108}a{~rSign}b")),
                    SubtitleMarkup.WEBVTT);
            assertEquals("line:50%,end position:50%,center", converted.settings());
            var subRip = decoder.decode(event(ass("0,0,Default,,0,0,0,,{~fs108}a{~rSign}b")), SubtitleMarkup.SUBRIP);
            // 108 pixels of a 1080-line script are 28.8 of the 288-line reference
            assertEquals("<font size=\"29\">a</font><font color=\"#ffff00\"><b><i>b</i></b></font>", subRip.text());
        }

        @Test
        @DisplayName("read SSA styles: legacy alignment, decimal colors and the Marked field")
        void ssa() {
            var header = """
                    [Script Info]
                    ScriptType: v4.00

                    [V4 Styles]
                    Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, \
                    Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
                    Style: Default,Arial,20,65535,255,0,0,0,-1,1,2,0,7,10,10,10,0,0

                    [Events]
                    Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                    """;
            var script = AssScript.parse(header);
            assertTrue(script.isSsa());
            var style = script.style("Default");
            assertEquals(9, style.alignment());
            assertEquals(0xFFFFFF00L, style.primary());
            assertEquals(StyledText.ITALIC, style.style());
            var decoder = decoder(header);
            assertEquals(ass("{~an9}<font color=\"#ffff00\"><i>x</i></font>"),
                    decoder.decode(event("0,0,Default,,0,0,0,,x"), SubtitleMarkup.SUBRIP).text());
        }

        @Test
        @DisplayName("encode cues of other markups into the Default style with a scaled position")
        void encode() {
            var track = DataTrack.builder().id(1).codec(Codec.ASS).timeBase(Rational.MILLISECONDS)
                    .codecConfig(HEADER.getBytes(StandardCharsets.UTF_8)).build();
            var encoder = new SubtitleEncoder(track);
            var cue = new SubtitleCue(0, 1_000_000, "<v Ann>a</v>", "", "line:25%,start position:75%,line-right");
            var packet = encoder.encode(cue, SubtitleMarkup.WEBVTT);
            assertEquals(ass("0,0,Default,Ann,0,0,0,,{~an9~pos(1440,270)}a"), new String(packet.data(), StandardCharsets.UTF_8));
            var second = encoder.encode(new SubtitleCue(0, 1, "b", "", "1,Sign,,0,0,0,"), SubtitleMarkup.ASS);
            assertEquals("1,1,Sign,,0,0,0,,b", new String(second.data(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("parse colors in CSS and ASS notations")
    void colors() {
        assertEquals(0xFFFF0000L, SubtitleColors.parseCss("#f00"));
        assertEquals(0xFF00FF00L, SubtitleColors.parseCss("#00FF00"));
        assertEquals(0x800000FFL, SubtitleColors.parseCss("#0000ff80"));
        assertEquals(0x80010203L, SubtitleColors.parseCss("rgba(1, 2, 3, 128)"));
        assertEquals(0xFF010203L, SubtitleColors.parseCss("rgb(1,2,3)"));
        assertEquals(0xFFFFA500L, SubtitleColors.parseCss("Orange"));
        assertEquals(StyledText.NO_COLOR, SubtitleColors.parseCss("rgb(1,2)"));
        assertEquals(StyledText.NO_COLOR, SubtitleColors.parseCss("#12345"));
        assertEquals(StyledText.NO_COLOR, SubtitleColors.parseCss("notacolor"));
        assertEquals(0xFF332211L, SubtitleColors.parseAss("&H112233&", false));
        assertEquals(0x7F332211L, SubtitleColors.parseAss("&H80112233", false));
        assertEquals(0xFFFFFF00L, SubtitleColors.parseAss("65535", true));
        assertEquals(StyledText.NO_COLOR, SubtitleColors.parseAss("&HXYZ&", false));
        assertEquals("&H332211&", SubtitleColors.ass(0xFF112233L));
        assertEquals("&H80332211", SubtitleColors.assWithAlpha(0x7F112233L));
        assertEquals("yellow", SubtitleColors.webVttClassOf(0xFFFFFF00L));
        assertEquals(null, SubtitleColors.webVttClassOf(0xFFFFA500L));
        assertEquals(null, SubtitleColors.webVttClassOf(0x80FFFF00L));
        assertFalse(SubtitleColors.css(0x80FFFFFFL).equals("#ffffff"));
    }
}
