package com.github.auties00.smalt.video.prores;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
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

/**
 * Decodes ProRes streams made by ffmpeg's prores_ks encoder in every flavor, at frame sizes that
 * are and are not multiples of the macroblock size, progressive and interlaced, with and without
 * alpha, and compares every frame with ffmpeg's own ProRes decode. RDD 36 leaves the inverse
 * transform implementation-defined within the Annex A accuracy bounds, so the comparison measures
 * PSNR and the largest difference instead of requiring identical samples.
 */
@DisplayName("ProRes decoder differential against ffmpeg")
class ProResDifferentialTest {
    /**
     * SMPTE RDD 36 leaves the inverse DCT to the decoder within an accuracy bound, so the deep pictures may round
     * differently from ffmpeg's: at least 60 dB.
     */
    static final Tolerance TOLERANCE = Tolerance.psnr(60);

    /** 8-bit pictures computed directly against ffmpeg's narrowing of its deep decode. */
    static final Tolerance EIGHT_BIT = Tolerance.psnr(45);

    static Path sample(int profile, int width, int height, List<String> extra) {
        var options = new ArrayList<>(List.of("-frames:v", "3", "-c:v", "prores_ks", "-profile:v", Integer.toString(profile)));
        options.addAll(extra);
        options.addAll(List.of("-an", "-f", "mov"));
        var output = new ArrayList<>(List.of("-filter_complex",
                "[0][1]blend=all_mode=average,scale=" + width + ":" + height + ":flags=bicubic"));
        output.addAll(options);
        return Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25",
                "-f", "lavfi", "-i", "mandelbrot=size=320x240:rate=25"), output);
    }

    static Stream<Arguments> flavors() {
        var cases = new ArrayList<Arguments>();
        for (var profile = 0; profile <= 5; profile++) {
            var alpha = profile >= 4;
            var format = alpha ? List.of("-pix_fmt", "yuva444p10le") : List.of("-pix_fmt", "yuv422p10le");
            cases.add(Arguments.of("profile " + profile + " 320x240", sample(profile, 320, 240, format),
                    alpha ? "yuva444p12le" : "yuv422p10le", alpha));
            cases.add(Arguments.of("profile " + profile + " 202x98", sample(profile, 202, 98, format),
                    alpha ? "yuva444p12le" : "yuv422p10le", alpha));
        }
        cases.add(Arguments.of("4444 without alpha", sample(4, 176, 144, List.of("-pix_fmt", "yuv444p10le")),
                "yuv444p12le", false));
        cases.add(Arguments.of("interlaced top first", sample(3, 320, 240, List.of("-pix_fmt", "yuv422p10le",
                "-flags", "+ildct", "-top", "1")), "yuv422p10le", false));
        cases.add(Arguments.of("interlaced bottom first odd height", sample(2, 200, 101, List.of("-pix_fmt", "yuv422p10le",
                "-flags", "+ildct", "-top", "0")), "yuv422p10le", false));
        cases.add(Arguments.of("4444 interlaced alpha", sample(4, 128, 90, List.of("-pix_fmt", "yuva444p10le",
                "-flags", "+ildct", "-top", "1")), "yuva444p12le", true));
        cases.add(Arguments.of("one-macroblock slices", sample(2, 96, 64, List.of("-pix_fmt", "yuv422p10le",
                "-mbs_per_slice", "1")), "yuv422p10le", false));
        cases.add(Arguments.of("8-bit alpha", sample(4, 64, 48, List.of("-pix_fmt", "yuva444p10le",
                "-alpha_bits", "8")), "yuva444p12le", true));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("flavors")
    @DisplayName("matches ffmpeg's decode within the IDCT accuracy bounds")
    void matchesFfmpeg(String label, Path file, String pixelFormat, boolean alpha) {
        var stream = IntraTesting.demux(file);
        var decoder = new ProResDecoder(stream.track());
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> ours.writeBytes(IntraTesting.raw(frame.picture(), alpha)));
        }
        var expected = IntraTesting.ffmpegDecode(file, pixelFormat, 0);
        var actual = ours.toByteArray();
        assertEquals(expected.length, actual.length, label);
        var peak = pixelFormat.contains("12") ? 4095 : 1023;
        var difference = IntraTesting.difference(expected, actual, true, peak);
        System.out.printf("%s: %s%n", label, difference);
        TOLERANCE.check(label, difference);
    }

    @ParameterizedTest(name = "profile {0}")
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 4})
    @DisplayName("computes 8-bit pictures directly when high bit depth is off")
    void eightBit(int profile) {
        var alpha = profile >= 4;
        var file = sample(profile, 320, 240, List.of("-pix_fmt", alpha ? "yuva444p10le" : "yuv422p10le"));
        var stream = IntraTesting.demux(file);
        var decoder = new ProResDecoder(stream.track(), ProResDecoderOptions.builder().highBitDepth(false).build());
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> {
                assertEquals(8, frame.picture().bitDepth());
                ours.writeBytes(IntraTesting.raw(frame.picture(), alpha));
            });
        }
        var expected = IntraTesting.ffmpegDecode(file, alpha ? "yuva444p" : "yuv422p", 0);
        IntraTesting.assertWithin(EIGHT_BIT, "8-bit profile " + profile, expected, ours.toByteArray(), false, 255);
    }

    /**
     * ProRes files written by Apple's encoders (Final Cut Pro, Compressor, the Netflix Sol Levante masters): every
     * flavor from Proxy to 4444 XQ, interlaced 1080 and UHD progressive, with and without alpha, gray content and a
     * file whose colour tags fall back to the frame header.
     */
    static Stream<Arguments> appleFiles() {
        return Stream.of(
                Arguments.of("prores-fate-Sequence_1-Apple_ProRes_422.mov", "yuv422p10le", false),
                Arguments.of("prores-fate-Sequence_1-Apple_ProRes_422_HQ.mov", "yuv422p10le", false),
                Arguments.of("prores-fate-Sequence_1-Apple_ProRes_422_LT.mov", "yuv422p10le", false),
                Arguments.of("prores-fate-Sequence_1-Apple_ProRes_422_Proxy.mov", "yuv422p10le", false),
                Arguments.of("prores-fate-Sequence_1-Apple_ProRes_with_Alpha.mov", "yuva444p12le", true),
                Arguments.of("prores-fate-color-tags-fallback.mov", "yuv422p10le", false),
                Arguments.of("prores-fate-gray.mov", "yuv422p10le", false),
                Arguments.of("prores-fate-prores4444_with_transparency.mov", "yuva444p12le", true),
                Arguments.of("prores-netflix-sollevante-sdr-422hq-uhd-f2400-12.mov", "yuv422p10le", false),
                Arguments.of("prores-netflix-sollevante-hdr10-4444xq-uhd-f2400-12.mov", "yuv444p12le", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("appleFiles")
    @DisplayName("decodes Apple-encoded files as ffmpeg does within the IDCT accuracy bounds")
    void appleEncoded(String name, String pixelFormat, boolean alpha) {
        var file = Conformance.vector(name);
        var stream = IntraTesting.demux(file);
        var decoder = new ProResDecoder(stream.track());
        var peak = pixelFormat.contains("12") ? 4095 : 1023;
        var packets = stream.packets();
        // every frame is intra coded: compare frame by frame so the UHD masters never sit whole in memory
        for (var index = 0; index < packets.size(); index++) {
            var ours = new ByteArrayOutputStream();
            decoder.decode(packets.get(index), frame -> ours.writeBytes(IntraTesting.raw(frame.picture(), alpha)));
            var actual = ours.toByteArray();
            // the coded frame, not the clean aperture ffmpeg 7.1 crops to by default
            var expected = IntraTesting.ffmpegDecodeFrame(file, List.of("-apply_cropping", "none"), pixelFormat, index,
                    actual.length);
            var label = name + " frame " + index;
            var difference = IntraTesting.difference(expected, actual, true, peak);
            System.out.printf("%s: %s%n", label, difference);
            TOLERANCE.check(label, difference);
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the Sol Levante cuts ship with the licence of their masters")
    void solLevanteLicence() throws java.io.IOException {
        // the cuts are CC BY 4.0 (Netflix Open Content): the licence text travels with them in the test data
        var text = java.nio.file.Files.readString(Conformance.vector("netflix-sollevante-license.txt"),
                java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("Attribution 4.0 International"), "CC BY 4.0 text");
        Conformance.vector("prores-netflix-sollevante-sdr-422hq-uhd-f2400-12.mov");
        Conformance.vector("prores-netflix-sollevante-hdr10-4444xq-uhd-f2400-12.mov");
    }
}
