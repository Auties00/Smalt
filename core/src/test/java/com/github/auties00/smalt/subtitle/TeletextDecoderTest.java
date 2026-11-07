package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.testkit.subtitle.TeletextWriter;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes teletext subtitle pages written by the test kit's {@link TeletextWriter} through {@link SubtitleDecoder}:
 * page selection and transmission, boxed text, alpha colors, double height, the Latin national option sub-sets, the
 * X/26 characters with diacritical marks and the character set designations of ETSI EN 300 706.
 */
@DisplayName("teletext decoding")
class TeletextDecoderTest {
    private static final Rational TICKS = Rational.timeBaseOf(90_000);
    private static final int GERMAN = 0b001;

    static DataTrack track(String language, byte[] descriptor) {
        return DataTrack.builder().id(1).codec(Codec.DVB_TELETEXT).timeBase(TICKS).language(language)
                .codecConfig(descriptor).build();
    }

    static List<SubtitleCue> decode(DataTrack track, CaptionService service, byte[]... packets) {
        var decoder = service == null ? new SubtitleDecoder(track) : new SubtitleDecoder(track, service);
        var cues = new ArrayList<SubtitleCue>();
        for (var index = 0; index < packets.length; index++) {
            cues.addAll(decoder.decodeAll(new Packet(1, packets[index], index * 90_000L, index * 90_000L, 90_000,
                    true)));
        }
        cues.addAll(decoder.flush());
        return cues;
    }

