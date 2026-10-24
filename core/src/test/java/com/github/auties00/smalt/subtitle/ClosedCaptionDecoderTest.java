package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.testkit.subtitle.CaptionScript;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes CEA-608 and CEA-708 caption scripts through {@link SubtitleDecoder}: the 47 CFR 15.119 styles, attributes,
 * characters and channels, and the CEA-708 windows, pen attributes and code sets. Frames are 1001/30000 s long, the
 * caption data rides one packet per frame.
 */
@DisplayName("closed caption decoding")
class ClosedCaptionDecoderTest {
    private static final Rational FRAME = new Rational(1001, 30000);

    static DataTrack track(Codec codec) {
        return DataTrack.builder().id(1).codec(codec).timeBase(FRAME).build();
    }

    static List<SubtitleCue> decode(Codec codec, CaptionService service, byte[][] frames) {
        var decoder = new SubtitleDecoder(track(codec), service);
        var cues = new ArrayList<SubtitleCue>();
        for (var frame = 0; frame < frames.length; frame++) {
            cues.addAll(decoder.decodeAll(new Packet(1, frames[frame], frame, frame, 1, true)));
        }
        cues.addAll(decoder.flush());
        return cues;
    }

    static long micros(int frame) {
        return FRAME.toMicros(frame);
    }

