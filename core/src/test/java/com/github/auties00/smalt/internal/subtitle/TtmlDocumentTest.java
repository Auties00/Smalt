package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the TTML parser: the timing model and time expressions, style resolution through referential
 * styles, regions and inheritance, whitespace handling, placement, and the documents the writer produces.
 */
@DisplayName("TTML documents")
class TtmlDocumentTest {
    private static final String NAMESPACES = "xmlns=\"http://www.w3.org/ns/ttml\" "
            + "xmlns:ttp=\"http://www.w3.org/ns/ttml#parameter\" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\" "
            + "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"";

    private static TtmlDocument parse(String document) {
        return TtmlDocument.parse(document.getBytes(StandardCharsets.UTF_8));
    }

    /** Describes each cue as {@code start-end:text} in microseconds. */
    private static List<String> timeline(TtmlDocument document) {
        var result = new ArrayList<String>();
        for (var cue : document.cues()) {
            result.add(cue.startMicros() + "-" + cue.endMicros() + ":" + cue.text().plain());
        }
        return result;
    }

    @Nested
    @DisplayName("timing")
    class Timing {
        @Test
        @DisplayName("reads clock, frame, tick and offset times relative to the parent, in par and seq containers")
        void expressions() {
            var document = parse("<tt " + NAMESPACES + " ttp:frameRate=\"25\" ttp:tickRate=\"10000000\"><body>"
                    + "<div begin=\"10s\">"
                    + "<p begin=\"00:00:01.5\" end=\"00:00:03\">clock</p>"
                    + "<p begin=\"25f\" dur=\"50f\">frames</p>"
                    + "<p begin=\"10000000t\" end=\"2.5s\">ticks</p>"
                    + "<p begin=\"00:00:01:12\" end=\"00:00:02:00\">clockframes</p>"
                    + "<p begin=\"100ms\" end=\"0.0025h\">units</p>"
                    + "</div>"
                    + "<div timeContainer=\"seq\" begin=\"30s\"><p dur=\"1s\">s1</p><p begin=\"1s\" dur=\"1s\">s2</p></div>"
                    + "<div begin=\"40s\" end=\"41s\"><p begin=\"0.5s\" end=\"5s\">clipped</p></div>"
                    + "</body></tt>");
            assertEquals(List.of("10100000-19000000:units", "11000000-13000000:frames", "11000000-12500000:ticks",
                    "11480000-12000000:clockframes", "11500000-13000000:clock", "30000000-31000000:s1",
                    "32000000-33000000:s2", "40500000-41000000:clipped"), timeline(document));
            assertEquals(41_000_000, document.endMicros());
        }

        @Test
        @DisplayName("applies the frame rate multiplier, sub-frames and SMPTE drop-frame counting")
        void frameRates() {
            var ntsc = parse("<tt " + NAMESPACES + " ttp:frameRate=\"30\" ttp:frameRateMultiplier=\"1000 1001\""
                    + " ttp:subFrameRate=\"2\"><body><div>"
                    + "<p begin=\"30f\" end=\"00:00:02:00.1\">a</p></div></body></tt>");
            // media time: 2 s plus half a frame of 1001/30000 s; the frame rate multiplier stretches frames, not seconds
            assertEquals(List.of("1001000-2016683:a"), timeline(ntsc));
            var drop = parse("<tt " + NAMESPACES + " ttp:timeBase=\"smpte\" ttp:frameRate=\"30\""
                    + " ttp:frameRateMultiplier=\"1000 1001\" ttp:dropMode=\"dropNTSC\"><body><div>"
                    + "<p begin=\"00:01:00:02\" end=\"00:10:00:00\">dropped</p></div></body></tt>");
            // 00:01:00;02 is frame 1800 and 00:10:00;00 frame 17982 of drop-frame time code
            assertEquals(List.of("60060000-599999400:dropped"), timeline(drop));
        }

