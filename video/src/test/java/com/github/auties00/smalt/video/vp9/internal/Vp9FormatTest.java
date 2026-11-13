package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers the chroma subsamplings of profiles 1 and 3 and the color signalling of the real-time
 * engine: every stream decodes with the module's decoder to exactly the encoder's reconstruction,
 * FFmpeg's native and libvpx decoders reproduce it byte for byte, and the key frame headers carry the
 * profile, subsampling, color space and range that were asked for.
 */
@DisplayName("VP9 encoder picture formats")
class Vp9FormatTest {
    static final long TICK = Vp9RtEncoder.TICKS_PER_SECOND / 30;

    @TempDir
    Path temp;

    static ChromaFormat inputFormat(int ssX, int ssY) {
        if (ssX == 1 && ssY == 1) {
            return ChromaFormat.YUV420;
        }
        return ssX == 1 ? ChromaFormat.YUV422 : ChromaFormat.YUV444;
    }

    /** Returns the visible planes of a frame, back to back, as FFmpeg's raw planar output lays them out. */
    static byte[] planes(Vp9Frame frame) {
        var out = new ByteArrayOutputStream();
        for (var plane = 0; plane < 3; plane++) {
            var w = frame.cropWidths[plane];
            for (var y = 0; y < frame.cropHeights[plane]; y++) {
                if (frame.highBitDepth()) {
                    for (var x = 0; x < w; x++) {
                        var v = frame.planes16[plane][y * frame.strides[plane] + x];
                        out.write(v & 0xFF);
                        out.write((v >> 8) & 0xFF);
                    }
                } else {
                    out.write(frame.planes8[plane], y * frame.strides[plane], w);
                }
            }
        }
        return out.toByteArray();
    }

    static void compare(Vp9Frame expected, Vp9Frame actual, int index) {
        for (var plane = 0; plane < 3; plane++) {
            assertEquals(expected.cropWidths[plane], actual.cropWidths[plane], "plane width");
            assertEquals(expected.cropHeights[plane], actual.cropHeights[plane], "plane height");
            for (var y = 0; y < expected.cropHeights[plane]; y++) {
                for (var x = 0; x < expected.cropWidths[plane]; x++) {
                    var e = expected.highBitDepth() ? expected.planes16[plane][y * expected.strides[plane] + x]
                            : expected.planes8[plane][y * expected.strides[plane] + x] & 0xFF;
                    var a = actual.highBitDepth() ? actual.planes16[plane][y * actual.strides[plane] + x]
                            : actual.planes8[plane][y * actual.strides[plane] + x] & 0xFF;
                    if (e != a) {
                        fail("frame " + index + " plane " + plane + " differs at " + x + "," + y + ": encoder " + e
                                + ", decoder " + a);
                    }
                }
            }
        }
    }

    record Encoded(List<byte[]> packets, byte[] reconstructions) {
    }

    static Encoded encode(Vp9RtEncoder enc, List<YuvPicture> pictures, int forceKeyAt) {
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        var packets = new ArrayList<byte[]>();
        var recon = new ByteArrayOutputStream();
        for (var i = 0; i < pictures.size(); i++) {
            var data = enc.encode(pictures.get(i), i * TICK, (i + 1) * TICK, i == forceKeyAt);
            assertNotNull(data, "frame " + i);
            packets.add(data);
            recon.writeBytes(planes(enc.recon));
            decoder.decodePacket(data, 0, data.length);
            var frame = decoder.shownFrame();
            assertNotNull(frame, "frame " + i);
            compare(enc.recon, frame, i);
        }
        return new Encoded(packets, recon.toByteArray());
    }

    static Vp9RtEncoder encoder(int width, int height, int ssX, int ssY, int speed, int bitrate) {
        var enc = new Vp9RtEncoder(width, height, ssX, ssY, 8, 1, 0, 1, MediaLimits.DEFAULT);
        enc.configure(Vp9TestVideo.rtOptions(bitrate, speed).keyframeInterval(12).adaptiveQuantization(true)
                .build());
        return enc;
    }

