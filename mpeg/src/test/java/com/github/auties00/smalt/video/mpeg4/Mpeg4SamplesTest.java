package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.video.h263.H263Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes third-party samples from the FFmpeg FATE suite (a DivX-style packed bitstream in AVI, a
 * raw Simple Profile stream, an unsupported Studio Profile stream) and raw elementary streams
 * written by ffmpeg, comparing against ffmpeg's decode within the IDCT noise floor that the
 * differential tests use.
 */
@DisplayName("MPEG-4 Part 2 samples and elementary streams")
class Mpeg4SamplesTest {
    private static final String FATE = "https://fate-suite.ffmpeg.org/mpeg4/";

    static void assertWithinNoiseFloor(Path file, Mpeg4TestStreams.Decoded decoded) {
        var expected = Mpeg4TestStreams.ffmpeg(file, "int");
        var simple = Mpeg4TestStreams.ffmpeg(file, "simple");
        assertEquals(expected.length, decoded.raw().length, "decoded size after " + decoded.frames() + " frames");
        var ours = Quality.psnr(expected, decoded.raw());
        var floor = Quality.psnr(expected, simple);
        assertTrue(ours >= Math.min(floor, 60) - 1, "PSNR " + ours + " dB, ffmpeg -idct simple " + floor + " dB");
        for (var i = 1; i < decoded.timestamps().size(); i++) {
            assertTrue(decoded.timestamps().get(i) > decoded.timestamps().get(i - 1), "timestamps increase: "
                    + decoded.timestamps());
        }
    }

    @Nested
    @DisplayName("FATE samples")
    class Fate {
        @Test
        @DisplayName("decodes a packed bitstream with B-VOPs in AVI in display order")
        void packedBframes() {
            var file = Conformance.fetch("fate-mpeg4-packed_bframes.avi", FATE + "packed_bframes.avi",
                    "450a89377f024ce11d176d2be0fa3756df005aa45294866b383fee18a17cbf48");
            var decoded = Mpeg4TestStreams.decode(file, Mpeg4Decoder::new);
            assertEquals(544, decoded.width());
            assertEquals(352, decoded.height());
            // 16 coded pictures: the last packet packs a B-VOP behind a P-VOP, which ffmpeg drops.
            assertEquals(16, decoded.frames());
            assertWithinNoiseFloor(file, Mpeg4TestStreams.alignPacked(decoded, 15 * 544 * 352 * 3 / 2));
        }

        @Test
        @DisplayName("decodes a raw Simple Profile elementary stream")
        void demo() {
            var file = Conformance.fetch("fate-mpeg4-demo.m4v", FATE + "demo.m4v",
                    "da1983816306e0e4003da606e139896fe9394564df17fc9be1171f1f5c7e2441");
            var decoded = Mpeg4TestStreams.decodeElementary(file, Mpeg4Decoder::new);
            assertEquals(320, decoded.width());
            assertWithinNoiseFloor(file, decoded);
        }

        @Test
        @DisplayName("rejects the Studio Profile as unsupported")
        void studioProfile() {
            var file = Conformance.fetch("fate-mpeg4-mpeg4_sstp_dpcm.m4v", FATE + "mpeg4_sstp_dpcm.m4v",
                    "c7389f15ceb3381be18ab21c932ef3cda952466bcd2ade824faf36a880ea860e");
            var error = assertThrows(MediaException.Unsupported.class,
                    () -> Mpeg4TestStreams.decodeElementary(file, Mpeg4Decoder::new));
            assertEquals("mpeg-4 studio profile", error.format());
        }
    }

    @Nested
    @DisplayName("elementary streams")
    class Elementary {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"-bf 0", "-bf 2 -flags +qpel"})
        @DisplayName("splits and decodes raw MPEG-4 streams written by ffmpeg")
        void mpeg4(String options) {
            var output = new java.util.ArrayList<>(List.of("-t", "1", "-c:v", "mpeg4", "-q:v", "5", "-f", "m4v"));
            output.addAll(List.of(options.split(" ")));
            var file = Fixtures.ffmpeg("m4v", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25"), output);
            try (var source = MediaSource.of(file); var stream = Mpeg4ElementaryStream.open(source)) {
                assertEquals(Codec.MPEG4_PART2, stream.track().codec());
                assertTrue(stream.track().codecConfig().isPresent());
                var first = stream.read();
                assertTrue(first.keyframe());
                assertEquals(0, first.pts());
                var second = stream.read();
                assertFalse(second.keyframe());
            }
            var decoded = Mpeg4TestStreams.decodeElementary(file, Mpeg4Decoder::new);
            assertEquals(packetTimes(file), decoded.timestamps(), "the frames carry the packets' VOP times");
            assertWithinNoiseFloor(file, decoded);
        }

        /** Returns the presentation times of the packets of an elementary stream, sorted. */
        private static List<Long> packetTimes(Path file) {
            var times = new java.util.ArrayList<Long>();
            try (var source = MediaSource.of(file); var stream = Mpeg4ElementaryStream.open(source)) {
                for (var packet = stream.read(); packet != null; packet = stream.read()) {
                    assertTrue(packet.dts() <= packet.pts(), "decoded before presented");
                    times.add(packet.pts());
                }
            }
            times.sort(null);
            return times;
        }

        @Test
        @DisplayName("splits and decodes raw H.263 streams written by ffmpeg")
        void h263() {
            var file = Fixtures.ffmpeg("h263", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=15"),
                    List.of("-t", "1", "-c:v", "h263", "-q:v", "5", "-g", "6", "-f", "h263"));
            try (var source = MediaSource.of(file); var stream = Mpeg4ElementaryStream.open(source)) {
                assertEquals(Codec.H263, stream.track().codec());
                var keys = 0;
                var count = 0;
                for (var packet = stream.read(); packet != null; packet = stream.read()) {
                    count++;
                    keys += packet.keyframe() ? 1 : 0;
                }
                assertEquals(15, count);
                assertEquals(3, keys);
            }
            var decoded = Mpeg4TestStreams.decodeElementary(file, H263Decoder::new);
            assertEquals(packetTimes(file), decoded.timestamps(), "the frames carry the temporal references");
            assertWithinNoiseFloor(file, decoded);
        }

        @Test
        @DisplayName("rejects content that is not an elementary stream")
        void notElementary() {
            assertThrows(MediaException.Unsupported.class,
                    () -> Mpeg4ElementaryStream.open(MediaSource.of(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0})));
            assertFalse(Mpeg4ElementaryStream.matches(new byte[]{0, 0, 1, (byte) 0xB3}));
            assertTrue(Mpeg4ElementaryStream.matches(new byte[]{0, 0, 1, (byte) 0xB0}));
            assertTrue(Mpeg4ElementaryStream.matches(new byte[]{0, 0, (byte) 0x82, 0}));
        }
    }
}