    static String plain(SubtitleCue cue) {
        return Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP);
    }

    @Nested
    @DisplayName("CEA-608")
    class Cea608 {
        @Test
        @DisplayName("shows a pop-on caption from End of Caption to Erase Displayed Memory, italics as a tag")
        void popOn() {
            var script = new CaptionScript().command(28, 1, 0x20).command(30, 1, 0x2E)
                    .preamble(32, 1, 15, 0x10 | 1 << 1).text(34, 1, "Hello pop-on").control(42, 1, 0x11, 0x2E)
                    .text(44, 1, "italic").command(62, 1, 0x2F).command(120, 1, 0x2C);
            var cues = decode(Codec.CEA_608, CaptionService.cea608(1), script.build(150, 2));
            assertEquals(1, cues.size(), cues.toString());
            var cue = cues.getFirst();
            assertEquals(micros(62), cue.startMicros());
            assertEquals(micros(120), cue.endMicros());
            assertEquals("Hello pop-on <i>italic</i>", cue.text());
        }

        @Test
        @DisplayName("rolls up rows at carriage returns, each line complete in its cue")
        void rollUp() {
            var script = new CaptionScript().command(150, 1, 0x25).preamble(152, 1, 15, 0).text(154, 1, "Roll one")
                    .command(170, 1, 0x2D).text(172, 1, "Roll two").control(180, 1, 0x11, 0x28).text(182, 1, "red")
                    .command(200, 1, 0x2D).command(240, 1, 0x2C);
            var cues = decode(Codec.CEA_608, CaptionService.cea608(1), script.build(260, 2));
            assertEquals(List.of("Roll one", "Roll one\nRoll two red", "Roll two red"),
                    cues.stream().map(ClosedCaptionDecoderTest::plain).toList());
            assertEquals(micros(154), cues.get(0).startMicros());
            assertEquals(micros(170), cues.get(0).endMicros());
            assertEquals(micros(170), cues.get(1).startMicros());
            assertEquals(micros(200), cues.get(1).endMicros());
            assertEquals(micros(240), cues.get(2).endMicros());
            assertTrue(cues.get(1).text().contains("<font color=\"#ff0000\">red</font>"), cues.get(1).text());
        }

        @Test
        @DisplayName("paints on directly, with the special and extended characters")
        void paintOn() {
            var script = new CaptionScript().command(260, 1, 0x29).preamble(262, 1, 2, 0x10 | 2 << 1)
                    .text(264, 1, "Paint ").control(268, 1, 0x11, 0x37).text(270, 1, "e")
                    .control(271, 1, 0x12, 0x30).command(300, 1, 0x2C);
            var cues = decode(Codec.CEA_608, CaptionService.cea608(1), script.build(320, 2));
            assertEquals(1, cues.size(), cues.toString());
            assertEquals("Paint \u266A\u00C0", plain(cues.getFirst()));
            assertEquals("{\\an8}Paint \u266A\u00C0", cues.getFirst().text(), "row 2 is in the upper half");
            assertEquals(micros(300), cues.getFirst().endMicros());
        }

        @Test
        @DisplayName("keeps the channels apart: CC1 and CC2 in field 1, CC3 in field 2")
        void channels() {
            var script = new CaptionScript()
                    .command(1, 1, 0x20).preamble(1, 1, 15, 0).text(1, 1, "one").command(1, 1, 0x2F)
                    .command(20, 2, 0x20).preamble(20, 2, 15, 0).text(20, 2, "two").command(20, 2, 0x2F)
                    .command(1, 3, 0x20).preamble(1, 3, 15, 0).text(1, 3, "three").command(1, 3, 0x2F)
                    .command(60, 1, 0x2C).command(60, 2, 0x2C).command(60, 3, 0x2C);
            var frames = script.build(90, 2);
            assertEquals(List.of("one"), decode(Codec.CEA_608, CaptionService.cea608(1), frames).stream()
                    .map(ClosedCaptionDecoderTest::plain).toList());
            assertEquals(List.of("two"), decode(Codec.CEA_608, CaptionService.cea608(2), frames).stream()
                    .map(ClosedCaptionDecoderTest::plain).toList());
            assertEquals(List.of("three"), decode(Codec.CEA_608, CaptionService.cea608(3), frames).stream()
                    .map(ClosedCaptionDecoderTest::plain).toList());
            assertEquals(List.of(), decode(Codec.CEA_608, CaptionService.cea608(4), frames));
        }

        @Test
        @DisplayName("acts on a control code once when it is repeated, and twice when it comes again later")
        void redundancy() {
            var once = new CaptionScript().command(1, 1, 0x20).preamble(2, 1, 15, 0).text(4, 1, "x")
                    .command(10, 1, 0x2F).command(40, 1, 0x2C);
            assertEquals(1, decode(Codec.CEA_608, CaptionService.cea608(1), once.build(60, 2)).size());
            var twice = new CaptionScript().command(1, 1, 0x20).preamble(2, 1, 15, 0).text(4, 1, "x")
                    .command(10, 1, 0x2F).command(20, 1, 0x2F).command(40, 1, 0x2C);
            var cues = decode(Codec.CEA_608, CaptionService.cea608(1), twice.build(60, 2));
            assertEquals(1, cues.size(), "the second flip hides the caption");
            assertEquals(micros(20), cues.getFirst().endMicros());
        }

        @Test
        @DisplayName("edits rows: backspace, delete to end of row and tab offsets")
        void editing() {
            var script = new CaptionScript().command(1, 1, 0x20).preamble(2, 1, 15, 0).text(4, 1, "abcdx")
                    .command(10, 1, 0x21).text(12, 1, "e").control(14, 1, 0x17, 0x22).text(16, 1, "f")
                    .preamble(18, 1, 14, 0).text(20, 1, "keep cut").preamble(26, 1, 14, 0x10 | 1 << 1)
                    .command(28, 1, 0x24).command(30, 1, 0x2F).command(60, 1, 0x2C);
            var cues = decode(Codec.CEA_608, CaptionService.cea608(1), script.build(80, 2));
            assertEquals(List.of("keep\nabcde  f"), cues.stream().map(ClosedCaptionDecoderTest::plain).toList());
        }

        @Test
        @DisplayName("ignores pairs that fail parity and the extended data services of field 2")
        void rejection() {
            var frames = new CaptionScript().command(1, 3, 0x20).preamble(2, 3, 15, 0).text(4, 3, "ok")
                    .pair(6, 3, 0x01, 0x05).pair(7, 3, 'X', 'Y').pair(8, 3, 0x0F, 0x1A).pair(9, 3, 'Z', 'Z')
                    .command(10, 3, 0x2F).command(40, 3, 0x2C).build(60, 2);
            frames[9][4] ^= (byte) 0x80;
            frames[9][5] ^= (byte) 0x80;
            var cues = decode(Codec.CEA_608, CaptionService.cea608(3), frames);
            assertEquals(List.of("ok"), cues.stream().map(ClosedCaptionDecoderTest::plain).toList());
        }

        @Test
        @DisplayName("reads CC1 from a CEA-708 track by default, beside the DTVCC data")
        void defaultChannel() {
            var script = new CaptionScript().command(1, 1, 0x20).preamble(2, 1, 15, 0).text(4, 1, "compat")
                    .command(10, 1, 0x2F).command(40, 1, 0x2C);
            var track = track(Codec.CEA_708);
            assertEquals(CaptionService.cea608(1), CaptionService.defaultFor(track));
            var decoder = new SubtitleDecoder(track);
            var cues = new ArrayList<SubtitleCue>();
            var frames = script.build(60, 20);
            for (var frame = 0; frame < frames.length; frame++) {
                cues.addAll(decoder.decodeAll(new Packet(1, frames[frame], frame, frame, 1, true)));
            }
            assertEquals(List.of("compat"), cues.stream().map(ClosedCaptionDecoderTest::plain).toList());
            assertThrows(IllegalStateException.class, () -> decoder.decode(new Packet(1, frames[0], 0, 0, 1, true)));
        }
    }

    @Nested
    @DisplayName("CEA-708")
    class Cea708 {
        static byte[] bytes(int... values) {
            var result = new byte[values.length];
            for (var index = 0; index < values.length; index++) {
                result[index] = (byte) values[index];
            }
            return result;
        }

        static byte[] concat(byte[]... parts) {
            var out = new java.io.ByteArrayOutputStream();
            for (var part : parts) {
                out.writeBytes(part);
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("shows the text of a visible window, with italics, a carriage return and a G2 character")
        void window() {
            var define = bytes(0x98, 0x38, 0x4A, 0x00, 0x02, 0x1F, 0x00);
            var script = new CaptionScript()
                    .service(5, 1, concat(define, bytes(0x90, 0, 0), "Seven oh".getBytes(), bytes(0x0D, 0x90, 0, 0x80),
                            "italic".getBytes()))
                    .service(30, 1, concat(bytes(0x88, 0x01, 0x92, 0, 0), "Next".getBytes(), bytes(0x10, 0x35)))
                    .service(60, 1, bytes(0x8A, 0x01));
            var cues = decode(Codec.CEA_708, CaptionService.cea708(1), script.build(90, 20));
            assertEquals(2, cues.size(), cues.toString());
            assertEquals("Seven oh\n<i>italic</i>", cues.get(0).text());
            assertEquals(micros(5), cues.get(0).startMicros());
            assertEquals(micros(30), cues.get(0).endMicros());
            assertEquals("Next\u2022", plain(cues.get(1)));
            assertEquals(micros(60), cues.get(1).endMicros());
        }

        @Test
        @DisplayName("reads only its own service, through the extended service header too")
        void services() {
            var define = bytes(0x98, 0x38, 0x4A, 0x00, 0x00, 0x1F, 0x00);
            var script = new CaptionScript()
                    .service(5, 1, concat(define, "one".getBytes()))
                    .service(6, 2, concat(define, "two".getBytes()))
                    .service(7, 20, concat(define, "twenty".getBytes()))
                    .service(40, 1, bytes(0x8C, 0xFF)).service(40, 2, bytes(0x8C, 0xFF)).service(41, 20, bytes(0x8C, 0xFF));
            var frames = script.build(60, 20);
            assertEquals(List.of("one"), decode(Codec.CEA_708, CaptionService.cea708(1), frames).stream()
                    .map(ClosedCaptionDecoderTest::plain).toList());
            assertEquals(List.of("two"), decode(Codec.CEA_708, CaptionService.cea708(2), frames).stream()
                    .map(ClosedCaptionDecoderTest::plain).toList());
            assertEquals(List.of("twenty"), decode(Codec.CEA_708, CaptionService.cea708(20), frames).stream()
                    .map(ClosedCaptionDecoderTest::plain).toList());
        }

        @Test
        @DisplayName("shows a hidden window when a display windows command arrives, colored by the pen")
        void popOnWindow() {
            var hidden = bytes(0x99, 0x18, 0x00, 0x00, 0x00, 0x1F, 0x00);
            var script = new CaptionScript()
                    .service(3, 1, concat(hidden, bytes(0x91, 0x3C, 0x00, 0x00), "later".getBytes()))
                    .service(20, 1, bytes(0x89, 0x02))
                    .service(50, 1, bytes(0x8B, 0x02));
            var cues = decode(Codec.CEA_708, CaptionService.cea708(1), script.build(70, 20));
            assertEquals(1, cues.size(), cues.toString());
            assertEquals(micros(20), cues.getFirst().startMicros());
            assertEquals(micros(50), cues.getFirst().endMicros());
            assertEquals("{\\an8}<font color=\"#ffff00\">later</font>", cues.getFirst().text());
        }

        @Test
        @DisplayName("assembles caption channel packets split over several frames")
        void assembly() {
            var define = bytes(0x98, 0x38, 0x4A, 0x00, 0x00, 0x1F, 0x00);
            var script = new CaptionScript().service(1, 1, concat(define, "split across frames!".getBytes()))
                    .service(30, 1, bytes(0x8C, 0x01));
            var cues = decode(Codec.CEA_708, CaptionService.cea708(1), script.build(40, 4));
            assertEquals(List.of("split across frames!"), cues.stream().map(ClosedCaptionDecoderTest::plain).toList());
            assertFalse(cues.getFirst().startMicros() == micros(1), "the packet completes in a later frame");
        }
    }

    @Test
    @DisplayName("rejects a service the track cannot carry")
    void services() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubtitleDecoder(track(Codec.CEA_608), CaptionService.cea708(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SubtitleDecoder(track(Codec.CEA_708), CaptionService.teletext(0x888)));
        assertThrows(IllegalArgumentException.class, () -> CaptionService.cea608(5));
        assertThrows(IllegalArgumentException.class, () -> CaptionService.cea708(0));
        assertThrows(IllegalArgumentException.class, () -> CaptionService.teletext(0x900));
        assertThrows(IllegalArgumentException.class, () -> new SubtitleDecoder(track(Codec.CEA_608), null));
    }
}