        @Test
        @DisplayName("splits a paragraph where its timed spans appear and disappear")
        void spans() {
            var document = parse("<tt " + NAMESPACES + "><body><div>"
                    + "<p begin=\"5s\" end=\"7s\">a <span begin=\"1s\">late</span><span end=\"0.5s\">early</span></p>"
                    + "<p begin=\"8s\" end=\"9s\">same <span end=\"0.5s\"/>text</p>"
                    + "</div></body></tt>");
            assertEquals(List.of("5000000-5500000:a early", "5500000-6000000:a", "6000000-7000000:a late",
                    "8000000-9000000:same text"), timeline(document));
        }

        @Test
        @DisplayName("gives untimed content the end of the latest time the document names")
        void untimed() {
            var document = parse("<tt " + NAMESPACES + "><body><div>"
                    + "<p>always</p><p begin=\"1s\" end=\"3s\">timed</p></div></body></tt>");
            assertEquals(List.of("0-3000000:always", "1000000-3000000:timed"), timeline(document));
        }
    }

    @Nested
    @DisplayName("styling and layout")
    class Styling {
        private static final String DOCUMENT = "<tt " + NAMESPACES + " xml:lang=\"en\"><head>"
                + "<metadata><ttm:title>Demo</ttm:title></metadata>"
                + "<styling>"
                + "<style xml:id=\"base\" tts:color=\"yellow\" tts:fontFamily=\"proportionalSansSerif\"/>"
                + "<style xml:id=\"bold\" style=\"base\" tts:fontWeight=\"bold\"/>"
                + "</styling><layout>"
                + "<region xml:id=\"bottom\" tts:origin=\"10% 80%\" tts:extent=\"80% 15%\" tts:displayAlign=\"after\""
                + " tts:textAlign=\"center\" tts:fontSize=\"2c\"/>"
                + "<region xml:id=\"top\" tts:origin=\"3% 3%\" tts:extent=\"94% 20%\" tts:displayAlign=\"before\""
                + " tts:textAlign=\"left\"/>"
                + "<region xml:id=\"side\" tts:origin=\"60% 40%\" tts:extent=\"30% 20%\" tts:displayAlign=\"center\""
                + " tts:textAlign=\"end\"/>"
                + "</layout></head>"
                + "<body region=\"bottom\"><div>"
                + "<p begin=\"1s\" end=\"2s\" style=\"bold\">Yellow <span tts:fontStyle=\"italic\" tts:color=\"#00ff00\">"
                + "green</span><br/>  two   lines </p>"
                + "<p begin=\"2s\" end=\"3s\" region=\"top\"><span tts:textDecoration=\"underline lineThrough\""
                + " tts:backgroundColor=\"black\">u</span> <span tts:fontSize=\"150%\">big</span>"
                + " <span tts:fontFamily=\"'Courier New', monospace\">mono</span></p>"
                + "<p begin=\"3s\" end=\"4s\" region=\"none\">unknown region</p>"
                + "<p begin=\"4s\" end=\"5s\" tts:display=\"none\">not shown</p>"
                + "<p begin=\"5s\" end=\"6s\" region=\"side\">placed</p>"
                + "<p begin=\"6s\" end=\"7s\"><span tts:visibility=\"hidden\">hidden </span>visible</p>"
                + "</div></body></tt>";