    @ParameterizedTest(name = "{0}x{1} subsampling {2},{3} speed {4}")
    @CsvSource({"352, 288, 1, 0, 6", "203, 117, 1, 0, 5", "352, 288, 0, 0, 6", "97, 65, 0, 0, 8", "352, 288, 0, 1, 7",
            "130, 77, 0, 1, 9", "352, 288, 1, 1, 6"})
    @DisplayName("every subsampling decodes to the reconstruction and signals profile 1")
    void subsampling(int width, int height, int ssX, int ssY, int speed) {
        var enc = encoder(width, height, ssX, ssY, speed, 500_000);
        var pictures = Vp9TestVideo.synthesize(width, height, 20, inputFormat(ssX, ssY));
        var encoded = encode(enc, pictures, 15);
        var header = VideoConfigs.vp9KeyFrame(encoded.packets().getFirst());
        assertNotNull(header);
        assertEquals(ssX == 1 && ssY == 1 ? 0 : 1, header.profile());
        assertEquals(8, header.bitDepth());
        assertEquals(ssX, header.subsamplingX());
        assertEquals(ssY, header.subsamplingY());
        assertEquals(width, header.width());
        assertEquals(height, header.height());
    }

    @ParameterizedTest(name = "color space {0}, full range {1}")
    @CsvSource({"0, false", "1, true", "2, false", "3, true", "4, false", "5, true"})
    @DisplayName("the key frame header carries the color space and range")
    void color(int colorSpace, boolean fullRange) {
        var enc = encoder(96, 64, 1, 1, 6, 300_000);
        enc.setColor(colorSpace, fullRange);
        var encoded = encode(enc, Vp9TestVideo.synthesize(96, 64, 3), -1);
        var header = VideoConfigs.vp9KeyFrame(encoded.packets().getFirst());
        assertNotNull(header);
        assertEquals(colorSpace, header.colorSpace());
        assertEquals(fullRange, header.fullRange());
    }

    @ParameterizedTest(name = "{0}x{1} subsampling {2},{3}, color space {4}")
    @CsvSource({"352, 288, 1, 0, 2", "203, 117, 0, 0, 1", "130, 77, 0, 1, 3", "160, 96, 0, 0, 7"})
    @DisplayName("FFmpeg's VP9 decoders reproduce the reconstruction of every subsampling")
    void ffmpeg(int width, int height, int ssX, int ssY, int colorSpace) throws Exception {
        Skip.unless(Vp9RtInteropTest.ffmpegAvailable(), "oracle:ffmpeg", "ffmpeg not available");
        var enc = encoder(width, height, ssX, ssY, 6, 400_000);
        enc.setColor(colorSpace, colorSpace == 7);
        var encoded = encode(enc, Vp9TestVideo.synthesize(width, height, 16, inputFormat(ssX, ssY)), 9);
        var ivf = temp.resolve("stream.ivf");
        Files.write(ivf, Vp9RtQualityTool.ivf(width, height, encoded.packets()));
        String pixelFormat;
        if (colorSpace == 7) {
            pixelFormat = "gbrp";
        } else if (ssX == 1) {
            pixelFormat = ssY == 1 ? "yuv420p" : "yuv422p";
        } else {
            pixelFormat = ssY == 1 ? "yuv440p" : "yuv444p";
        }
        var want = Vp9RtInteropTest.md5(encoded.reconstructions());
        assertEquals(want, Vp9RtInteropTest.md5(decode(ivf, "vp9", pixelFormat)), "ffmpeg native vp9 decoder");
        assertEquals(want, Vp9RtInteropTest.md5(decode(ivf, "libvpx-vp9", pixelFormat)), "libvpx decoder");
    }

    static Encoded encode16(Vp9RtEncoder enc, List<Yuv16Picture> pictures, int forceKeyAt) {
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        var packets = new ArrayList<byte[]>();
        var recon = new ByteArrayOutputStream();
        for (var i = 0; i < pictures.size(); i++) {
            var data = enc.encode(pictures.get(i), i * TICK, (i + 1) * TICK, i == forceKeyAt);
            assertNotNull(data, "frame " + i);
            packets.add(data);
            recon.writeBytes(planes(enc.recon));
            decoder.decodePacket(data, 0, data.length);
            var frame = decoder.shownFrame();
            assertNotNull(frame, "frame " + i);
            compare(enc.recon, frame, i);
        }
        return new Encoded(packets, recon.toByteArray());
    }

    static Vp9RtEncoder encoder(int width, int height, int ssX, int ssY, int bitDepth, int speed, int bitrate) {
        var enc = new Vp9RtEncoder(width, height, ssX, ssY, bitDepth, 1, 0, 1, MediaLimits.DEFAULT);
        enc.configure(Vp9TestVideo.rtOptions(bitrate, speed).keyframeInterval(12).adaptiveQuantization(true)
                .build());
        return enc;
    }

