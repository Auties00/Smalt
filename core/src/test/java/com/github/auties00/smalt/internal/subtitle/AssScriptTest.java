package com.github.auties00.smalt.internal.subtitle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the SSA and ASS script model: header parsing, styles, script resolution, the events of a script
 * file in their own field order, and the conversion between the v4.00 and v4.00+ versions.
 */
@DisplayName("SSA and ASS scripts")
class AssScriptTest {
    private static final char BS = (char) 92;

    static final String ASS = """
            [Script Info]
            ; a comment
            Title: Demo
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 480
            WrapStyle: 0

            [Aegisub Project Garbage]
            Active Line: 2

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, \
            Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, \
            MarginR, MarginV, Encoding
            Style: Default,Arial,28,&H00FFFFFF,&H000000FF,&H00101010,&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,20,1
            Style: Top,DejaVu Sans,24,&H0000FFFF,&H000000FF,&H00000000,&H00000000,-1,0,-1,0,95,100,1.5,0,3,1,0,8,10,10,15,0

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Comment: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,a note
            Dialogue: 1,0:00:05.00,0:00:06.50,Top,Narrator,0000,0000,0025,,{\\pos(320,40)}Second in time
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,First\\Nline
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,Bob,0,0,0,Banner;10,{\\b1}Overlap, with commas
            Dialogue: 0,bad,0:00:03.00,Default,,0,0,0,,dropped

            [Fonts]
            fontname: demo.ttf
            M3=!""";

    @Test
    @DisplayName("parse the info fields, styles and event format of an ASS header")
    void header() {
        var script = AssScript.parse(ASS);
        assertFalse(script.isSsa());
        assertEquals("Demo", script.info("title"));
        assertEquals(640, script.width());
        assertEquals(480, script.height());
        assertFalse(script.softBreaksAreLineBreaks());
        assertEquals(List.of("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"),
                script.eventFormat());
        assertEquals(2, script.styles().size());
        var top = script.style("top");
        assertEquals("Top", top.name());
        assertEquals("DejaVu Sans", top.font());
        assertEquals(24, top.size());
        assertEquals(0xFFFFFF00L, top.primary());
        assertEquals(StyledText.BOLD | StyledText.UNDERLINE, top.style());
        assertEquals(8, top.alignment());
        assertEquals("1.5", top.fields().get("spacing"));
        assertEquals("Default", script.style("missing").name());
        assertEquals("Top", script.style("*Top").name());
    }

    @Test
    @DisplayName("derive the script resolution as players do")
    void resolution() {
        assertEquals(384, AssScript.parse("[Script Info]\n").width());
        assertEquals(288, AssScript.parse("[Script Info]\n").height());
        assertEquals(1280, AssScript.parse("[Script Info]\nPlayResY: 1024\n").width());
        assertEquals(960, AssScript.parse("[Script Info]\nPlayResY: 720\n").width());
        assertEquals(1024, AssScript.parse("[Script Info]\nPlayResX: 1280\n").height());
        assertEquals(540, AssScript.parse("[Script Info]\nPlayResX: 720\n").height());
        assertEquals(384, AssScript.parse("[Script Info]\nPlayResX: -5\n").width());
        assertNull(AssScript.parse("").style("Default"));
    }

    @Test
    @DisplayName("map the SSA legacy alignment to the numpad one and back")
    void legacyAlignment() {
        int[] legacy = {1, 2, 3, 9, 10, 11, 5, 6, 7};
        for (var numpad = 1; numpad <= 9; numpad++) {
            assertEquals(numpad, AssScript.fromLegacyAlignment(legacy[numpad - 1]));
            assertEquals(legacy[numpad - 1], AssScript.toLegacyAlignment(numpad));
        }
        assertEquals(2, AssScript.fromLegacyAlignment(0));
        assertEquals(2, AssScript.fromLegacyAlignment(4));
        assertEquals(2, AssScript.fromLegacyAlignment(12));
        assertEquals(2, AssScript.toLegacyAlignment(0));
    }

    @Test
    @DisplayName("read the events of a script with their fields, in file order, dropping malformed lines")
    void events() {
        var parsed = AssFile.parse(ASS);
        assertEquals(3, parsed.events().size());
        var first = parsed.events().getFirst();
        assertEquals(0, first.event().readOrder());
        assertEquals(5_000_000, first.startMicros());
        assertEquals(6_500_000, first.endMicros());
        assertEquals("1,Top,Narrator,0000,0000,0025,", first.event().fields());
        assertEquals("0,1,Top,Narrator,0000,0000,0025,,{" + BS + "pos(320,40)}Second in time", first.event().block());
        var third = parsed.events().get(2);
        assertEquals("Banner;10", third.event().effect());
        assertEquals("{" + BS + "b1}Overlap, with commas", third.event().text());
        assertTrue(parsed.header().contains("Comment: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,a note"));
        assertTrue(parsed.header().contains("[Fonts]\nfontname: demo.ttf\nM3=!"));
        assertFalse(parsed.header().contains("Dialogue"));
        assertTrue(AssFile.isSignature("\n; generated\n[script info]\n"));
        assertFalse(AssFile.isSignature("[Events]\n"));
    }