        @Test
        @DisplayName("resolves referential styles, region styles and inline styles with inheritance")
        void styles() {
            var document = parse(DOCUMENT);
            assertEquals("Demo", document.title());
            var cues = document.cues();
            assertEquals(List.of("1000000-2000000:Yellow green\ntwo lines", "2000000-3000000:u big mono",
                    "5000000-6000000:placed", "6000000-7000000:visible"), timeline(document));
            var first = cues.getFirst().text();
            assertEquals(List.of(
                    new StyledText.Run("Yellow ", StyledText.BOLD, 0xFFFFFF00L, StyledText.NO_COLOR, "", Double.NaN, -1),
                    new StyledText.Run("green", StyledText.BOLD | StyledText.ITALIC, 0xFF00FF00L, StyledText.NO_COLOR, "",
                            Double.NaN, -1),
                    new StyledText.Run("\ntwo lines", StyledText.BOLD, 0xFFFFFF00L, StyledText.NO_COLOR, "", Double.NaN,
                            -1)), first.runs());
            assertEquals(0, first.alignment());
            assertFalse(first.hasPosition());
            var second = cues.get(1).text();
            assertEquals(7, second.alignment());
            assertFalse(second.hasPosition());
            var runs = second.runs();
            assertEquals(5, runs.size());
            assertEquals(new StyledText.Run("u", StyledText.UNDERLINE | StyledText.STRIKEOUT, StyledText.NO_COLOR,
                    0xFF000000L, "", Double.NaN, -1), runs.get(0));
            assertEquals(StyledText.Run.of(" ", 0), runs.get(1));
            assertEquals("big", runs.get(2).text());
            // 150 percent of the default one-cell size of a 15-row grid on the 288-line reference frame
            assertEquals(28.8, runs.get(2).size(), 1e-9);
            assertEquals(StyledText.Run.of(" ", 0), runs.get(3));
            assertEquals(new StyledText.Run("mono", 0, StyledText.NO_COLOR, StyledText.NO_COLOR, "Courier New",
                    Double.NaN, -1), runs.get(4));
            var placed = cues.get(2).text();
            assertEquals(6, placed.alignment());
            assertEquals(0.9, placed.x(), 1e-9);
            assertEquals(0.5, placed.y(), 1e-9);
            assertEquals("<b><i>x</i></b>", Markup.write(StyledText.EMPTY.withRuns(List.of(StyledText.Run.of("x",
                    StyledText.BOLD | StyledText.ITALIC))), SubtitleMarkup.SUBRIP, Markup.Context.NONE));
        }

        @Test
        @DisplayName("places content of the default region at the top left, as TTML does")
        void defaultRegion() {
            var document = parse("<tt " + NAMESPACES + "><body><div><p begin=\"0s\" end=\"1s\">x</p></div></body></tt>");
            assertEquals(7, document.cues().getFirst().text().alignment());
            var ffmpeg = parse("<tt " + NAMESPACES + " ttp:cellResolution=\"384 288\"><head><layout>"
                    + "<region xml:id=\"Default\" tts:origin=\"3% 0%\" tts:extent=\"97% 97%\" tts:displayAlign=\"after\""
                    + " tts:textAlign=\"center\" tts:fontSize=\"16c\" tts:fontFamily=\"Arial\"/></layout></head><body><div>"
                    + "<p begin=\"00:00:01.000\" end=\"00:00:02.500\"><span region=\"Default\">Hello world<br/>line two"
                    + "</span></p></div></body></tt>");
            var cue = ffmpeg.cues().getFirst();
            assertEquals(List.of(StyledText.Run.of("Hello world\nline two", 0)), cue.text().runs());
            assertEquals(0, cue.text().alignment());
            assertFalse(cue.text().hasPosition());
        }

        @Test
        @DisplayName("keeps whitespace under xml:space preserve")
        void whitespace() {
            var document = parse("<tt " + NAMESPACES + "><body><div>"
                    + "<p begin=\"0s\" end=\"1s\" xml:space=\"preserve\">a  b\nc</p>"
                    + "<p begin=\"1s\" end=\"2s\"> x <span xml:space=\"preserve\">  y  </span> z </p>"
                    + "</div></body></tt>");
            assertEquals(List.of("0-1000000:a  b\nc", "1000000-2000000:x   y   z"), timeline(document));
        }
    }

