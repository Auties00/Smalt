package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes synthetic scenes with ffmpeg's MPEG-4 Part 2 encoder and with Xvid across the Simple and
 * Advanced Simple profile tools and compares the decoder's output with ffmpeg's decode using the
 * same inverse DCT family ({@code -idct int}). The standard leaves the inverse DCT free within the
 * IEEE 1180 accuracy, so samples may differ by one where the two integer transforms round
 * differently, and such differences drift slowly through the predictions; the tests therefore
 * require the decoder to stay at least as close to ffmpeg's {@code -idct int} decode as ffmpeg's
 * own {@code -idct simple} decode does (the IDCT noise floor), with the same frame count.
 */
@DisplayName("MPEG-4 Part 2 decoder against ffmpeg")
class Mpeg4DifferentialTest {
    /** Intra VOPs carry no prediction for rounding differences to drift through: one level, in under 0.1% of samples. */
    static final Tolerance INTRA = Tolerance.offByOne().and(Tolerance.differing(0.001));

    /**
     * Returns the rule of a stream with predictions: at least the PSNR against the {@code -idct int} decode that
     * ffmpeg's {@code -idct simple} decode reaches (the IDCT noise floor, capped at 60 dB) less 1 dB, and at least
     * 50 dB.
     */
    static Tolerance idctNoiseFloor(byte[] expected, byte[] simple) {
        var floor = Tolerance.Difference.of(expected, simple).psnr();
        return Tolerance.psnr(Math.max(50, Math.min(floor, 60) - 1));
    }

    record Case(String name, String size, String container, String codec, String options, double seconds) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("simple profile P-VOPs", "176x144", "mp4", "mpeg4", "-bf 0", 1.0),
                new Case("B-VOPs", "176x144", "mp4", "mpeg4", "-bf 2", 1.0),
                new Case("four motion vectors", "176x144", "mp4", "mpeg4", "-bf 0 -flags +mv4", 1.0),
                new Case("quarter sample", "176x144", "mp4", "mpeg4", "-bf 2 -flags +qpel+mv4", 1.0),
                new Case("MPEG quantization", "176x144", "mp4", "mpeg4", "-bf 1 -mpeg_quant 1", 1.0),
                new Case("odd size", "202x118", "mp4", "mpeg4", "-bf 2", 0.8),
                new Case("video packets", "352x288", "mp4", "mpeg4", "-bf 0 -ps 400", 0.5),
                new Case("data partitioning", "352x288", "mp4", "mpeg4", "-bf 0 -ps 400 -data_partitioning 1", 0.5),
                new Case("interlaced P-VOPs", "352x288", "mp4", "mpeg4", "-bf 0 -flags +ildct+ilme -alternate_scan 1", 0.4),
                // Every reference is an I-VOP, so no macroblock is in interlaced direct mode, which
                // ffmpeg's encoder does not produce faithfully (see Mpeg4InterlacedDirectTest).
                new Case("interlaced B-VOPs", "352x288", "mp4", "mpeg4",
                        "-bf 2 -g 3 -flags +ildct+ilme -alternate_scan 1", 0.6),
                // Frame prediction only, so the direct macroblocks of these B-VOPs scale frame
                // vectors of P-VOPs while their residuals use the field DCT.
                new Case("interlaced DCT B-VOPs", "352x288", "mp4", "mpeg4", "-bf 2 -flags +ildct -alternate_scan 1",
                        0.6),
                new Case("matroska", "176x144", "mkv", "mpeg4", "-bf 2", 1.0),
                new Case("avi with B-VOPs", "176x144", "avi", "mpeg4", "-bf 2", 1.0),
                new Case("xvid", "176x144", "mp4", "libxvid", "-bf 2", 1.0),
                new Case("xvid quarter sample", "176x144", "mp4", "libxvid", "-bf 2 -flags +qpel", 1.0),
                new Case("xvid global motion compensation", "176x144", "mp4", "libxvid", "-bf 0 -gmc 1", 1.0),
                new Case("xvid in matroska", "176x144", "mkv", "libxvid", "-bf 1", 1.0),
                new Case("xvid in avi", "176x144", "avi", "libxvid", "-bf 2", 1.0)
        );
    }

    static Mpeg4TestStreams.Decoded check(Case c) {
        var output = new ArrayList<>(List.of("-t", Double.toString(c.seconds()), "-c:v", c.codec(), "-q:v", "5"));
        output.addAll(List.of(c.options().split(" ")));
        var file = Fixtures.ffmpeg(c.container(), List.of("-f", "lavfi", "-i", "testsrc2=size=" + c.size() + ":rate=25"),
                output);
        var expected = Mpeg4TestStreams.ffmpeg(file, "int");
        var simple = Mpeg4TestStreams.ffmpeg(file, "simple");
        var decoded = Mpeg4TestStreams.decode(file, Mpeg4Decoder::new);
        if (c.container().equals("avi") && c.codec().equals("libxvid")) {
            // Xvid packs B-VOPs behind P-VOPs in AVI, and ffmpeg drops the last packed one.
            decoded = Mpeg4TestStreams.alignPacked(decoded, expected.length);
        }
        assertEquals(expected.length, decoded.raw().length, "decoded size of " + c.name() + " after "
                + decoded.frames() + " frames");
        idctNoiseFloor(expected, simple).assertWithin(c.name() + " against ffmpeg -idct int", expected, decoded.raw());
        for (var i = 1; i < decoded.timestamps().size(); i++) {
            assertTrue(decoded.timestamps().get(i) > decoded.timestamps().get(i - 1), "timestamps of " + c.name()
                    + " increase: " + decoded.timestamps());
        }
        return decoded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("decodes within the IDCT noise floor of ffmpeg's decode")
    void matchesFfmpeg(Case c) {
        check(c);
    }

    @Test
    @DisplayName("decodes intra VOPs to within one sample value of ffmpeg's integer IDCT")
    void intraOnly() {
        var c = new Case("intra only", "352x288", "mp4", "mpeg4", "-g 1 -q:v 2", 0.4);
        var file = Fixtures.ffmpeg(c.container(), List.of("-f", "lavfi", "-i", "testsrc2=size=" + c.size() + ":rate=25"),
                List.of("-t", "0.4", "-c:v", "mpeg4", "-g", "1", "-q:v", "2"));
        var expected = Mpeg4TestStreams.ffmpeg(file, "int");
        var decoded = Mpeg4TestStreams.decode(file, Mpeg4Decoder::new);
        INTRA.assertWithin("intra only", expected, decoded.raw());
    }
}