    @Test
    @DisplayName("write a script back with its events in their section and the sections after it in place")
    void writeBack() {
        var parsed = AssFile.parse(ASS);
        var written = AssFile.write(parsed.header(), parsed.events());
        var expected = ASS.replace("Dialogue: 0,bad,0:00:03.00,Default,,0,0,0,,dropped\n", "") + "\n";
        assertEquals(expected, written);
        var custom = "[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Start, End, Style, Text\n";
        var reordered = AssFile.parse(custom + "Dialogue: 0:00:01.00,0:00:02.00,Top,a, b\n");
        var event = reordered.events().getFirst();
        assertEquals("Top", event.event().style());
        assertEquals("a, b", event.event().text());
        assertEquals("0", event.event().layer());
        assertEquals(custom + "Dialogue: 0:00:01.00,0:00:02.00,Top,a, b\n", AssFile.write(reordered.header(),
                reordered.events()));
        var noEvents = AssFile.write("[Script Info]\nScriptType: v4.00\n", List.of(parsed.events().get(1)));
        assertEquals("[Script Info]\nScriptType: v4.00\n\n[Events]\nFormat: Marked, Start, End, Style, Name, MarginL, "
                + "MarginR, MarginV, Effect, Text\nDialogue: Marked=0,0:00:01.00,0:00:02.00,Default,,0,0,0,,First" + BS
                + "Nline\n", noEvents);
    }

    @Test
    @DisplayName("convert an ASS header to SSA and back, keeping what both versions hold")
    void convert() {
        var ssa = AssScript.convert(AssFile.parse(ASS).header(), true);
        var script = AssScript.parse(ssa);
        assertTrue(script.isSsa());
        assertTrue(ssa.contains("ScriptType: v4.00\n"));
        assertTrue(ssa.contains("[V4 Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, "
                + "TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, "
                + "MarginV, AlphaLevel, Encoding\n"));
        assertTrue(ssa.contains("Style: Top,DejaVu Sans,24,65535,255,0,0,-1,0,3,1,0,6,10,10,15,0,0\n"), ssa);
        assertTrue(ssa.contains("Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"));
        assertTrue(ssa.contains("[Aegisub Project Garbage]\nActive Line: 2"));
        assertEquals(8, script.style("Top").alignment());
        var back = AssScript.parse(AssScript.convert(ssa, false));
        assertFalse(back.isSsa());
        var top = back.style("Top");
        assertEquals(8, top.alignment());
        assertEquals(0xFFFFFF00L, top.primary());
        assertEquals(StyledText.BOLD, top.style());
        assertEquals("&H00FFFF00".length(), top.fields().get("primarycolour").length());
        assertEquals("100", top.fields().get("scalex"));
        assertEquals(ASS.substring(0, 20), AssScript.convert(ASS, false).substring(0, 20));
        var minimal = AssScript.convert("[Script Info]\nTitle: x\n\n[V4 Styles]\nStyle: A,Arial,20,255,0,0,0,0,0,1,0,0,2,0,0,0,0,0\n",
                false);
        assertTrue(minimal.contains("ScriptType: v4.00+"), minimal);
        assertTrue(minimal.contains("Style: A,Arial,20,&H000000FF,"), minimal);
    }

    @Test
    @DisplayName("parse and format event times at centisecond precision")
    void times() {
        assertEquals(3_723_450_000L, AssEvents.parseTime("1:02:03.45"));
        assertEquals(3_000_000, AssEvents.parseTime(" 0:00:03 "));
        assertEquals(-1, AssEvents.parseTime("0:00:03.45x"));
        assertEquals("1:02:03.46", AssEvents.formatTime(3_723_455_000L));
        assertEquals("0:00:00.00", AssEvents.formatTime(-5));
        assertEquals("100:00:00.00", AssEvents.formatTime(360_000_000_000L));
        var block = AssEvents.parseBlock("7,Marked=0,Default,,0,0,0,,x");
        assertEquals("0", block.layer());
        assertEquals(7, block.readOrder());
        assertEquals("0,Default,,0,0,0,", AssEvents.parseBlock("no fields").fields());
        assertEquals("no fields", AssEvents.parseBlock("no fields").text());
        assertEquals("0,Default,,0,0,0,", AssEvents.withFields(0, "garbage", "x").fields());
    }
}