    static List<String> plain(List<SubtitleCue> cues) {
        return cues.stream().map(cue -> Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP)).toList();
    }

    @Test
    @DisplayName("shows the boxed text of a subtitle page in its colors, with the German national characters")
    void subtitlePage() {
        var writer = new TeletextWriter().header(8, 0x88, true, true, false, GERMAN)
                .row(8, 20, TeletextWriter.codes(new int[]{0x0D, 0x0B, 0x0B, 0x02}, "Gr", 0x7D, 'n', 0x0A, 0x0A,
                        'x', 'y'))
                .row(8, 21, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "hidden by double height", 0x0A, 0x0A))
                .row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B, 0x03}, "Gelber Text", 0x0A, 0x0A))
                .row(8, 23, TeletextWriter.codes(new int[0], "not boxed"));
        var first = writer.packet();
        var erase = new TeletextWriter().header(8, 0x88, true, true, false, GERMAN).packet();
        var cues = decode(track("ger", null), CaptionService.teletext(0x888), first, erase);
        assertEquals(1, cues.size(), cues.toString());
        assertEquals("<font color=\"#00ff00\">Gr\u00FCn</font>\n<font color=\"#ffff00\">Gelber Text</font>",
                cues.getFirst().text());
        assertEquals(0, cues.getFirst().startMicros());
        assertEquals(1_000_000, cues.getFirst().endMicros());
    }

    @Test
    @DisplayName("replaces a page by its next version and ends the last at the end of the stream")
    void versions() {
        var one = new TeletextWriter().header(8, 0x88, true, true, false, 0)
                .row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "one", 0x0A)).packet();
        var two = new TeletextWriter().header(8, 0x88, true, true, false, 0)
                .row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "two", 0x0A)).packet();
        var other = new TeletextWriter().header(8, 0x77, true, true, false, 0)
                .row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "other page", 0x0A)).packet();
        var decoder = new SubtitleDecoder(track(null, new byte[]{'e', 'n', 'g', 0x02 << 3, (byte) 0x88}));
        var cues = new ArrayList<SubtitleCue>();
        cues.addAll(decoder.decodeAll(new Packet(1, one, 0, 0, 0, true)));
        cues.addAll(decoder.decodeAll(new Packet(1, other, 45_000, 45_000, 0, true)));
        cues.addAll(decoder.decodeAll(new Packet(1, two, 90_000, 90_000, 0, true)));
        cues.addAll(decoder.decodeAll(new Packet(1, new byte[]{0x10}, 180_000, 180_000, 45_000, true)));
        cues.addAll(decoder.flush());
        assertEquals(List.of("one", "two"), plain(cues));
        assertEquals(1_000_000, cues.get(0).endMicros());
        assertEquals(2_500_000, cues.get(1).endMicros());
    }

    @Test
    @DisplayName("composes characters with diacritical marks from X/26 packets")
    void enhancements() {
        var packet = new TeletextWriter().header(8, 0x88, true, true, false, 0)
                .row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "Accent e", 0x0A, 0x0A))
                .enhancements(8, 62 | 0x04 << 6, 9 | 0x12 << 6 | 'e' << 11, 10 | 0x0F << 6 | 0x24 << 11,
                        11 | 0x10 << 6 | 0x2A << 11)
                .row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "Accent e  ", 0x0A, 0x0A)).packet();
        var cues = decode(track(null, null), CaptionService.teletext(0x888), packet);
        assertEquals(List.of("Accent \u00E9$@"), plain(cues));
    }

    @Test
    @DisplayName("replaces the X/26 triplets of a page retransmitted without an erase")
    void retransmittedEnhancements() {
        var decoder = new SubtitleDecoder(track(null, null), CaptionService.teletext(0x300));
        var cues = new ArrayList<SubtitleCue>();
        for (var cycle = 0; cycle < 40; cycle++) {
            // The page cycles without C4; from cycle 20 on its X/26/0 packet puts a grave rather than an acute.
            var mark = cycle < 20 ? 0x12 : 0x11;
            var packet = new TeletextWriter().header(3, 0x00, cycle == 0, false, false, 0)
                    .row(3, 5, TeletextWriter.codes(new int[0], "Cafe"))
                    .enhancements(3, 45 | 0x04 << 6, 3 | mark << 6 | 'e' << 11).packet();
            var next = new TeletextWriter().header(3, 0x01, false, false, false, 0).packet();
            cues.addAll(decoder.decodeAll(new Packet(1, packet, cycle * 90_000L, cycle * 90_000L, 0, true)));
            cues.addAll(decoder.decodeAll(new Packet(1, next, cycle * 90_000L + 45_000, cycle * 90_000L + 45_000, 0,
                    true)));
        }
        cues.addAll(decoder.flush());
        assertEquals(List.of("Café", "Cafè"), plain(cues));
        assertEquals(20 * 1_000_000L, cues.get(1).startMicros());
    }

    @Test
    @DisplayName("drops an Arabic tail cell that follows no right-to-left letter")
    void strayTail() {
        var packet = new TeletextWriter().header(1, 0x50, true, true, false, 0b111)
                .row(1, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, new String(new char[]{'1', 0x26, '2'}), 0x0A,
                        0x0A)).packet();
        assertEquals(List.of("12"), plain(decode(track("ara", null), CaptionService.teletext(0x150), packet)));
    }

    @Test
    @DisplayName("selects the character set from an X/28/0 designation and from the track's language")
    void characterSets() {
        var cyrillic = new TeletextWriter().header(1, 0x50, true, true, false, 0b100).designation(1, 0x24)
                .row(1, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "Pdbtn", 0x0A, 0x0A)).packet();
        assertEquals(List.of("\u041F\u0434\u0431\u0442\u043D"),
                plain(decode(track(null, null), CaptionService.teletext(0x150), cyrillic)));
        var russian = new TeletextWriter().header(1, 0x50, true, true, false, 0b100)
                .row(1, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "Pdbtn", 0x0A, 0x0A)).packet();
        assertEquals(List.of("\u041F\u0434\u0431\u0442\u043D"),
                plain(decode(track("rus", null), CaptionService.teletext(0x150), russian)));
        assertEquals(List.of("Pdbtn"), plain(decode(track("eng", null), CaptionService.teletext(0x150), russian)));
        var greek = new TeletextWriter().header(1, 0x50, true, true, false, 0b111)
                .row(1, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "abc", 0x0A, 0x0A)).packet();
        assertEquals(List.of("\u03B1\u03B2\u03B3"), plain(decode(track("gre", null), CaptionService.teletext(0x150), greek)));
    }

    @Test
    @DisplayName("reads Arabic rows from right to left: letter forms, tails, lam-alef, digits and mirrored brackets")
    void arabic() {
        // Shown left to right: "(12) " then an isolated seen (tail cell, teeth cell), ra and dal; then meem,
        // lam-alef and an initial seen.
        var shown = new String(new char[]{0x29, '1', '2', 0x28, ' ', 0x26, 0x53, 0x51, 0x4F});
        var salam = new String(new char[]{0x7B, 0x7E, 0x53});
        var packet = new TeletextWriter().header(1, 0x50, true, true, false, 0b111)
                .row(1, 21, TeletextWriter.codes(new int[]{0x0B, 0x0B}, shown, 0x0A, 0x0A))
                .row(1, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, salam, 0x0A, 0x0A)).packet();
        assertEquals(List.of("درس (12)\nسلام"),
                plain(decode(track("ara", null), CaptionService.teletext(0x150), packet)));
        var designated = new TeletextWriter().header(1, 0x50, true, true, false, 0).designation(1, 0x57)
                .row(1, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, salam, 0x0A, 0x0A)).packet();
        assertEquals(List.of("سلام"),
                plain(decode(track(null, null), CaptionService.teletext(0x150), designated)));
    }

    @Test
    @DisplayName("takes Arabic G2 characters with a Latin G0 set, and reads Hebrew rows from right to left")
    void arabicSupplementAndHebrew() {
        // Rows 22 and 23 take G2 characters through X/26: an Arabic-Indic five and a peh.
        var digit = new TeletextWriter().header(8, 0x88, true, true, false, 0b000)
                .enhancements(8, 62 | 0x04 << 6, 5 | 0x0F << 6 | 0x35 << 11, 63 | 0x04 << 6,
                        3 | 0x0F << 6 | 0x2B << 11)
                .row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "No 5", 0x0A, 0x0A))
                .row(8, 23, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "ab", 0x0A, 0x0A)).packet();
        assertEquals(List.of("No ٥\nپa"),
                plain(decode(track("ara", null), CaptionService.teletext(0x888), digit)));
        var hebrew = new TeletextWriter().header(1, 0x50, true, true, false, 0b101)
                .row(1, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, new String(new char[]{0x6D, 0x65, 0x6C, 0x79}),
                        0x0A, 0x0A)).packet();
        assertEquals(List.of("שלום"),
                plain(decode(track("heb", null), CaptionService.teletext(0x150), hebrew)));
    }

    @Test
    @DisplayName("shows every row of a page that is not a subtitle page, but not mosaics or concealed text")
    void normalPage() {
        var packet = new TeletextWriter().header(2, 0x00, true, false, false, 0)
                .row(2, 1, TeletextWriter.codes(new int[0], "Top", 0x11, 0x7F, 0x7F, 0x07, '!'))
                .row(2, 2, TeletextWriter.codes(new int[]{0x18}, "secret", 0x07, "shown".charAt(0))).packet();
        var cues = decode(track(null, null), CaptionService.teletext(0x200), packet);
        assertEquals(List.of("Top    !\ns"), plain(cues));
        assertEquals("{\\an8}", cues.getFirst().text().substring(0, 6));
    }

    @Test
    @DisplayName("ends a page at the next header of any magazine in serial mode, and survives single bit errors")
    void serialMode() {
        var first = new TeletextWriter().header(3, 0x10, true, false, true, 0)
                .row(3, 5, TeletextWriter.codes(new int[0], "serial")).packet();
        first[7] ^= 0x01;
        var next = new TeletextWriter().header(4, 0x20, true, false, true, 0).packet();
        var decoder = new SubtitleDecoder(track(null, null), CaptionService.teletext(0x310));
        var cues = new ArrayList<SubtitleCue>(decoder.decodeAll(new Packet(1, first, 0, 0, 0, true)));
        assertEquals(List.of(), cues, "a page that is not a subtitle page is shown when its transmission ends");
        cues.addAll(decoder.decodeAll(new Packet(1, next, 9000, 9000, 0, true)));
        cues.addAll(decoder.flush());
        assertEquals(List.of("serial"), plain(cues));
        assertEquals(0, cues.getFirst().startMicros());
    }
}