    @Nested
    @DisplayName("documents")
    class Documents {
        @Test
        @DisplayName("reads the TTML draft namespaces, the XML encoding declaration and prefixed elements")
        void variants() {
            var draft = parse("<tt xmlns=\"http://www.w3.org/2006/10/ttaf1\" xmlns:tts=\"http://www.w3.org/2006/10/ttaf1#styling\">"
                    + "<body><div><p begin=\"1s\" end=\"2s\"><span tts:fontWeight=\"bold\">dfxp</span></p></div></body></tt>");
            assertEquals(StyledText.BOLD, draft.cues().getFirst().text().runs().getFirst().style());
            var latin = ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><tt " + NAMESPACES + "><body><div>"
                    + "<p begin=\"0s\" end=\"1s\">caf\u00E9</p></div></body></tt>").getBytes(StandardCharsets.ISO_8859_1);
            assertEquals("caf\u00E9", TtmlDocument.parse(latin).cues().getFirst().text().plain());
            var prefixed = parse("<t:tt xmlns:t=\"http://www.w3.org/ns/ttml\"><t:body><t:div><t:p begin=\"0s\" end=\"1s\">"
                    + "p</t:p></t:div></t:body></t:tt>");
            assertEquals("p", prefixed.cues().getFirst().text().plain());
            assertTrue(TtmlDocument.isSignature("\uFEFF<?xml version=\"1.0\"?>\n<!-- c -->\n<tt:tt xmlns:tt=\"x\">"));
            assertFalse(TtmlDocument.isSignature("<html>"));
        }

        @Test
        @DisplayName("rejects documents that are not well-formed TTML, and document type declarations")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> parse("<tt " + NAMESPACES + "><body>"));
            assertThrows(MediaException.Malformed.class, () -> parse("<html><body/></html>"));
            assertThrows(MediaException.Malformed.class, () -> parse("<!DOCTYPE tt [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                    + "<tt " + NAMESPACES + "><body><div><p begin=\"0s\" end=\"1s\">&x;</p></div></body></tt>"));
            assertThrows(MediaException.Malformed.class, () -> parse("<tt " + NAMESPACES + ">"
                    + "<span>".repeat(100) + "</span>".repeat(100) + "</tt>"));
        }

        @Test
        @DisplayName("writes cues that read back with their styles, colors, sizes and placements")
        void writer() {
            var cues = List.of(
                    SubtitleCue.of(1_000_000, 2_000_000, "<b>bold</b> <font color=\"#ff0000\" size=\"24\">red</font>"),
                    SubtitleCue.of(1_500_000, 3_000_000, "{\\an8}top & <i>i</i>\nline2"),
                    SubtitleCue.of(4_000_000, 5_000_000, "<s>s</s><u>u</u> <font face=\"Courier New\">c</font>"),
                    SubtitleCue.of(6_000_000, 7_000_000, "{\\an1\\pos(96,72)}anchored"));
            var bytes = TtmlWriter.write(cues, SubtitleMarkup.SUBRIP);
            var document = TtmlDocument.parse(bytes);
            assertEquals(cues.size(), document.cues().size());
            for (var index = 0; index < cues.size(); index++) {
                var expected = Markup.parseCue(cues.get(index), SubtitleMarkup.SUBRIP, Markup.Context.of(cues.get(index)));
                var actual = document.cues().get(index);
                assertEquals(cues.get(index).startMicros(), actual.startMicros());
                assertEquals(cues.get(index).endMicros(), actual.endMicros());
                assertEquals(expected.runs(), actual.text().runs(), "cue " + index);
                assertEquals(expected.alignment(), actual.text().alignment(), "cue " + index);
                assertEquals(expected.x(), actual.text().x(), 1e-9);
                assertEquals(expected.y(), actual.text().y(), 1e-9);
            }
            assertEquals(List.of(TtmlDocument.TT, TtmlDocument.TTM, TtmlDocument.TTP, TtmlDocument.TTS), document.namespaces());
            var empty = TtmlDocument.parse(TtmlWriter.write(List.of(), SubtitleMarkup.SUBRIP));
            assertTrue(empty.cues().isEmpty());
        }
    }
}
