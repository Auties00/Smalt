package com.github.auties00.smalt.internal.subtitle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what TTML documents may get wrong or leave out: time expressions and parameters that are not understood,
 * drop-frame modes, every length and font size unit, alignment keywords, text decoration tokens, animation through
 * {@code set}, timed regions, the draft namespaces and the encoding declaration.
 */
@DisplayName("TTML edge cases")
class TtmlEdgeCasesTest {
    private static final String NAMESPACES = "xmlns=\"http://www.w3.org/ns/ttml\" "
            + "xmlns:ttp=\"http://www.w3.org/ns/ttml#parameter\" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\" "
            + "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"";

    private static TtmlDocument parse(String document) {
        return TtmlDocument.parse(document.getBytes(StandardCharsets.UTF_8));
    }

    private static TtmlDocument body(String rootAttributes, String content) {
        return parse("<tt " + NAMESPACES + " " + rootAttributes + "><body><div>" + content + "</div></body></tt>");
    }

    private static List<String> timeline(TtmlDocument document) {
        var result = new ArrayList<String>();
        for (var cue : document.cues()) {
            result.add(cue.startMicros() + "-" + cue.endMicros() + ":" + cue.text().plain());
        }
        return result;
    }

    private static StyledText.Run run(TtmlDocument document, String text) {
        for (var cue : document.cues()) {
            for (var run : cue.text().runs()) {
                if (run.text().strip().equals(text)) {
                    return run;
                }
            }
        }
        throw new AssertionError("no run " + text + " in " + timeline(document));
    }

    @Nested
    @DisplayName("time expressions")
    class Times {
        @ParameterizedTest(name = "end=\"{0}\"")
        @ValueSource(strings = {"", "   ", "1:2", "1:2:3:4:5", "x:00:01", "00:-1:00", "00:00:x", "00:00:-1",
                "00:00:01:x", "00:00:x:01", "00:00:01:-1", "-1s", "1q", "abcs", "1e5s",
                "0000000000000000000000000000000000000000001s"})
        @DisplayName("treats an end that is not a time expression as indefinite")
        void badEnd(String end) {
            var document = body("", "<p begin=\"1s\" end=\"" + end + "\">a</p><p begin=\"0s\" end=\"9s\">b</p>");
            assertEquals(List.of("0-9000000:b", "1000000-9000000:a"), timeline(document));
        }

        @Test
        @DisplayName("reads a bad begin as the parent's begin and ignores a bad duration")
        void badBeginAndDuration() {
            var document = body("", "<p begin=\"later\" end=\"2s\">a</p><p begin=\"1s\" end=\"3s\" dur=\"long\">b</p>");
            assertEquals(List.of("0-2000000:a", "1000000-3000000:b"), timeline(document));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"dropPAL,119840000", "nonDrop,120000000", "unknown,120000000"})
        @DisplayName("counts SMPTE time codes in the drop mode of the document")
        void dropModes(String mode, long micros) {
            var document = body("ttp:timeBase=\"smpte\" ttp:frameRate=\"25\" ttp:dropMode=\"" + mode + "\"",
                    "<p begin=\"0s\" end=\"00:02:00:00\">a</p>");
            assertEquals(List.of("0-" + micros + ":a"), timeline(document));
        }

        @Test
        @DisplayName("keeps the defaults for parameters it does not understand")
        void badParameters() {
            var document = body("ttp:frameRate=\"0\" ttp:frameRateMultiplier=\"1000\" ttp:subFrameRate=\"0\""
                    + " ttp:tickRate=\"none\" ttp:cellResolution=\"10\"", "<p begin=\"30f\" end=\"60f\">a</p>"
                    + "<p begin=\"1s\" end=\"30t\">b</p>");
            // the default frame rate of 30; with an unusable tick rate the tick rate is the frame rate, so b is empty
            assertEquals(List.of("1000000-2000000:a"), timeline(document));
            var multiplier = body("ttp:frameRate=\"10\" ttp:frameRateMultiplier=\"0 1\"", "<p begin=\"10f\" end=\"20f\">a</p>");
            assertEquals(List.of("1000000-2000000:a"), timeline(multiplier));
            var ticks = body("ttp:frameRate=\"10\" ttp:subFrameRate=\"2\" ttp:tickRate=\"0\"",
                    "<p begin=\"20t\" end=\"40t\">a</p>");
            assertEquals(List.of("1000000-2000000:a"), timeline(ticks), "ticks count frames times sub-frames");
            var cells = body("ttp:cellResolution=\"0 10\"", "<p begin=\"0s\" end=\"1s\"><span tts:fontSize=\"1c\">a</span>"
                    + "</p>");
            assertTrue(Double.isNaN(run(cells, "a").size()), "one cell of the default 15 rows is the default size");
        }

