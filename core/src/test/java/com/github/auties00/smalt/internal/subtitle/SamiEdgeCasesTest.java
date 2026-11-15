package com.github.auties00.smalt.internal.subtitle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the SAMI files real authoring tools write loosely: unterminated style and title elements, selectors that
 * name no class, attributes quoted, unquoted, spaced and lookalike, SYNC elements without a start or past the body,
 * paragraphs without a class or an end tag, out-of-order SYNC times, and the HTML a paragraph may hold.
 */
@DisplayName("SAMI edge cases")
class SamiEdgeCasesTest {
    private static List<String> cues(SamiText.Parsed parsed) {
        return parsed.tracks().stream().flatMap(track -> track.cues().stream()
                .map(cue -> track.name() + "@" + cue.startMicros() / 1000 + "-" + cue.endMicros() / 1000 + ":" + cue.text()))
                .toList();
    }

    @Test
    @DisplayName("recognizes the SAMI tag only as a whole name")
    void signatures() {
        assertTrue(SamiText.isSignature("  <SAMI>"));
        assertTrue(SamiText.isSignature("<sami"));
        assertTrue(SamiText.isSignature("<Sami\n"));
        assertFalse(SamiText.isSignature("<SAMIX>"));
        assertFalse(SamiText.isSignature("<SAM"));
    }

    @Test
    @DisplayName("reads the declared charset and none for an unknown or missing one")
    void charsets() {
        assertEquals(StandardCharsets.ISO_8859_1, SamiText.declaredCharset(
                "<SAMI><HEAD><META charset=ISO-8859-1>".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(StandardCharsets.UTF_8, SamiText.declaredCharset(
                "<META content=\"text/html; charset=utf-8\">".getBytes(StandardCharsets.US_ASCII)));
        assertNull(SamiText.declaredCharset("<META charset=x-nothing>".getBytes(StandardCharsets.US_ASCII)));
        assertNull(SamiText.declaredCharset("<SAMI>".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("reads the classes of a loose style block and the title")
    void head() {
        var parsed = SamiText.parse("<SAMI><HEAD><TITLE>My <b>Title</b> &amp; more</TITLE><STYLE><!--\n"
                + "P { margin: 0 }\n.ENCC { Name: English; lang: en-US; nothing }\n. { lang: xx }\n"
                + ".bad name { lang: yy }\n.FRCC { lang: fr-FR }\n.ENCC { lang: dup }\n"
                + "--></STYLE></HEAD><BODY>"
                + "<SYNC Start=1000><P Class=ENCC>hello<P CLASS=\"FRCC\">bonjour"
                + "<SYNC Start=2000><P class=encc>&nbsp;<P class='frcc'>salut</P>"
                + "</BODY></SAMI>");
        assertEquals("My Title & more", parsed.title());
        assertEquals(2, parsed.tracks().size());
        assertEquals("en-US", parsed.tracks().get(0).language());
        assertEquals("English", parsed.tracks().get(0).title());
        assertEquals("fr-FR", parsed.tracks().get(1).language());
        assertEquals(List.of("ENCC@1000-2000:hello", "FRCC@1000-2000:bonjour", "FRCC@2000-6000:salut"), cues(parsed));
    }

    @Test
    @DisplayName("keeps reading after an unterminated style or title")
    void unterminated() {
        var style = SamiText.parse("<SAMI><STYLE>.ENCC { lang: en }<BODY><SYNC Start=0><P class=ENCC>a</SAMI>");
        assertEquals("en", style.tracks().getFirst().language());
        var title = SamiText.parse("<SAMI><TITLE>never closed<BODY><SYNC Start=0><P>a</SAMI>");
        assertEquals("", title.title());
        assertEquals(1, title.tracks().size());
    }

    @Test
    @DisplayName("skips SYNC elements without a usable start and stops at a cut tag")
    void syncs() {
        var parsed = SamiText.parse("<SAMI><BODY>"
                + "<SYNC>none<SYNC start>bare<SYNC xstart=5>lookalike<SYNC Start = \"x\">text<SYNC Start=-3>neg"
                + "<SYNC Start = '3000'>third<SYNC Start=1000>first<SYNC Start=1000>again"
                + "<SYNC Start=12345678901234567890>huge</BODY>after<SYNC Start=9000>past body<SYNC Start=10");
        var times = cues(parsed);
        assertTrue(times.contains("@1000-3000:first"), times.toString());
        assertTrue(times.contains("@1000-3000:again"), times.toString());
        assertTrue(times.contains("@3000-9000:third"), times.toString());
        assertTrue(times.contains("@9000-123456789012345:past body"), times.toString());
        assertTrue(times.stream().noneMatch(cue -> cue.contains("lookalike") || cue.contains("none")), times.toString());
        assertTrue(times.stream().noneMatch(cue -> cue.contains("after")), times.toString());
    }

    @Test
    @DisplayName("reads paragraphs without a class, without an end tag and lookalike tags")
    void paragraphs() {
        var parsed = SamiText.parse("<SAMI><BODY><SYNC Start=0>loose text<SYNC Start=1000><P>one</P><PARAM x>two"
                + "<SYNC Start=2000><P Class = ENCC>three<P class=ENCC tail=1>four</SAMI>");
        var all = cues(parsed);
        assertTrue(all.contains("@0-1000:loose text"), all.toString());
        assertTrue(all.contains("@1000-2000:one"), all.toString());
        assertTrue(all.stream().anyMatch(cue -> cue.startsWith("ENCC@2000") && cue.endsWith(":three")), all.toString());
        assertTrue(all.stream().anyMatch(cue -> cue.endsWith(":four")), all.toString());
    }

    @Test
    @DisplayName("converts paragraph HTML to SubRip text")
    void html() {
        assertEquals("a\nb\nc", SamiText.html("a <br>  b <BR/>c"));
        assertEquals("x <b>y</b> <i>z</i>", SamiText.html("x  <b>y</b> <I>z</I><span>"));
        assertEquals("<font color=\"red\">r</font> &x", SamiText.html("<font color=\"red\">r</font> &x"));
        assertEquals("a & b \u00A0c", SamiText.html("a &amp; b &NBSP;c"));
        assertEquals("cut", SamiText.html("cut <b"));
        assertEquals("", SamiText.html("   "));
        assertEquals("&toolongreference;", SamiText.html("&toolongreference;"));
    }

    @Test
    @DisplayName("reads a file without any cue as one empty track of its first class")
    void empty() {
        var parsed = SamiText.parse("<SAMI><STYLE>.KRCC { lang: ko }</STYLE><BODY></BODY></SAMI>");
        assertEquals(1, parsed.tracks().size());
        assertEquals("ko", parsed.tracks().getFirst().language());
        assertTrue(parsed.tracks().getFirst().cues().isEmpty());
        var none = SamiText.parse("<SAMI></SAMI>");
        assertEquals("", none.tracks().getFirst().name());
    }
}
