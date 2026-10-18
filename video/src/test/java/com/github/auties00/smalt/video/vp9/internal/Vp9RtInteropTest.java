package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the real-time VP9 encoder's streams with FFmpeg, used only as a black box, with both
 * its native VP9 decoder and libvpx, and checks that the decoded pictures are exactly the
 * encoder's reconstruction. Skipped when {@code ffmpeg} is not on the path.
 */
@DisplayName("VP9 real-time encoder interoperability with FFmpeg")
class Vp9RtInteropTest {
    @TempDir
    Path temp;

    static boolean ffmpegAvailable() {
        try {
            var process = new ProcessBuilder("ffmpeg", "-hide_banner", "-version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static byte[] i420(YuvPicture picture) {
        var w = picture.width();
        var h = picture.height();
        var cw = (w + 1) / 2;
        var ch = (h + 1) / 2;
        var out = new byte[w * h + 2 * cw * ch];
        var pos = 0;
        for (var r = 0; r < h; r++, pos += w) {
            System.arraycopy(picture.y(), r * picture.yStride(), out, pos, w);
        }
        for (var r = 0; r < ch; r++, pos += cw) {
            System.arraycopy(picture.u(), r * picture.uvStride(), out, pos, cw);
        }
        for (var r = 0; r < ch; r++, pos += cw) {
            System.arraycopy(picture.v(), r * picture.uvStride(), out, pos, cw);
        }
        return out;
    }

    static byte[] decode(Path ivf, String decoder) throws IOException, InterruptedException {
        var process = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-c:v", decoder, "-i",
                ivf.toString(), "-f", "rawvideo", "-pix_fmt", "yuv420p", "-").start();
        var errors = new ByteArrayOutputStream();
        var drain = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(errors);
            } catch (IOException ignored) {
                // the process ended
            }
        });
        var output = process.getInputStream().readAllBytes();
        var exit = process.waitFor();
        drain.join();
        assertEquals(0, exit, "ffmpeg " + decoder + " failed: " + errors);
        return output;
    }

    @ParameterizedTest(name = "{0}x{1} speed {2}, resilient {3}, tiles {4}")
    @CsvSource({"352, 288, 6, true, 0", "352, 288, 8, false, 1", "203, 117, 5, true, 0", "640, 360, 7, false, 6",
            "96, 64, 9, true, 0"})
    @DisplayName("FFmpeg's VP9 decoders reproduce the reconstruction")
    void decoders(int width, int height, int speed, boolean errorResilient, int tiles) throws Exception {
        Assumptions.assumeTrue(ffmpegAvailable(), "ffmpeg not available");
        var enc = new Vp9RtEncoder(width, height, 1, tiles, 1, MediaLimits.DEFAULT);
        enc.configure(Vp9TestVideo.rtOptions(250_000, speed).keyframeInterval(10).errorResilient(errorResilient)
                .adaptiveQuantization(true).build());
        var pictures = Vp9TestVideo.synthesize(width, height, 24);
        var packets = new ArrayList<byte[]>();
        var expected = new ByteArrayOutputStream();
        var recon = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        var tick = Vp9RtEncoder.TICKS_PER_SECOND / 30;
        for (var i = 0; i < pictures.size(); i++) {
            var data = enc.encode(pictures.get(i), i * tick, (i + 1) * tick, i == 17);
            if (data != null) {
                packets.add(data);
                enc.copyReconstruction(recon);
                expected.writeBytes(i420(recon));
            }
        }
        var ivf = temp.resolve("stream.ivf");
        Files.write(ivf, Vp9RtQualityTool.ivf(width, height, packets));
        var want = md5(expected.toByteArray());
        assertEquals(want, md5(decode(ivf, "vp9")), "ffmpeg native vp9 decoder");
        assertEquals(want, md5(decode(ivf, "libvpx-vp9")), "libvpx decoder");
    }
}