        @Test
        @DisplayName("gives a timed region's content only the time the region is active")
        void regionTiming() {
            var document = parse("<tt " + NAMESPACES + "><head><layout>"
                    + "<region xml:id=\"r\" begin=\"1s\" end=\"3s\" dur=\"1s\" tts:origin=\"10% 10%\" tts:extent=\"50% 50%\"/>"
                    + "<region xml:id=\"open\" end=\"never\" dur=\"x\"/>"
                    + "</layout></head><body><div>"
                    + "<p begin=\"0s\" end=\"4s\" region=\"r\">a</p><p begin=\"0s\" end=\"1s\" region=\"open\">b</p>"
                    + "</div></body></tt>");
            assertEquals(List.of("0-1000000:b", "1000000-2000000:a"), timeline(document));
        }

        @Test
        @DisplayName("restyles content while a set element is active")
        void set() {
            var document = body("", "<p begin=\"0s\" end=\"2s\"><set begin=\"1s\" end=\"2s\" tts:color=\"red\"/>text</p>");
            var cues = document.cues();
            assertEquals(2, cues.size(), timeline(document).toString());
            assertEquals(StyledText.NO_COLOR, cues.get(0).text().runs().getFirst().color());
            assertEquals(0xFFFF0000L, cues.get(1).text().runs().getFirst().color());
        }
    }

    @Nested
    @DisplayName("lengths and sizes")
    class Lengths {
        @Test
        @DisplayName("places regions given in pixels of the root extent, cells and root container units")
        void regionUnits() {
            var document = parse("<tt " + NAMESPACES + " tts:extent=\"640px 480px\" ttp:cellResolution=\"20 10\">"
                    + "<head><layout>"
                    + "<region xml:id=\"px\" tts:origin=\"64px 48px\" tts:extent=\"2c 3c\" tts:displayAlign=\"before\""
                    + " tts:textAlign=\"start\"/>"
                    + "<region xml:id=\"root\" tts:origin=\"10rw 20rh\" tts:extent=\"50% 10%\" tts:displayAlign=\"after\""
                    + " tts:textAlign=\"center\"/>"
                    + "<region xml:id=\"bad\" tts:origin=\"left top\" tts:extent=\"80%\"/>"
                    + "</layout></head><body><div>"
                    + "<p begin=\"0s\" end=\"1s\" region=\"px\">px</p>"
                    + "<p begin=\"1s\" end=\"2s\" region=\"root\">root</p>"
                    + "<p begin=\"2s\" end=\"3s\" region=\"bad\">bad</p>"
                    + "</div></body></tt>");
            var px = document.cues().get(0).text();
            assertEquals(7, px.alignment());
            assertEquals(0.1, px.x(), 1e-9);
            assertEquals(0.1, px.y(), 1e-9);
            var root = document.cues().get(1).text();
            assertEquals(2, root.alignment());
            assertEquals(0.35, root.x(), 1e-9);
            assertEquals(0.3, root.y(), 1e-9);
            var bad = document.cues().get(2).text();
            assertFalse(bad.hasPosition(), "an origin and extent not understood are the whole frame");
            assertEquals(7, bad.alignment());
        }

        @Test
        @DisplayName("ignores pixel lengths without a root extent")
        void pixelsWithoutExtent() {
            var document = parse("<tt " + NAMESPACES + "><head><layout><region xml:id=\"r\" tts:origin=\"64px 48px\""
                    + " tts:extent=\"50% 50%\" tts:textAlign=\"end\"/></layout></head><body><div>"
                    + "<p begin=\"0s\" end=\"1s\" region=\"r\"><span tts:fontSize=\"24px\">a</span></p></div></body></tt>");
            var text = document.cues().getFirst().text();
            assertEquals(0.5, text.x(), 1e-9);
            assertEquals(24, run(document, "a").size(), 1e-9);
        }

        @Test
        @DisplayName("converts every font size unit to reference pixels")
        void fontSizes() {
            var document = body("", "<p begin=\"0s\" end=\"1s\">"
                    + "<span tts:fontSize=\"2em\">em</span> <span tts:fontSize=\"10rh\">rh</span>"
                    + " <span tts:fontSize=\"10rw\">rw</span> <span tts:fontSize=\"2c\">c</span>"
                    + " <span tts:fontSize=\"1em 3c\">pair</span></p>"
                    + "<p begin=\"1s\" end=\"2s\"><span tts:fontSize=\"big\">bad</span></p>"
                    + "<p begin=\"2s\" end=\"3s\"><span tts:fontSize=\"-50%\">negative</span></p>");
            assertEquals(38.4, run(document, "em").size(), 1e-9);
            assertEquals(28.8, run(document, "rh").size(), 1e-9);
            assertEquals(51.2, run(document, "rw").size(), 1e-9);
            assertEquals(38.4, run(document, "c").size(), 1e-9);
            assertEquals(57.6, run(document, "pair").size(), 1e-9);
            assertTrue(Double.isNaN(run(document, "bad").size()));
            assertTrue(Double.isNaN(run(document, "negative").size()));
            var pixels = parse("<tt " + NAMESPACES + " tts:extent=\"640px 480px\"><body><div><p begin=\"0s\" end=\"1s\">"
                    + "<span tts:fontSize=\"24px\">a</span></p></div></body></tt>");
            assertEquals(14.4, run(pixels, "a").size(), 1e-9);
        }
    }

    @Nested
    @DisplayName("styles")
    class Styles {
        @ParameterizedTest(name = "textAlign {0}, displayAlign {1}")
        @CsvSource({"right,after,3", "end,center,6", "justify,justify,7", "bogus,bogus,7", "start,center,4",
                "left,before,7", "center,after,0"})
        @DisplayName("maps the alignment keywords to a numpad alignment")
        void alignment(String text, String display, int alignment) {
            var document = body("", "<p begin=\"0s\" end=\"1s\" tts:textAlign=\"" + text + "\" tts:displayAlign=\""
                    + display + "\">a</p>");
            assertEquals(alignment, document.cues().getFirst().text().alignment());
        }

        @Test
        @DisplayName("applies text decoration tokens in order, oblique as italic, and keeps colors it cannot read")
        void decorations() {
            var document = body("", "<p begin=\"0s\" end=\"1s\" tts:color=\"blue\">"
                    + "<span tts:textDecoration=\"underline lineThrough none\">none</span>"
                    + " <span tts:textDecoration=\"underline noUnderline lineThrough noLineThrough overline\""
                    + " tts:color=\"lime\">off</span>"
                    + " <span tts:fontStyle=\"oblique\" tts:color=\"not-a-color\">oblique</span>"
                    + " <span tts:backgroundColor=\"transparent\" tts:color=\"red\">clear</span>"
                    + " <span tts:backgroundColor=\"nonsense\" tts:color=\"yellow\">unknown</span></p>");
            assertEquals(0, run(document, "none").style());
            assertEquals(0, run(document, "off").style());
            assertEquals(StyledText.ITALIC, run(document, "oblique").style());
            assertEquals(0xFF0000FFL, run(document, "oblique").color());
            assertEquals(StyledText.NO_COLOR, run(document, "clear").background());
            assertEquals(StyledText.NO_COLOR, run(document, "unknown").background());
        }

        @Test
        @DisplayName("takes the first named font family")
        void fontFamilies() {
            var cases = new java.util.LinkedHashMap<String, String>();
            cases.put("default, &quot;My Font&quot;", "My Font");
            cases.put("serif", "");
            cases.put(" , Arial", "Arial");
            cases.put("'Quoted'", "Quoted");
            cases.put("monospaceSerif, proportionalSerif", "");
            for (var entry : cases.entrySet()) {
                var document = body("", "<p begin=\"0s\" end=\"1s\"><span tts:fontFamily=\"" + entry.getKey()
                        + "\" tts:fontWeight=\"bold\">a</span></p>");
                assertEquals(entry.getValue(), run(document, "a").font(), entry.getKey());
            }
        }

        @Test
        @DisplayName("ignores style references that loop or name nothing")
        void references() {
            var document = parse("<tt " + NAMESPACES + "><head><styling>"
                    + "<style xml:id=\"a\" style=\"b missing\" tts:fontWeight=\"bold\"/>"
                    + "<style xml:id=\"b\" style=\"a\" tts:fontStyle=\"italic\"/>"
                    + "<style xml:id=\"self\" style=\"self\" tts:color=\"red\"/>"
                    + "<other xml:id=\"x\"/>"
                    + "</styling><metadata><ttm:title>T</ttm:title><ttm:title>second</ttm:title><ttm:desc>d</ttm:desc>"
                    + "</metadata></head><body><div>"
                    + "<p begin=\"0s\" end=\"1s\" style=\"a\">loop</p><p begin=\"1s\" end=\"2s\" style=\"self\">self</p>"
                    + "</div></body></tt>");
            assertEquals("T", document.title());
            assertEquals(StyledText.BOLD | StyledText.ITALIC, run(document, "loop").style());
            assertEquals(0xFFFF0000L, run(document, "self").color());
        }

        @Test
        @DisplayName("reads the styling and parameter vocabularies of both TTML drafts")
        void drafts() {
            for (var draft : List.of("http://www.w3.org/2006/10/ttaf1", "http://www.w3.org/2006/04/ttaf1")) {
                for (var styling : List.of("#styling", "#style")) {
                    var document = parse("<tt xmlns=\"" + draft + "\" xmlns:s=\"" + draft + styling + "\" xmlns:p=\""
                            + draft + "#parameter\" p:frameRate=\"10\"><body><div><p begin=\"10f\" end=\"20f\">"
                            + "<span s:fontWeight=\"bold\">x</span></p></div></body></tt>");
                    assertEquals(List.of("1000000-2000000:x"), timeline(document), draft + styling);
                    assertEquals(StyledText.BOLD, run(document, "x").style(), draft + styling);
                }
            }
            var foreign = parse("<tt " + NAMESPACES + " xmlns:o=\"urn:other\" o:frameRate=\"10\"><o:head/><body><div>"
                    + "<p begin=\"30f\" end=\"60f\" o:x=\"1\">a<o:span>hidden</o:span></p><o:p>b</o:p></div></body></tt>");
            assertEquals(List.of("1000000-2000000:a"), timeline(foreign));
        }
    }

    @Nested
    @DisplayName("documents")
    class Documents {
        @Test
        @DisplayName("decodes the encoding of the XML declaration in single quotes, and UTF-8 for an unknown one")
        void encodings() {
            var latin = ("<?xml version='1.0' encoding='ISO-8859-1'?><tt " + NAMESPACES + "><body><div>"
                    + "<p begin=\"0s\" end=\"1s\">caf\u00E9</p></div></body></tt>").getBytes(StandardCharsets.ISO_8859_1);
            assertEquals("caf\u00E9", TtmlDocument.parse(latin).cues().getFirst().text().plain());
            var unknown = ("<?xml version=\"1.0\" encoding=\"x-not-a-charset\"?><tt " + NAMESPACES + "><body><div>"
                    + "<p begin=\"0s\" end=\"1s\">caf\u00E9</p></div></body></tt>").getBytes(StandardCharsets.UTF_8);
            assertEquals("caf\u00E9", TtmlDocument.parse(unknown).cues().getFirst().text().plain());
            var unquoted = ("<?xml version=\"1.0\" encoding=UTF-8?><tt " + NAMESPACES + "><body><div>"
                    + "<p begin=\"0s\" end=\"1s\">x</p></div></body></tt>").getBytes(StandardCharsets.UTF_8);
            assertEquals(TtmlDocument.decode(unquoted), new String(unquoted, StandardCharsets.UTF_8));
            var open = "<?xml encoding=\"UTF-8".getBytes(StandardCharsets.UTF_8);
            assertEquals("<?xml encoding=\"UTF-8", TtmlDocument.decode(open));
            var bom = new byte[]{(byte) 0xFF, (byte) 0xFE, '<', 0, 't', 0};
            assertEquals("<t", TtmlDocument.decode(bom));
        }

        @Test
        @DisplayName("recognizes the root element after comments and processing instructions only")
        void signatures() {
            assertTrue(TtmlDocument.isSignature("<!-- a --><?pi x?> <tt>"));
            assertTrue(TtmlDocument.isSignature("<x:tt/>"));
            assertFalse(TtmlDocument.isSignature("<!-- unterminated"));
            assertFalse(TtmlDocument.isSignature("<?xml version=\"1.0\""));
            assertFalse(TtmlDocument.isSignature("text <tt>"));
            assertFalse(TtmlDocument.isSignature(""));
            assertFalse(TtmlDocument.isSignature("<ttx>"));
        }
    }
}
