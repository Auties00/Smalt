package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes DV streams made by ffmpeg's dvvideo encoder in every format it writes (DV and DVCPRO at 25 Mb/s, DVCPRO50
 * and the four DVCPRO HD systems), with frame DCT and with the interlaced 2-4-8 and field DCT modes, and compares
 * every frame with ffmpeg's own decode. The inverse DCT of DV is not normative, so samples may round differently.
 */
@DisplayName("DV decoder differential against ffmpeg")
class DvDifferentialTest {
    /**
     * The DCT of SMPTE ST 314 and ST 370 is not normative, and ffmpeg computes it in fixed point while Smalt computes
     * it in floating point: every sample within 2, the RMS difference below 0.4 (about 0.3 is measured; a misplaced
     * or misdecoded block raises it far above).
     */
    static final Tolerance TOLERANCE = Tolerance.maxAbs(2).and(Tolerance.rms(0.4));

    /** Encodes three frames of a blend of ffmpeg's test patterns as a raw DIF stream. */
    static Path sample(DvProfile profile, List<String> extra) {
        return sample(profile, extra, false);
    }

    /** Encodes three frames as {@link #sample(DvProfile, List)} does, with strong noise when asked. */
    static Path sample(DvProfile profile, List<String> extra, boolean noisy) {
        var output = new ArrayList<>(List.of("-filter_complex",
                "[0][1]blend=all_mode=average,scale=" + DvTesting.size(profile) + ":flags=bicubic"
                        + (noisy ? ",noise=alls=40:allf=t" : ""),
                "-r", DvTesting.rate(profile), "-frames:v", "3", "-c:v", "dvvideo", "-pix_fmt",
                DvTesting.pixelFormat(profile)));
        output.addAll(extra);
        output.addAll(List.of("-an", "-f", "dv"));
        return Fixtures.ffmpeg("dv", List.of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25",
                "-f", "lavfi", "-i", "mandelbrot=size=320x240:rate=25"), output);
    }

    static Stream<Arguments> streams() {
        var cases = new ArrayList<Arguments>();
        for (var profile : List.of(DvProfile.DV_525, DvProfile.DV_625, DvProfile.DVCPRO_625,
                DvProfile.DVCPRO50_525, DvProfile.DVCPRO50_625, DvProfile.DVCPRO_HD_1080I60,
                DvProfile.DVCPRO_HD_1080I50, DvProfile.DVCPRO_HD_720P60, DvProfile.DVCPRO_HD_720P50)) {
            cases.add(Arguments.of(profile.name(), profile, sample(profile, List.of())));
        }
        cases.add(Arguments.of("DV_525 2-4-8 DCT", DvProfile.DV_525, sample(DvProfile.DV_525,
                List.of("-flags", "+ildct"))));
        cases.add(Arguments.of("DV_625 2-4-8 DCT", DvProfile.DV_625, sample(DvProfile.DV_625,
                List.of("-flags", "+ildct"))));
        cases.add(Arguments.of("DVCPRO50_625 2-4-8 DCT", DvProfile.DVCPRO50_625, sample(DvProfile.DVCPRO50_625,
                List.of("-flags", "+ildct"))));
        cases.add(Arguments.of("DVCPRO_HD_1080I60 field DCT", DvProfile.DVCPRO_HD_1080I60,
                sample(DvProfile.DVCPRO_HD_1080I60, List.of("-flags", "+ildct"))));
        // noise makes most blocks continue in the free space of their macroblock and of their video segment
        for (var profile : List.of(DvProfile.DV_525, DvProfile.DV_625, DvProfile.DVCPRO50_525,
                DvProfile.DVCPRO_HD_1080I50, DvProfile.DVCPRO_HD_720P60)) {
            cases.add(Arguments.of(profile.name() + " noisy", profile, sample(profile, List.of(), true)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("matches ffmpeg's decode within the DCT rounding")
    void matchesFfmpeg(String label, DvProfile profile, Path file) {
        var packets = DvTesting.split(Fixtures.read(file), profile);
        assertEquals(3, packets.size(), label);
        var pixelFormat = DvTesting.pixelFormat(profile);
        var ours = new ByteArrayOutputStream();
        var decoder = new DvDecoder();
        for (var packet : packets) {
            decoder.decode(packet, frame -> {
                assertEquals(profile.width(), frame.picture().width());
                assertEquals(profile.height(), frame.picture().height());
                assertTrue(frame.keyframe());
                var order = frame.sideData().fieldOrder();
                var expected = !profile.interlaced() ? FieldOrder.PROGRESSIVE
                        : profile.width() > 720 ? FieldOrder.TOP_FIRST : FieldOrder.BOTTOM_FIRST;
                assertEquals(expected, order.orElseThrow());
                ours.writeBytes(DvTesting.raw(frame.picture(), pixelFormat));
            });
        }
        var expected = IntraTesting.ffmpegDecode(file, pixelFormat, 0);
        var actual = ours.toByteArray();
        assertEquals(expected.length, actual.length, label);
        var difference = IntraTesting.difference(expected, actual, false, 255);
        System.out.printf("%s: %s%n", label, difference);
        TOLERANCE.check(label, difference);
    }
}
