package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks DNxHD and DNxHR alpha: the in-band alpha channel of ST 2019-1 (Amendment 1:2023), coded
 * by Smalt's encoder with the DCT and losslessly and read back by Smalt's decoder, and the
 * out-of-band alpha of Avid's QuickTime encoders, read from Avid files attached to public ffmpeg
 * bug reports. No file with in-band alpha from another encoder was found, so the in-band tests are
 * round trips through Smalt's own encoder.
 */
@DisplayName("DNxHD alpha")
class DnxhdAlphaTest {
    /** Returns the 16-bit RGBA samples of a picture. */
    static short[] rgba(Picture picture) {
        if (picture instanceof Rgba16Picture wide) {
            return wide.pixels();
        }
        return ImageOps.toSixteenBit(picture instanceof RgbaPicture narrow ? narrow : ImageOps.toRgba(picture)).pixels();
    }

    /** Returns the PSNR of one channel (0 to 3) of two 16-bit RGBA buffers. */
    static double psnr(short[] expected, short[] actual, int channel) {
        var sum = 0.0;
        for (var i = channel; i < expected.length; i += 4) {
            var difference = (expected[i] & 0xFFFF) - (actual[i] & 0xFFFF);
            sum += (double) difference * difference;
        }
        var mse = sum / (expected.length / 4.0);
        return mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(65535.0 * 65535.0 / mse);
    }

