package com.github.auties00.smalt.video.mpeg2;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes synthetic scenes with ffmpeg's MPEG-1 and MPEG-2 encoders into every container that carries them (MPEG
 * transport streams, MP4, QuickTime, Matroska, AVI), decodes each file through the demuxer and the codec registry and
 * compares the frames with ffmpeg's decode using its integer inverse DCT ({@code -idct int}), with the same frame
 * count and increasing timestamps.
 *
 * <p>ffmpeg's integer transform is an older version of the libjpeg transform Smalt uses, which rounds some blocks
 * differently, so the frames agree only up to the drift of those differences through the predictions: the tests
 * require the decoder to stay at least as close to ffmpeg's {@code -idct int} decode as ffmpeg's own {@code -idct
 * simple} decode does (the inverse DCT noise floor), and intra-only streams to differ by at most one level in under
 * one sample in two hundred. Sample-exact agreement is the business of {@link Mpeg2ReferenceDecoderTest}.
 */
@DisplayName("MPEG video decoder against ffmpeg")
class Mpeg2DifferentialTest {
    static final Tolerance INTRA = Tolerance.offByOne().and(Tolerance.differing(0.005));

    static Tolerance idctNoiseFloor(byte[] expected, byte[] simple) {
        var floor = Tolerance.Difference.of(expected, simple).psnr();
        return Tolerance.psnr(Math.max(45, Math.min(floor, 60) - 1));
    }

    record Case(String name, String size, String container, String codec, String options, double seconds) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("transport stream", "352x288", "ts", "mpeg2video", "-bf 2 -g 12 -q:v 4", 1.0),
                new Case("interlaced transport stream", "720x576", "ts", "mpeg2video",
                        "-flags +ildct+ilme -top 1 -bf 2 -q:v 4", 0.4),
                new Case("MPEG-1 in a transport stream", "352x288", "ts", "mpeg1video", "-bf 2 -q:v 4", 1.0),
                new Case("mp4", "352x288", "mp4", "mpeg2video", "-bf 2 -q:v 4", 1.0),
                new Case("MPEG-1 in mp4", "352x240", "mp4", "mpeg1video", "-bf 2 -q:v 4", 1.0),
                new Case("quicktime", "352x288", "mov", "mpeg2video", "-bf 2 -q:v 4", 1.0),
                new Case("matroska", "352x288", "mkv", "mpeg2video", "-bf 2 -q:v 4", 1.0),
                new Case("MPEG-1 in matroska", "176x144", "mkv", "mpeg1video", "-bf 1 -q:v 4", 1.0),
                new Case("avi", "352x288", "avi", "mpeg2video", "-bf 2 -q:v 4", 1.0),
                new Case("MPEG-1 in avi", "176x144", "avi", "mpeg1video", "-bf 2 -q:v 4", 1.0),
                new Case("4:2:2 in matroska", "352x288", "mkv", "mpeg2video", "-pix_fmt yuv422p -bf 2 -q:v 4", 0.6),
                new Case("odd size in mp4", "202x118", "mp4", "mpeg2video", "-bf 2 -q:v 4", 0.6)
        );
    }

    static Mpeg2TestStreams.Decoded check(Case c) {
        var output = new ArrayList<>(List.of("-t", Double.toString(c.seconds()), "-c:v", c.codec()));
        output.addAll(List.of(c.options().split(" ")));
        var file = Fixtures.ffmpeg(c.container(), List.of("-f", "lavfi", "-i", "testsrc2=size=" + c.size()
                + ":rate=25"), output);
        var decoded = Mpeg2TestStreams.decodeFile(file);
        assertTrue(decoded.failures().isEmpty(), c.name() + " decodes without errors: " + decoded.failures());
        var format = Mpeg2TestStreams.pixelFormat(decoded.first().chroma());
        var expected = Mpeg2TestStreams.ffmpeg(file, "int", format);
        var simple = Mpeg2TestStreams.ffmpeg(file, "simple", format);
        assertEquals(expected.length, decoded.raw().length, "decoded size of " + c.name() + " after "
                + decoded.count() + " frames");
        idctNoiseFloor(expected, simple).assertWithin(c.name() + " against ffmpeg -idct int", expected,
                decoded.raw());
        var timestamps = decoded.timestamps();
        for (var i = 1; i < timestamps.size(); i++) {
            assertTrue(timestamps.get(i) > timestamps.get(i - 1), "timestamps of " + c.name() + " increase: "
                    + timestamps);
        }
        assertTrue(decoded.first().keyframe(), c.name() + " starts with a key frame");
        return decoded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("decodes within the inverse DCT noise floor of ffmpeg's decode")
    void matchesFfmpeg(Case c) {
        check(c);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("intraCases")
    @DisplayName("decodes intra pictures to within one level of ffmpeg's integer inverse DCT")
    void intraOnly(Case c) {
        var file = Fixtures.ffmpeg(c.container(), List.of("-f", "lavfi", "-i", "testsrc2=size=" + c.size()
                + ":rate=25"), List.of("-t", Double.toString(c.seconds()), "-c:v", c.codec(), "-g", "1", "-q:v", "2"));
        var decoded = Mpeg2TestStreams.decodeFile(file);
        var expected = Mpeg2TestStreams.ffmpeg(file, "int", "yuv420p");
        assertEquals(expected.length, decoded.raw().length, c.name() + " decoded size");
        INTRA.assertWithin(c.name(), expected, decoded.raw());
    }

    static Stream<Case> intraCases() {
        return Stream.of(new Case("MPEG-2 intra", "352x288", "ts", "mpeg2video", "", 0.4),
                new Case("MPEG-1 intra", "352x288", "mkv", "mpeg1video", "", 0.4));
    }
}
