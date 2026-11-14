package com.github.auties00.smalt.video.cfhd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes CineForm streams made by ffmpeg's encoder in every pixel format and several qualities, in QuickTime, AVI and
 * Matroska, and compares them with ffmpeg's decode and with the source frames; feeds damaged samples.
 */
@DisplayName("CineForm against ffmpeg")
class CfhdDifferentialTest {
    // GoPro's and ffmpeg's decoders add output-dependent rounding offsets to the lowpass band; Smalt adds none
    static final Tolerance FFMPEG = Tolerance.maxAbs(8);

    // the SDK's decoder adds output-dependent rounding offsets to the lowpass band; Smalt adds none
    static final Tolerance GOPRO = Tolerance.maxAbs(4);

    static final int WIDTH = 176;

    static final int HEIGHT = 90;

    static java.nio.file.Path sample(String pixelFormat, String quality, String container) {
        return Fixtures.ffmpeg(container, List.of("-f", "lavfi", "-i", "testsrc2=size=" + WIDTH + "x" + HEIGHT
                + ":rate=25", "-f", "lavfi", "-i", "gradients=size=" + WIDTH + "x" + HEIGHT + ":rate=25:seed=2"),
                List.of("-filter_complex", "[1]format=gray[a];[0]format=rgba[c];[c][a]alphamerge,format=" + pixelFormat,
                        "-frames:v", "3", "-c:v", "cfhd", "-quality", quality));
    }

