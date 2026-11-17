package com.github.auties00.smalt.video.mpeg2;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.mpeg2.Mpeg2TestStreams.Split;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the decoder sample for sample with the ISO/IEC 13818-5 reference decoder built with Smalt's inverse DCT
 * ({@code tools/testdata/mpeg2-oracle}), the one step the standards leave to the implementation, so that every other
 * step of the decoding process (variable length decoding, inverse quantization with its saturation and mismatch
 * control, motion vector reconstruction, every prediction mode, field and frame structures, the output order) must
 * agree exactly. The streams come from ffmpeg's MPEG-1 and MPEG-2 encoders and from the ISO/IEC 13818-5 reference
 * encoder, whose parameter file reaches field pictures, dual-prime prediction, 4:2:2 and 4:4:4 chroma, 11-bit intra
 * DC precision and repeated fields. Each stream is also decoded cut into other packets and on one thread, which must
 * not change a sample.
 */
@DisplayName("MPEG video decoder against the ISO/IEC 13818-5 reference decoder")
class Mpeg2ReferenceDecoderTest {
    static final Tolerance EXACT = Tolerance.exact();

    /** Decodes a stream every way and compares each decode with the reference decoder. */
    static Mpeg2TestStreams.Decoded check(String name, byte[] stream, Codec codec) {
        var expected = Mpeg2TestStreams.reference(stream, false);
        var decoded = Mpeg2TestStreams.decodeElementary(stream, codec, Split.PICTURES, VideoDecoderOptions.defaults());
        assertTrue(decoded.failures().isEmpty(), name + " decodes without errors: " + decoded.failures());
        assertTrue(decoded.count() > 0, name + " has frames");
        var frameSize = decoded.first().raw().length;
        var layout = Mpeg2TestStreams.referenceLayout(decoded);
        assertEquals(expected.length, layout.length, name + ": decoded size after " + decoded.count() + " frames");
        EXACT.assertFrames(name + " against the reference decoder", expected, layout,
                layout.length / decoded.count());
        var whole = Mpeg2TestStreams.decodeElementary(stream, codec, Split.WHOLE, VideoDecoderOptions.defaults());
        assertArrayEquals(decoded.raw(), whole.raw(), name + " in one packet");
        var triples = Mpeg2TestStreams.decodeElementary(stream, codec, Split.TRIPLES,
                VideoDecoderOptions.builder().threads(1).build());
        assertArrayEquals(decoded.raw(), triples.raw(), name + " three pictures per packet on one thread");
        assertEquals(frameSize * decoded.count(), decoded.raw().length, name + " frame sizes");
        return decoded;
    }

    @Nested
    @DisplayName("ffmpeg's encoders")
    class Ffmpeg {
        record Case(String name, String size, String codec, String options, double seconds) {
            @Override
            public String toString() {
                return name;
            }
        }

        static Stream<Case> cases() {
            return Stream.of(
                    new Case("I, P and B frames", "352x288", "mpeg2video", "-g 12 -bf 2 -q:v 4", 1.0),
                    new Case("intra only", "352x288", "mpeg2video", "-g 1 -q:v 2", 0.3),
                    new Case("coarse quantizer, skipped macroblocks", "352x288", "mpeg2video", "-g 15 -bf 2 -q:v 20",
                            1.0),
                    new Case("interlaced field DCT and field prediction", "720x576", "mpeg2video",
                            "-flags +ildct+ilme -top 1 -bf 2 -q:v 5", 0.4),
                    new Case("interlaced bottom field first", "720x576", "mpeg2video",
                            "-flags +ildct+ilme -top 0 -bf 1 -q:v 5", 0.3),
                    new Case("alternate scan", "352x288", "mpeg2video",
                            "-flags +ildct+ilme -alternate_scan 1 -bf 2 -q:v 4", 0.6),
                    new Case("intra VLC table and non-linear quantizer", "352x288", "mpeg2video",
                            "-intra_vlc 1 -non_linear_quant 1 -qmax 28 -bf 2 -q:v 6", 0.6),
                    new Case("10-bit intra DC", "352x288", "mpeg2video", "-dc 10 -bf 2 -q:v 3", 0.6),
                    new Case("4:2:2", "352x288", "mpeg2video", "-pix_fmt yuv422p -bf 2 -q:v 4", 0.6),
                    new Case("4:2:2 interlaced", "720x576", "mpeg2video",
                            "-pix_fmt yuv422p -flags +ildct+ilme -bf 2 -q:v 4", 0.3),
                    new Case("downloaded quantizer matrices", "352x288", "mpeg2video",
                            "-intra_matrix 8,20,20,24,24,24,26,26,26,26,28,28,28,28,28,30,30,30,30,30,30,32,32,32,32,"
                                    + "32,32,32,34,34,34,34,34,34,34,34,36,36,36,36,36,36,36,38,38,38,38,38,38,40,40,40,"
                                    + "40,40,42,42,42,42,44,44,44,46,46,48 -inter_matrix 16,17,18,19,20,21,22,23,17,18,"
                                    + "19,20,21,22,23,24,18,19,20,21,22,23,24,25,19,20,21,22,23,24,26,27,20,21,22,23,25,"
                                    + "26,27,28,21,22,23,24,26,27,28,30,22,23,24,26,27,28,30,31,23,24,25,27,28,30,31,33"
                                    + " -bf 2 -q:v 4", 0.6),
                    new Case("odd size", "202x118", "mpeg2video", "-bf 2 -q:v 4", 0.6),
                    new Case("tall picture with slice vertical position extensions", "64x2912", "mpeg2video",
                            "-bf 0 -g 4 -q:v 6 -strict -2", 0.2),
                    new Case("MPEG-1 with B frames", "352x288", "mpeg1video", "-bf 2 -q:v 4", 1.0),
                    new Case("MPEG-1 coarse quantizer", "352x240", "mpeg1video", "-bf 2 -q:v 25 -g 18", 1.0),
                    new Case("MPEG-1 intra only", "176x144", "mpeg1video", "-g 1 -q:v 1", 0.3)
            );
        }

