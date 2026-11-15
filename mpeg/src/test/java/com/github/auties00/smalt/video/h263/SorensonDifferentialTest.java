package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Encodes synthetic scenes with ffmpeg's Sorenson Spark encoder (which writes version 1 pictures, with the 7 and
 * 11-bit escapes) into FLV files and compares the decoder's output with ffmpeg's decode, within the noise floor that
 * the freedom of the inverse DCT leaves, as {@link H263DifferentialTest} does: every standard picture
 * size, custom sizes of 8 and 16 bits, intra-only streams, four vectors per macroblock, a fine quantizer over
 * noise, whose levels need the 11-bit escape, and fast scrolling motion, whose vectors reach the edge of their range
 * and point outside the picture.
 */
@DisplayName("Sorenson Spark decoder against ffmpeg")
class SorensonDifferentialTest {
    record Case(String name, String size, String source, List<String> options) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("QCIF", "176x144", "", List.of("-g", "12")),
                new Case("CIF", "352x288", "", List.of("-g", "12")),
                new Case("320x240", "320x240", "", List.of("-g", "12")),
                new Case("160x120", "160x120", "", List.of("-g", "8")),
                new Case("sub-QCIF intra only", "128x96", "", List.of("-g", "1")),
                new Case("custom 8-bit size", "100x76", "", List.of("-g", "10")),
                new Case("custom 16-bit size", "640x360", "", List.of("-g", "10")),
                new Case("four vectors per macroblock", "176x144", "", List.of("-flags", "+mv4", "-g", "12")),
                new Case("fine quantizer over noise", "176x144", ",noise=alls=60:allf=t",
                        List.of("-q:v", "1", "-g", "6")),
                new Case("coarse quantizer", "176x144", "", List.of("-q:v", "31", "-g", "20")),
                // about 21 and 12 pixels a frame: vectors at the edge of the baseline range, pointing outside
                new Case("fast motion", "176x144", ",scroll=horizontal=0.12:vertical=0.08",
                        List.of("-g", "30")),
                new Case("fast motion with four vectors", "176x144", ",scroll=horizontal=-0.1:vertical=0.09",
                        List.of("-flags", "+mv4", "-g", "30")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("decodes ffmpeg's FLV files within the IDCT noise floor of ffmpeg's decode")
    void matchesFfmpeg(Case c) {
        var output = new ArrayList<>(List.of("-t", "1", "-c:v", "flv1", "-q:v", "5"));
        output.addAll(c.options());
        var file = Fixtures.ffmpeg("flv", List.of("-f", "lavfi", "-i", "testsrc2=size=" + c.size() + ":rate=15"
                + c.source()), output);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            assertEquals(ContainerFormat.FLV, demuxer.format());
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.FLV1, track.codec());
            var dimensions = c.size().split("x");
            assertEquals(Integer.parseInt(dimensions[0]), track.width());
            assertEquals(Integer.parseInt(dimensions[1]), track.height());
        }
        var expected = H263DifferentialTest.ffmpeg(file, "int");
        var pictures = H263DifferentialTest.decode(file);
        var raw = H263DifferentialTest.concat(pictures);
        assertEquals(expected.length, raw.length, c.name() + " after " + pictures.size() + " frames");
        // the IDCT noise floor: the PSNR against the -idct int decode that ffmpeg's -idct simple decode reaches,
        // capped at 60 dB, less 1 dB
        var simple = H263DifferentialTest.ffmpeg(file, "simple");
        Tolerance.psnr(Math.min(Tolerance.Difference.of(expected, simple).psnr(), 60) - 1)
                .assertWithin(c.name() + " against ffmpeg -idct int", expected, raw);
    }
}