    /** Returns an RGBA picture whose alpha is a disc with a soft edge over a transparent background. */
    static RgbaPicture disc(int width, int height) {
        var picture = (RgbaPicture) IntraTesting.sourceFrames(width, height, "rgba", 1).getFirst();
        var pixels = picture.pixels();
        var radius = Math.min(width, height) * 0.4;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var edge = Math.hypot(x - width / 2.0, y - height / 2.0) - radius;
                pixels[(y * width + x) * 4 + 3] = (byte) (edge < -8 ? 255 : edge > 8 ? 0 : Math.round((8 - edge) * 255 / 16));
            }
        }
        return picture;
    }

    static Packet encode(DnxhdEncoder encoder, Picture picture) {
        return encoder.encodeFrame(new VideoFrame(picture, 0, 3600, true));
    }

    @Nested
    @DisplayName("in band")
    class InBand {
        static Stream<Arguments> cases() {
            return Stream.of(
                    Arguments.of("DNxHR HQ", 640, 360, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ), 1272, 3, 60.0),
                    Arguments.of("DNxHR HQX", 640, 360, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX), 1271, 3, 70.0),
                    Arguments.of("DNxHR 444", 640, 360, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444), 1270, 3, 70.0),
                    Arguments.of("DNxHR 444 RGB", 640, 360, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder()
                            .rgb(true).build(), 1270, 3, 70.0),
                    Arguments.of("DNxHD 1080p", 1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                            .toBuilder()
                            .compressionId(1237)
                            .build(), 1237, 4, 60.0),
                    Arguments.of("DNxHD 1080i two fields", 1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                            .toBuilder()
                            .fieldOrder(FieldOrder.TOP_FIRST)
                            .build(), 1243, 4, 60.0),
                    Arguments.of("DNxHD 1080i adaptive", 1440, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                            .toBuilder()
                            .fieldOrder(FieldOrder.TOP_FIRST).compressionId(1260)
                            .build(), 1260, 4, 60.0),
                    Arguments.of("DNxHD 444 RGB", 1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder()
                            .rgb(true).build(), 1256, 4, 70.0));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        @DisplayName("round-trips DCT-coded alpha in frames half again as large")
        void dctAlpha(String label, int width, int height, DnxhdEncoderOptions options, int cid, int version,
                      double minimumAlphaPsnr) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = disc(width, height);
            var plain = encode(new DnxhdEncoder(width, height, options), source);
            var encoder = new DnxhdEncoder(width, height, options.toBuilder().alpha(true).build());
            assertEquals(cid, encoder.compressionId());
            var packet = encode(encoder, source);
            var data = packet.data();
            assertEquals(1, data[7] & 1, "ALP set");
            assertEquals(0, data[7] & 2, "LLA clear");
            assertEquals(version, data[4], "header version");
            assertEquals(sizeWithAlpha(cid, plain.size(), width, height), packet.size(), "frame size with alpha");
            var picture = new DnxhdDecoder().decodeFrame(data, 0, data.length);
            assertTrue(picture.hasAlpha(), label);
            var expected = rgba(source);
            var actual = rgba(picture);
            var alpha = psnr(expected, actual, 3);
            var green = psnr(expected, actual, 1);
            System.out.printf("%s: alpha %.2f dB, green %.2f dB, %d bytes%n", label, alpha, green, packet.size());
            assertTrue(alpha >= minimumAlphaPsnr, label + " alpha PSNR " + alpha);
            assertTrue(green >= 40, label + " green PSNR " + green);
        }

        @Test
        @DisplayName("codes DNxHR 444 alpha losslessly")
        void lossless() {
            var source = IntraTesting.sourceFrames(320, 240, "yuva444p10le", 1).getFirst();
            var encoder = new DnxhdEncoder(320, 240, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder()
                    .alpha(true)
                    .losslessAlpha(true).build());
            var data = encode(encoder, source).data();
            assertEquals(3, data[7] & 3, "ALP and LLA set");
            var picture = assertInstanceOf(Yuv16Picture.class, new DnxhdDecoder().decodeFrame(data, 0, data.length));
            var original = (Yuv16Picture) source;
            for (var y = 0; y < 240; y++) {
                for (var x = 0; x < 320; x++) {
                    assertEquals(original.alpha()[y * original.alphaStride() + x], picture.alpha()[y * picture.alphaStride() + x],
                            "alpha at " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("codes the alpha of ID 1256 losslessly at its 10 bits")
        void losslessRgb() {
            var source = disc(1920, 1080);
            var data = encode(new DnxhdEncoder(1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder()
                    .rgb(true)
                    .alpha(true).losslessAlpha(true).build()), source).data();
            assertEquals(3, data[7] & 3, "ALP and LLA set");
            assertEquals(4, data[4], "header version 4");
            var picture = assertInstanceOf(Rgba16Picture.class, new DnxhdDecoder().decodeFrame(data, 0, data.length));
            var expected = source.pixels();
            for (var i = 3; i < expected.length; i += 4) {
                var code = (int) Math.round((expected[i] & 0xFF) * 1023.0 / 255);
                assertEquals((code * 65535 + 511) / 1023, picture.pixels()[i] & 0xFFFF, "alpha sample " + i / 4);
            }
        }

        @Test
        @DisplayName("falls back to DCT-coded alpha when lossless alpha leaves no room for the video")
        void losslessFallback() {
            var source = (Yuv16Picture) IntraTesting.sourceFrames(320, 240, "yuva444p12le", 1).getFirst();
            var random = new java.util.Random(7);
            for (var i = 0; i < source.alpha().length; i++) {
                source.alpha()[i] = (short) random.nextInt(4096);
            }
            var data = encode(new DnxhdEncoder(320, 240, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444)
                    .toBuilder()
                    .bitDepth(12).alpha(true).losslessAlpha(true)
                    .build()), source).data();
            assertEquals(1, data[7] & 3, "ALP set, LLA clear");
            var picture = new DnxhdDecoder().decodeFrame(data, 0, data.length);
            assertTrue(picture.hasAlpha());
        }

        @Test
        @DisplayName("writes alpha streams into QuickTime with a 32-bit depth that ffmpeg decodes cleanly")
        void quickTime(@TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = disc(640, 360);
            var encoder = new DnxhdEncoder(640, 360, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder()
                    .alpha(true)
                    .frameRate(DnxhdEncoderTest.FRAME_RATE).build());
            var packets = DnxhdEncoderTest.encode(List.of(source, source), encoder);
            var file = DnxhdEncoderTest.mux(packets, encoder, directory.resolve("alpha.mov"));
            var bytes = Files.readAllBytes(file);
            var entry = indexOf(bytes, "AVdh".getBytes()) - 4;
            assertEquals(32, (bytes[entry + 82] & 0xFF) << 8 | bytes[entry + 83] & 0xFF, "sample entry depth");
            // ffmpeg ignores the alpha macroblocks and decodes the video of an 8-bit DNxHR frame
            Oracle.assertDecodesCleanly(file);
            var ffmpeg = IntraTesting.ffmpegDecode(file, "yuv422p", 1);
            var ours = IntraTesting.raw(new DnxhdDecoder().decodeFrame(packets.getFirst().data(), 0,
                    packets.getFirst().size()), false);
            var psnr = IntraTesting.psnr(ours, ffmpeg, false, 255);
            assertTrue(psnr > 40, "ffmpeg against Smalt " + psnr);
        }

        /**
         * Returns the frame size with alpha: 1.5 times the HD size (Amendment 1:2023), equation 7.1
         * with {@code fA = 0.5} for the RI IDs.
         */
        static long sizeWithAlpha(int cid, int plainSize, int width, int height) {
            if (cid < 1270) {
                return plainSize * 3L / 2;
            }
            var reference = cid == 1270 ? 1835008L : cid == 1273 ? 606208L : 917504L;
            var size = 3 * reference * ((width + 15) / 16) * ((height + 15) / 16) / (2 * 8160);
            var remainder = size % 4096;
            return Math.max(8192, remainder >= 2048 ? size + 4096 - remainder : size - remainder);
        }

        static int indexOf(byte[] data, byte[] pattern) {
            outer:
            for (var i = 0; i + pattern.length <= data.length; i++) {
                for (var j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    @Nested
    @DisplayName("Avid out of band")
    class OutOfBand {
        @Test
        @DisplayName("reads the raw 8-bit alpha after a DNxHR SQ frame")
        void rawAlpha() {
            // Avid-encoded DNxHR SQ 480x270 with "straight" alpha, attached to ffmpeg trac ticket 8427
            var file = Conformance.fetch("ffmpeg-trac-8427-dnxhd_with_alpha_straight.mov",
                    "https://trac.ffmpeg.org/raw-attachment/ticket/8427/dnxhd_with_alpha_straight.mov",
                    "10d46a0f07ac9ec5093dc7deac30446b5c42d86685e6bbc475ff05c570fb71a4");
            var stream = IntraTesting.demux(file);
            var data = stream.packets().getFirst().data();
            var videoSize = (data[data.length - 4] & 0xFF) << 24 | (data[data.length - 3] & 0xFF) << 16
                    | (data[data.length - 2] & 0xFF) << 8 | data[data.length - 1] & 0xFF;
            assertEquals(36864, videoSize, "the frame ends with the size of its coding unit");
            assertEquals(0, data[7] & 1, "no in-band alpha");
            var picture = assertInstanceOf(YuvPicture.class, new DnxhdDecoder(stream.track()).decodeFrame(data, 0, data.length));
            assertNotNull(picture.alpha());
            var expected = java.util.Arrays.copyOfRange(data, videoSize, videoSize + 480 * 270);
            var actual = new byte[480 * 270];
            for (var y = 0; y < 270; y++) {
                System.arraycopy(picture.alpha(), y * picture.alphaStride(), actual, y * 480, 480);
            }
            assertArrayEquals(expected, actual);
            var levels = new java.util.HashSet<Integer>();
            for (var value : actual) {
                levels.add(value & 0xFF);
            }
            assertTrue(levels.contains(0) && levels.contains(255) && levels.size() > 2, "alpha carries transparency");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var ffmpeg = IntraTesting.ffmpegDecode(file, "yuv422p", 1);
                var psnr = IntraTesting.psnr(ffmpeg, IntraTesting.raw(picture, false), false, 255);
                assertTrue(psnr > 50, "video PSNR against ffmpeg " + psnr);
            }
        }

        @Test
        @DisplayName("reads the raw 8-bit alpha after a 10-bit two-field DNxHD frame as 10-bit alpha")
        void rawAlphaTenBit() {
            // Avid-encoded DNxHD 1241 1080i, alpha area sized for 10-bit samples, ffmpeg trac ticket 3707
            var file = Conformance.fetch("ffmpeg-trac-3707-untitled_dnhd.mov",
                    "https://samples.ffmpeg.org/ffmpeg-bugs/trac/ticket3707/Untitled_dnhd.mov",
                    "ff237238ca50fe68bae53876e98d69507b07439b9323bd5271f305e52cbb8092");
            var stream = IntraTesting.demux(file);
            var data = stream.packets().getFirst().data();
            assertEquals(917504 + 1920 * 1080 * 10 / 8 + 4, data.length);
            var picture = assertInstanceOf(Yuv16Picture.class, new DnxhdDecoder(stream.track()).decodeFrame(data, 0, data.length));
            for (var y = 0; y < 1080; y += 7) {
                for (var x = 0; x < 1920; x += 5) {
                    var code = data[917504 + y * 1920 + x] & 0xFF;
                    assertEquals((code * 1023 + 127) / 255, picture.alpha()[y * picture.alphaStride() + x]);
                }
            }
        }

        @Test
        @DisplayName("reads alpha coded as a second DNxHD coding unit")
        void codedAlpha(@TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            // Avid "DNxHD RGB Codec" export, ID 1238 with its alpha in a second coding unit, ffmpeg trac ticket 3707
            var file = Conformance.fetch("ffmpeg-trac-3707-dnxhd_alpha.mov",
                    "https://samples.ffmpeg.org/ffmpeg-bugs/trac/ticket3707/DNxHD%20Alpha.mov",
                    "e52d761169e71fab3c413fec66543a61cfafa4472c1c7860444ba2058600b2c6");
            var stream = IntraTesting.demux(file);
            var data = stream.packets().get(50).data();
            assertEquals(917504 * 2 + 4, data.length);
            var unit = directory.resolve("alpha.dnxhd");
            Files.write(unit, java.util.Arrays.copyOfRange(data, 917504, 2 * 917504));
            // ffmpeg decodes the second coding unit on its own; its luma is the alpha
            var luma = java.util.Arrays.copyOf(Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1),
                    List.of("-v", "error", "-f", "dnxhd", "-i", unit.toString(), "-f", "rawvideo", "-pix_fmt", "gray", "-")),
                    1920 * 1080);
            var picture = assertInstanceOf(YuvPicture.class, new DnxhdDecoder(stream.track()).decodeFrame(data, 0, data.length));
            var actual = new byte[1920 * 1080];
            for (var y = 0; y < 1080; y++) {
                System.arraycopy(picture.alpha(), y * picture.alphaStride(), actual, y * 1920, 1920);
            }
            var psnr = IntraTesting.psnr(luma, actual, false, 255);
            // ffmpeg reconstructs AC levels differently (see DnxhdDifferentialTest), hence the margin
            assertTrue(psnr > 45, "alpha PSNR against ffmpeg's luma " + psnr);
        }
    }

    @Test
    @DisplayName("keeps 8-bit alpha of a 10-bit stream when high bit depth is off")
    void eightBitOutput() {
        var source = IntraTesting.sourceFrames(160, 96, "yuva444p10le", 1).getFirst();
        var data = encode(new DnxhdEncoder(160, 96, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder()
                .alpha(true)
                .losslessAlpha(true).build()), source).data();
        var picture = assertInstanceOf(YuvPicture.class, new DnxhdDecoder(DnxhdDecoderOptions.builder()
                .highBitDepth(false)
                .build()).decodeFrame(data, 0, data.length));
        assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
        var original = (Yuv16Picture) source;
        for (var i = 0; i < 160 * 96; i += 13) {
            var expected = Math.min(255, (original.alpha()[i] + 2) >> 2);
            assertEquals(expected, picture.alpha()[(i / 160) * picture.alphaStride() + i % 160] & 0xFF);
        }
        assertEquals(ColorInfo.BT709_LIMITED, picture.color());
    }
}