        static byte[] encode(Case c) {
            var output = new ArrayList<>(List.of("-t", Double.toString(c.seconds()), "-c:v", c.codec()));
            output.addAll(List.of(c.options().split(" ")));
            output.addAll(List.of("-f", c.codec().equals("mpeg1video") ? "mpeg1video" : "mpeg2video"));
            return Mpeg2TestStreams.read(Fixtures.ffmpeg(c.codec().equals("mpeg1video") ? "m1v" : "m2v",
                    List.of("-f", "lavfi", "-i", "testsrc2=size=" + c.size() + ":rate=25"), output));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        @DisplayName("decodes every sample as the reference decoder does")
        void matchesReference(Case c) {
            var codec = c.codec().equals("mpeg1video") ? Codec.MPEG1_VIDEO : Codec.MPEG2_VIDEO;
            check(c.name(), encode(c), codec);
        }
    }

    @Nested
    @DisplayName("the ISO/IEC 13818-5 reference encoder")
    class Reference {
        static Stream<ReferenceEncoder.Params> cases() {
            var list = new ArrayList<ReferenceEncoder.Params>();
            var p = new ReferenceEncoder.Params("frame pictures with field and frame prediction");
            list.add(p);
            p = new ReferenceEncoder.Params("field pictures with B-pictures");
            p.fieldPictures = true;
            list.add(p);
            p = new ReferenceEncoder.Params("field pictures, bottom field first");
            p.fieldPictures = true;
            p.topFirst = false;
            list.add(p);
            p = new ReferenceEncoder.Params("dual-prime frame pictures");
            p.distance = 1;
            list.add(p);
            p = new ReferenceEncoder.Params("dual-prime field pictures");
            p.distance = 1;
            p.fieldPictures = true;
            list.add(p);
            p = new ReferenceEncoder.Params("low delay");
            p.distance = 1;
            p.lowDelay = true;
            p.profile = 5;
            list.add(p);
            p = new ReferenceEncoder.Params("4:2:2 frame pictures");
            p.chroma = 2;
            p.profile = 1;
            list.add(p);
            p = new ReferenceEncoder.Params("4:2:2 field pictures");
            p.chroma = 2;
            p.profile = 1;
            p.fieldPictures = true;
            list.add(p);
            p = new ReferenceEncoder.Params("4:2:2 dual-prime");
            p.chroma = 2;
            p.profile = 1;
            p.distance = 1;
            list.add(p);
            p = new ReferenceEncoder.Params("4:4:4");
            p.chroma = 3;
            p.profile = 8;
            list.add(p);
            p = new ReferenceEncoder.Params("4:4:4 field pictures");
            p.chroma = 3;
            p.profile = 8;
            p.fieldPictures = true;
            list.add(p);
            p = new ReferenceEncoder.Params("11-bit DC, alternate scan, intra VLC table, linear quantizer");
            p.dcPrecision = 3;
            p.profile = 1;
            p.alternateScan = new int[]{1, 1, 1};
            p.intraVlc = new int[]{1, 1, 1};
            p.qScaleType = new int[]{0, 0, 0};
            list.add(p);
            p = new ReferenceEncoder.Params("9-bit DC, frame prediction and frame DCT only");
            p.dcPrecision = 1;
            p.framePredFrameDct = new int[]{1, 1, 1};
            list.add(p);
            p = new ReferenceEncoder.Params("progressive sequence, every frame shown three times");
            p.progressiveSequence = true;
            p.progressiveFrame = true;
            p.repeatFirst = true;
            p.topFirst = true;
            p.frameRateCode = 7;
            p.profile = 8;
            p.framePredFrameDct = new int[]{1, 1, 1};
            list.add(p);
            p = new ReferenceEncoder.Params("progressive frames shown as three fields");
            p.progressiveFrame = true;
            p.repeatFirst = true;
            p.frameRateCode = 4;
            list.add(p);
            p = new ReferenceEncoder.Params("downloaded quantizer matrices");
            p.intraMatrix = matrix(8, 3);
            p.interMatrix = matrix(16, 1);
            list.add(p);
            p = new ReferenceEncoder.Params("MPEG-1");
            p.mpeg1 = true;
            p.progressiveSequence = true;
            p.progressiveFrame = true;
            p.framePredFrameDct = new int[]{1, 1, 1};
            p.intraVlc = new int[]{0, 0, 0};
            p.qScaleType = new int[]{0, 0, 0};
            p.vbv = 20;
            p.bitRate = 1_150_000;
            list.add(p);
            return list.stream();
        }

        static int[] matrix(int first, int step) {
            var m = new int[64];
            for (var i = 0; i < 64; i++) {
                m[i] = i == 0 ? first : Math.min(255, first + step * ((i >> 3) + (i & 7)) + (i % 5));
            }
            return m;
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        @DisplayName("decodes every sample as the reference decoder does")
        void matchesReference(ReferenceEncoder.Params p) {
            var stream = ReferenceEncoder.encode(p);
            var decoded = check(p.name, stream, p.mpeg1 ? Codec.MPEG1_VIDEO : Codec.MPEG2_VIDEO);
            assertEquals(p.frames, decoded.count(), p.name + " frame count");
        }
    }
}