    /** Lays a picture out as ffmpeg's yuv422p10le, gbrp12le or gbrap12le. */
    static byte[] raw(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case Yuv16Picture yuv -> out.writeBytes(IntraTesting.raw(yuv, false));
            case Rgba16Picture rgba -> {
                for (var channel : rgba.hasAlpha() ? new int[]{1, 2, 0, 3} : new int[]{1, 2, 0}) {
                    for (var y = 0; y < rgba.height(); y++) {
                        for (var x = 0; x < rgba.width(); x++) {
                            var value = (rgba.pixels()[y * rgba.stride() + x * 4 + channel] & 0xFFFF) >>> 4;
                            out.write(value);
                            out.write(value >>> 8);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException(picture.toString());
        }
        return out.toByteArray();
    }

    /** Returns frames of gbrap12le without their alpha planes. */
    static byte[] withoutAlpha(byte[] frames) {
        var plane = WIDTH * HEIGHT * 2;
        var out = new ByteArrayOutputStream();
        for (var at = 0; at + 4 * plane <= frames.length; at += 4 * plane) {
            out.write(frames, at, 3 * plane);
        }
        return out.toByteArray();
    }

    /** Returns the PSNR of two little-endian 16-bit buffers with a given peak. */
    static double psnr(byte[] expected, byte[] actual, int peak) {
        assertEquals(expected.length, actual.length);
        var sum = 0.0;
        for (var i = 0; i < expected.length; i += 2) {
            var a = (expected[i] & 0xFF) | (expected[i + 1] & 0xFF) << 8;
            var b = (actual[i] & 0xFF) | (actual[i + 1] & 0xFF) << 8;
            sum += (double) (a - b) * (a - b);
        }
        return sum == 0 ? 99 : 10 * StrictMath.log10((double) peak * peak * (expected.length / 2.0) / sum);
    }

    @ParameterizedTest(name = "{0} {1} in {2}")
    @CsvSource({"yuv422p10le,film3+,mov", "yuv422p10le,medium,mov", "yuv422p10le,low,avi", "yuv422p10le,film1,mkv",
            "gbrp12le,film3+,mov", "gbrp12le,high,avi", "gbrap12le,film3+,mov", "gbrap12le,medium+,mkv"})
    @DisplayName("decodes ffmpeg's streams as ffmpeg does, close to the source frames")
    void decodesFfmpeg(String pixelFormat, String quality, String container) {
        var file = sample(pixelFormat, quality, container);
        var stream = IntraTesting.demux(file);
        assertEquals(Codec.CFHD, stream.track().codec());
        var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.defaults());
        assertInstanceOf(CfhdDecoder.class, decoder);
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> {
                assertEquals(WIDTH, frame.picture().width());
                assertEquals(HEIGHT, frame.picture().height());
                ours.writeBytes(raw(frame.picture()));
            });
        }
        var expected = IntraTesting.ffmpegDecode(file, pixelFormat, 0);
        var label = pixelFormat + " " + quality + " " + container;
        FFMPEG.check(label, Tolerance.Difference.of(reduce(expected, 16), reduce(ours.toByteArray(), 16)));
        var source = com.github.auties00.smalt.testkit.Oracle.run(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG,
                java.time.Duration.ofMinutes(2), List.of("-v", "error", "-f", "lavfi", "-i", "testsrc2=size="
                        + WIDTH + "x" + HEIGHT + ":rate=25", "-f", "lavfi", "-i", "gradients=size=" + WIDTH + "x"
                        + HEIGHT + ":rate=25:seed=2", "-filter_complex", "[1]format=gray[a];[0]format=rgba[c];[c][a]"
                        + "alphamerge,format=" + pixelFormat, "-frames:v", "3", "-f", "rawvideo", "-"));
        // ffmpeg's encoder does not compand alpha as CineForm decoders expand it (its own round trip of the alpha
        // plane is near 10 dB), so only the color planes are held to the source
        var colors = pixelFormat.equals("gbrap12le") ? withoutAlpha(source) : source;
        var decoded = pixelFormat.equals("gbrap12le") ? withoutAlpha(ours.toByteArray()) : ours.toByteArray();
        var quality10 = psnr(colors, decoded, pixelFormat.startsWith("yuv") ? 1023 : 4095);
        assertTrue(quality10 > (quality.startsWith("film") ? 40 : 30), label + ": PSNR against the source " + quality10);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"yuv422p10le", "gbrap12le"})
    @DisplayName("raises only the documented exceptions for truncations and byte flips")
    void damaged(String pixelFormat) {
        var stream = IntraTesting.demux(sample(pixelFormat, "medium", "mov"));
        var data = stream.packets().getFirst().data();
        var random = new Random(9);
        var decoder = new CfhdDecoder(stream.track(), VideoDecoderOptions.defaults());
        for (var length = 0; length < data.length; length += Math.max(1, data.length / 89)) {
            var cut = Arrays.copyOf(data, length);
            try {
                decoder.decodeFrame(cut, 0, cut.length);
            } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded expected) {
                // a truncated sample may fail; anything but Malformed fails the test
            }
        }
        for (var flip = 0; flip < 300; flip++) {
            var damaged = data.clone();
            damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
            try {
                decoder.decodeFrame(damaged, 0, damaged.length);
            } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded expected) {
                // a damaged sample may fail; anything but Malformed fails the test
            }
        }
    }

    /**
     * Reads a vector of tools/testdata/cfhd/make-vectors.sh after checking its pinned SHA-256: each sample with
     * GoPro's decode of it, empty for a sequence header.
     */
    static List<byte[][]> vector(String name) throws java.io.IOException, java.security.NoSuchAlgorithmException {
        byte[] data;
        try (var in = CfhdDifferentialTest.class.getResourceAsStream("sdk/" + name);
             var pins = CfhdDifferentialTest.class.getResourceAsStream("sdk.sha256")) {
            data = in.readAllBytes();
            var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(data));
            assertTrue(new String(pins.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII)
                    .contains(digest + " *" + name), name + " does not match its pinned SHA-256");
        }
        assertEquals("CFHDVEC1", new String(data, 0, 8, java.nio.charset.StandardCharsets.US_ASCII));
        var frames = new java.util.ArrayList<byte[][]>();
        var buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).position(8);
        while (buffer.hasRemaining()) {
            var sample = new byte[buffer.getInt()];
            buffer.get(sample);
            var decode = new byte[buffer.getInt()];
            buffer.get(decode);
            frames.add(new byte[][]{sample, decode});
        }
        return frames;
    }

    /** Lays a picture out as GoPro's YU64 (Y0 Cr Y1 Cb), RG48 or B64A (A R G B) decode: 16-bit little-endian samples. */
    static byte[] sdkLayout(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case Yuv16Picture yuv -> {
                var shift = 16 - yuv.bitDepth();
                for (var y = 0; y < yuv.height(); y++) {
                    for (var x = 0; x < yuv.width(); x += 2) {
                        int[] values = {yuv.y()[y * yuv.yStride() + x], yuv.v()[y * yuv.uvStride() + x / 2],
                                yuv.y()[y * yuv.yStride() + x + 1], yuv.u()[y * yuv.uvStride() + x / 2]};
                        for (var value : values) {
                            var wide = value << shift;
                            out.write(wide);
                            out.write(wide >>> 8);
                        }
                    }
                }
            }
            case Rgba16Picture rgba -> {
                var channels = rgba.hasAlpha() ? new int[]{3, 0, 1, 2} : new int[]{0, 1, 2};
                for (var y = 0; y < rgba.height(); y++) {
                    for (var x = 0; x < rgba.width(); x++) {
                        for (var c : channels) {
                            var value = rgba.pixels()[y * rgba.stride() + x * 4 + c];
                            out.write(value);
                            out.write(value >>> 8);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException(picture.toString());
        }
        return out.toByteArray();
    }

    /** Returns 16-bit little-endian samples reduced to a precision. */
    static int[] reduce(byte[] samples, int precision) {
        var out = new int[samples.length / 2];
        for (var i = 0; i < out.length; i++) {
            out[i] = ((samples[2 * i] & 0xFF) | (samples[2 * i + 1] & 0xFF) << 8) >>> (16 - precision);
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"yuv422-intra-filmscan1.cfhd,10,128,64", "yuv422-gop2-filmscan1.cfhd,10,128,64",
            "yuv422-gop2-low.cfhd,10,128,64", "yuv422-interlaced-medium.cfhd,10,128,64", "rgb444-filmscan1.cfhd,12,128,64",
            "rgba4444-high.cfhd,12,128,64", "yuv422-intra-176x90-medium.cfhd,10,176,90",
            "yuv422-gop2-176x90-high.cfhd,10,176,90", "rgb444-176x90-filmscan1.cfhd,12,176,90"})
    @DisplayName("decodes GoPro's samples as GoPro's decoder does, groups of two frames and padded band rows included")
    void decodesGoPro(String name, int precision, int width, int height) throws Exception {
        var track = com.github.auties00.smalt.track.VideoTrack.builder().id(1).codec(Codec.CFHD)
                .timeBase(new com.github.auties00.smalt.track.Rational(1, 600)).width(width).height(height).build();
        var decoder = new CfhdDecoder(track, VideoDecoderOptions.defaults());
        var frames = 0;
        for (var entry : vector(name)) {
            var picture = decoder.decodeFrame(entry[0], 0, entry[0].length);
            if (entry[1].length == 0) {
                assertEquals(null, picture, name + " sequence header");
                continue;
            }
            frames++;
            assertEquals(width, picture.width());
            assertEquals(height, picture.height());
            GOPRO.check(name + " frame " + frames, Tolerance.Difference.of(reduce(entry[1], precision),
                    reduce(sdkLayout(picture), precision)));
        }
        assertEquals(name.contains("gop2") ? 3 : 4, frames, name);
    }

    @Test
    @DisplayName("rejects a group's frame sample without its group")
    void orphanFrame() {
        var stream = IntraTesting.demux(sample("yuv422p10le", "medium", "mov"));
        var decoder = new CfhdDecoder(stream.track(), VideoDecoderOptions.defaults());
        byte[] frame = {0, 1, 0, 1};
        org.junit.jupiter.api.Assertions.assertThrows(MediaException.Malformed.class,
                () -> decoder.decodeFrame(frame, 0, frame.length));
    }
}
