package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes DNxHD and DNxHR streams made by ffmpeg's dnxhd encoder for every compression ID it
 * produces (HD rasters, progressive and interlaced, 8 and 10-bit; DNxHR LB, SQ, HQ, HQX and 444 in
 * Y'CbCr and RGB at sizes that are not multiples of 16) and compares every frame with ffmpeg's own
 * decode. ST 2019-1 defines the inverse transform mathematically, so the comparison measures PSNR
 * and the largest difference.
 *
 * <p>For 4:4:4 the comparison accounts for the known divergence on the color format flag
 * {@code CLF} (clause 7.2.5): ffmpeg writes its Y'CbCr 4:4:4 streams with the flag set (RGB in the
 * standard, every macroblock in the alternate Y'CbCr color space) and returns them as Y'CbCr,
 * where Smalt returns the BT.709 conversion to RGB that clause 8.1.1 prescribes; ffmpeg writes its
 * RGB streams with the flag clear (Y'CbCr 4:4:4 in the standard) and channels green, blue, red,
 * which Smalt returns as Y'CbCr planes holding those channels. The expected pictures are derived
 * from ffmpeg's decode with that exact relation. A FATE sample from a non-ffmpeg encoder checks the
 * reading of the flag on a real DNxHR 444 file.
 *
 * <p>ffmpeg's C decode of its RGB streams is wrong (about 36 dB against the source pictures, some
 * samples off by full scale), and its arm64 builds decode them that way; its x86 decode reaches
 * about 56 dB. The RGB stream is therefore also compared with the source pictures, and with ffmpeg's
 * decode only where that decode is itself close to the source.
 */
@DisplayName("DNxHD decoder differential against ffmpeg")
class DnxhdDifferentialTest {
    static final List<String> INPUTS = List.of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25",
            "-f", "lavfi", "-i", "mandelbrot=size=320x240:rate=25");

    static String graph(int width, int height) {
        return "[0][1]blend=all_mode=average,scale=" + width + ":" + height + ":flags=bicubic";
    }

    static Path sample(int width, int height, List<String> codec) {
        var output = new ArrayList<>(List.of("-filter_complex", graph(width, height), "-frames:v", "2",
                "-c:v", "dnxhd"));
        output.addAll(codec);
        output.addAll(List.of("-an", "-f", "mov"));
        return Fixtures.ffmpeg("mov", INPUTS, output);
    }

    /** Returns the two source pictures of {@link #sample} in a raw pixel format, as the encoder received them. */
    static byte[] source(int width, int height, String pixelFormat) {
        var arguments = new ArrayList<>(List.of("-v", "error"));
        arguments.addAll(INPUTS);
        arguments.addAll(List.of("-filter_complex", graph(width, height) + ",format=" + pixelFormat, "-frames:v", "2",
                "-f", "rawvideo", "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(5), arguments);
    }

    /** How ffmpeg's decode of a stream relates to the standard's. */
    enum Relation {
        /** The same samples, up to the reconstruction differences. */
        SAME,
        /** ffmpeg returns the Y'CbCr of alternate color space macroblocks the standard converts to RGB. */
        CONVERTED,
        /** ffmpeg returns as G, B, R the planes the standard reads as Y'CbCr 4:4:4. */
        RELABELED
    }

    /** Converts 10-bit yuv444p10le frames to the 16-bit full-range gbrp layout of {@link IntraTesting#raw}, by BT.709. */
    static byte[] bt709ToRgb(byte[] yuv, int width, int height) {
        var plane = width * height;
        var frameBytes = plane * 6;
        var out = new byte[yuv.length];
        for (var frame = 0; frame * frameBytes < yuv.length; frame++) {
            var base = frame * frameBytes;
            for (var i = 0; i < plane; i++) {
                var y = read(yuv, base, i);
                var cb = read(yuv, base + plane * 2, i) - 512;
                var cr = read(yuv, base + plane * 4, i) - 512;
                var excursions = 219.0 / 224.0;
                var r = y + 2 * (1 - 0.2126) * excursions * cr;
                var b = y + 2 * (1 - 0.0722) * excursions * cb;
                var g = y - (2 * (1 - 0.0722) * 0.0722 * cb + 2 * (1 - 0.2126) * 0.2126 * cr) / 0.7152 * excursions;
                write(out, base, i, expand(g));
                write(out, base + plane * 2, i, expand(b));
                write(out, base + plane * 4, i, expand(r));
            }
        }
        return out;
    }

    /** Rounds a converted 10-bit sample to its code, clips it, and stretches the BT.709 levels to 16-bit full range. */
    static int expand(double sample) {
        var code = Math.clamp(Math.round(sample), 0, 1023);
        return (int) Math.clamp(Math.round((code - 64) * 65535.0 / 876), 0, 65535);
    }

    static int read(byte[] raw, int base, int index) {
        return (raw[base + index * 2] & 0xFF) | (raw[base + index * 2 + 1] & 0xFF) << 8;
    }

    static void write(byte[] raw, int base, int index, int value) {
        raw[base + index * 2] = (byte) value;
        raw[base + index * 2 + 1] = (byte) (value >>> 8);
    }

    static Stream<Arguments> streams() {
        return Stream.of(
                Arguments.of("1253 1080p 36M", sample(1920, 1080, List.of("-b:v", "36M", "-pix_fmt", "yuv422p")), "yuv422p", Relation.SAME),
                Arguments.of("1237 1080p 120M", sample(1920, 1080, List.of("-b:v", "120M", "-pix_fmt", "yuv422p")), "yuv422p", Relation.SAME),
                Arguments.of("1238 1080p 185M", sample(1920, 1080, List.of("-b:v", "185M", "-pix_fmt", "yuv422p")), "yuv422p", Relation.SAME),
                Arguments.of("1235 1080p 10-bit", sample(1920, 1080, List.of("-b:v", "185M", "-pix_fmt", "yuv422p10le")), "yuv422p10le", Relation.SAME),
                Arguments.of("1242 1080i 120M", sample(1920, 1080, List.of("-b:v", "120M", "-pix_fmt", "yuv422p", "-flags", "+ildct")), "yuv422p", Relation.SAME),
                Arguments.of("1241 1080i 10-bit", sample(1920, 1080, List.of("-b:v", "185M", "-pix_fmt", "yuv422p10le", "-flags", "+ildct")), "yuv422p10le", Relation.SAME),
                Arguments.of("1251 720p 90M", sample(1280, 720, List.of("-b:v", "90M", "-pix_fmt", "yuv422p")), "yuv422p", Relation.SAME),
                Arguments.of("1250 720p 10-bit", sample(1280, 720, List.of("-b:v", "90M", "-pix_fmt", "yuv422p10le")), "yuv422p10le", Relation.SAME),
                Arguments.of("DNxHR LB", sample(400, 202, List.of("-profile:v", "dnxhr_lb", "-pix_fmt", "yuv422p")), "yuv422p", Relation.SAME),
                Arguments.of("DNxHR SQ", sample(400, 202, List.of("-profile:v", "dnxhr_sq", "-pix_fmt", "yuv422p")), "yuv422p", Relation.SAME),
                Arguments.of("DNxHR HQ", sample(410, 220, List.of("-profile:v", "dnxhr_hq", "-pix_fmt", "yuv422p")), "yuv422p", Relation.SAME),
                Arguments.of("DNxHR HQX", sample(400, 202, List.of("-profile:v", "dnxhr_hqx", "-pix_fmt", "yuv422p10le")), "yuv422p10le", Relation.SAME),
                Arguments.of("DNxHR 444", sample(400, 202, List.of("-profile:v", "dnxhr_444", "-pix_fmt", "yuv444p10le")), "yuv444p10le", Relation.CONVERTED),
                Arguments.of("DNxHR 444 RGB", sample(400, 202, List.of("-profile:v", "dnxhr_444", "-pix_fmt", "gbrp10le")), "gbrp10le", Relation.RELABELED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("matches ffmpeg's decode within the IDCT accuracy bounds, up to the known divergences")
    void matchesFfmpeg(String label, Path file, String pixelFormat, Relation relation) {
        var stream = IntraTesting.demux(file);
        var decoder = new DnxhdDecoder(stream.track());
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> {
                switch (relation) {
                    case CONVERTED -> assertInstanceOf(Rgba16Picture.class, frame.picture(), label);
                    case RELABELED -> assertInstanceOf(Yuv16Picture.class, frame.picture(), label);
                    case SAME -> { }
                }
                ours.writeBytes(IntraTesting.raw(frame.picture(), false));
            });
        }
        var expected = IntraTesting.ffmpegDecode(file, pixelFormat, 0);
        var peak = pixelFormat.contains("10") ? 1023 : 255;
        if (relation == Relation.CONVERTED) {
            expected = bt709ToRgb(expected, stream.track().width(), stream.track().height());
            peak = 65535;
        }
        var actual = ours.toByteArray();
        assertEquals(expected.length, actual.length, label);
        var wide = pixelFormat.endsWith("le");
        if (relation == Relation.RELABELED) {
            var width = stream.track().width();
            var height = stream.track().height();
            var source = source(width, height, pixelFormat);
            var ffmpegToSource = IntraTesting.psnr(source, expected, wide, peak);
            var oursToSource = IntraTesting.psnr(source, actual, wide, peak);
            System.out.printf("%s: psnr against the source %.2f, ffmpeg's %.2f%n", label, oursToSource, ffmpegToSource);
            assertTrue(oursToSource >= 50, label + " PSNR against the source " + oursToSource);
            if (ffmpegToSource < 50) {
                // the C decode described in the class documentation: there is no ffmpeg decode to compare with
                return;
            }
        }
        var psnr = IntraTesting.psnr(expected, actual, wide, peak);
        var max = IntraTesting.maxDifference(expected, actual, wide);
        System.out.printf("%s: psnr=%.2f max=%d mismatch=%.5f%n", label, psnr, max,
                IntraTesting.mismatchRate(expected, actual, wide));
        // ffmpeg reconstructs AC levels a quarter step further from zero than ST 2019-1 equation
        // 8.1 and uses other weights for ID 1271, hence the margin on coarsely quantized streams
        assertTrue(psnr >= 45, label + " PSNR " + psnr);
    }

    @Test
    @DisplayName("reads a non-ffmpeg DNxHR 444 file as RGB coded in Y'CbCr macroblocks")
    void fateDnxhr444() {
        // a DNxHR 444 file from a non-ffmpeg encoder (compressor name "DNxHR", 1920x839), stream
        // copied by ffmpeg into the FATE suite in 2015, before ffmpeg had a DNxHR encoder
        var file = Conformance.fetch("fate-dnxhd-dnxhr444_cid1270.mov",
                "https://fate-suite.ffmpeg.org/dnxhd/dnxhr444_cid1270.mov",
                "f7c2eebab156ebae99be97e0752aca34ab4bce81ce0c776767b814ea014b0856");
        var stream = IntraTesting.demux(file);
        var packet = stream.packets().getFirst().data();
        assertEquals(1, packet[0x2C] & 1, "CLF set: RGB format");
        var decoder = new DnxhdDecoder(stream.track());
        var ours = new ByteArrayOutputStream();
        for (var frame : stream.packets()) {
            decoder.decode(frame, decoded -> {
                assertInstanceOf(Rgba16Picture.class, decoded.picture());
                ours.writeBytes(IntraTesting.raw(decoded.picture(), false));
            });
        }
        var expected = bt709ToRgb(IntraTesting.ffmpegDecode(file, "yuv444p10le", 0), 1920, 839);
        var psnr = IntraTesting.psnr(expected, ours.toByteArray(), true, 65535);
        System.out.printf("FATE dnxhr444_cid1270: psnr=%.2f against ffmpeg's Y'CbCr converted by BT.709%n", psnr);
        assertTrue(psnr >= 50, "PSNR " + psnr);
    }

    @Test
    @DisplayName("computes 8-bit pictures of 10-bit streams directly when high bit depth is off")
    void eightBit() {
        var file = sample(400, 202, List.of("-profile:v", "dnxhr_hqx", "-pix_fmt", "yuv422p10le"));
        var stream = IntraTesting.demux(file);
        var decoder = new DnxhdDecoder(stream.track(), DnxhdDecoderOptions.builder().highBitDepth(false).build());
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> ours.writeBytes(IntraTesting.raw(frame.picture(), false)));
        }
        var psnr = IntraTesting.psnr(IntraTesting.ffmpegDecode(file, "yuv422p", 0), ours.toByteArray(), false, 255);
        assertTrue(psnr >= 40, "8-bit PSNR " + psnr);
    }
}