    @ParameterizedTest(name = "{0}x{1} subsampling {2},{3}, {4} bits, speed {5}")
    @CsvSource({"352, 288, 1, 1, 10, 6", "203, 117, 1, 1, 12, 5", "176, 144, 1, 0, 10, 7", "97, 65, 0, 0, 12, 8",
            "130, 77, 0, 1, 10, 9", "160, 96, 0, 0, 10, 6"})
    @DisplayName("10 and 12-bit streams decode to the reconstruction and signal profiles 2 and 3")
    void highBitDepth(int width, int height, int ssX, int ssY, int bitDepth, int speed) {
        var enc = encoder(width, height, ssX, ssY, bitDepth, speed, 600_000);
        var pictures = Vp9TestVideo.synthesize16(width, height, 18, inputFormat(ssX, ssY), bitDepth);
        var encoded = encode16(enc, pictures, 13);
        var header = VideoConfigs.vp9KeyFrame(encoded.packets().getFirst());
        assertNotNull(header);
        assertEquals(ssX == 1 && ssY == 1 ? 2 : 3, header.profile());
        assertEquals(bitDepth, header.bitDepth());
        assertEquals(ssX, header.subsamplingX());
        assertEquals(ssY, header.subsamplingY());
        var psnr = psnr16(pictures.getLast(), enc.recon, bitDepth);
        assertTrue(psnr > 30, "psnr " + psnr);
    }

    @ParameterizedTest(name = "{0}x{1} subsampling {2},{3}, {4} bits")
    @CsvSource({"352, 288, 1, 1, 10", "203, 117, 1, 0, 12", "130, 77, 0, 1, 10", "97, 65, 0, 0, 12"})
    @DisplayName("FFmpeg's VP9 decoders reproduce the reconstruction of 10 and 12-bit streams")
    void ffmpegHighBitDepth(int width, int height, int ssX, int ssY, int bitDepth) throws Exception {
        Skip.unless(Vp9RtInteropTest.ffmpegAvailable(), "oracle:ffmpeg", "ffmpeg not available");
        var enc = encoder(width, height, ssX, ssY, bitDepth, 6, 500_000);
        var encoded = encode16(enc, Vp9TestVideo.synthesize16(width, height, 14, inputFormat(ssX, ssY), bitDepth), 9);
        var ivf = temp.resolve("stream.ivf");
        Files.write(ivf, Vp9RtQualityTool.ivf(width, height, encoded.packets()));
        var layout = ssX == 1 ? (ssY == 1 ? "yuv420p" : "yuv422p") : (ssY == 1 ? "yuv440p" : "yuv444p");
        var pixelFormat = layout + bitDepth + "le";
        var want = Vp9RtInteropTest.md5(encoded.reconstructions());
        assertEquals(want, Vp9RtInteropTest.md5(decode(ivf, "vp9", pixelFormat)), "ffmpeg native vp9 decoder");
        assertEquals(want, Vp9RtInteropTest.md5(decode(ivf, "libvpx-vp9", pixelFormat)), "libvpx decoder");
    }

    @Test
    @DisplayName("lossless 10-bit frames reconstruct the source exactly")
    void lossless16() {
        var enc = new Vp9RtEncoder(96, 64, 1, 1, 10, 1, 0, 1, MediaLimits.DEFAULT);
        enc.configure(Vp9TestVideo.rtOptions(2_000_000, 6).quantizers(0, 0).build());
        var pictures = Vp9TestVideo.synthesize16(96, 64, 4, ChromaFormat.YUV420, 10);
        encode16(enc, pictures, -1);
        var last = pictures.getLast();
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 96; x++) {
                assertEquals(last.y()[y * last.yStride() + x], enc.recon.planes16[0][y * enc.recon.strides[0] + x]);
            }
        }
    }

    static double psnr16(Yuv16Picture source, Vp9Frame recon, int bitDepth) {
        long sse = 0;
        for (var y = 0; y < source.height(); y++) {
            for (var x = 0; x < source.width(); x++) {
                var d = source.y()[y * source.yStride() + x] - recon.planes16[0][y * recon.strides[0] + x];
                sse += (long) d * d;
            }
        }
        var peak = (1 << bitDepth) - 1.0;
        return sse == 0 ? 99 : 10 * Math.log10(peak * peak * source.width() * source.height() / sse);
    }

    static byte[] decode(Path ivf, String decoder, String pixelFormat) throws Exception {
        var process = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-c:v", decoder, "-i",
                ivf.toString(), "-f", "rawvideo", "-pix_fmt", pixelFormat, "-").start();
        var errors = new ByteArrayOutputStream();
        var drain = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(errors);
            } catch (java.io.IOException ignored) {
                // the process ended
            }
        });
        var output = process.getInputStream().readAllBytes();
        var exit = process.waitFor();
        drain.join();
        assertEquals(0, exit, "ffmpeg " + decoder + " failed: " + errors);
        return output;
    }
}
