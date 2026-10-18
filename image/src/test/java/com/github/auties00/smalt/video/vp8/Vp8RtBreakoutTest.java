package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the opt-in encode breakout of the real-time VP8 encoder: streams stay decodable to the
 * encoder's reconstruction, the default stream is unchanged, and on content with static regions
 * the breakout skips macroblocks without losing quality.
 */
@DisplayName("real-time VP8 encode breakout")
class Vp8RtBreakoutTest {
    /**
     * Static gray desktop-like content: flat panels, text-like stripes and a small moving
     * window, with neutral chroma that the encoder reconstructs exactly.
     */
    static List<YuvPicture> screen(int width, int height, int frames) {
        var pictures = new ArrayList<YuvPicture>();
        for (var t = 0; t < frames; t++) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            var y = picture.y();
            for (var row = 0; row < height; row++) {
                for (var column = 0; column < width; column++) {
                    var value = column < width / 3 ? 40 : ((row / 6) % 3 == 0 && (column / 3) % 5 != 0 ? 30 : 220);
                    var wx = column - (20 + 3 * t);
                    var wy = row - (30 + t);
                    if (wx >= 0 && wx < 48 && wy >= 0 && wy < 32) {
                        value = 100 + ((wx * 7 + wy * 13 + t * 5) & 63);
                    }
                    y[row * picture.yStride() + column] = (byte) value;
                }
            }
            for (var row = 0; row < picture.chromaHeight(); row++) {
                for (var column = 0; column < picture.chromaWidth(); column++) {
                    picture.u()[row * picture.uvStride() + column] = (byte) 128;
                    picture.v()[row * picture.uvStride() + column] = (byte) 128;
                }
            }
            pictures.add(picture);
        }
        return pictures;
    }

    static double psnr(IntFunction<YuvPicture> frames, Vp8VideoEncoderTest.Run run) {
        var total = 0.0;
        for (var i = 0; i < run.recon().size(); i++) {
            total += Quality.psnr(Ivf.i420(frames.apply(i)), run.recon().get(i));
        }
        return total / run.recon().size();
    }

    static void record(String name, double plainPsnr, long plainBytes, double brokenPsnr, long brokenBytes)
            throws IOException {
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "vp8rt-breakout.txt"),
                String.format("%s: off %.3f dB %d bytes, threshold 1 %.3f dB %d bytes%n", name, plainPsnr, plainBytes,
                        brokenPsnr, brokenBytes),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    static long bytes(Vp8VideoEncoderTest.Run run) {
        return run.packets().stream().mapToLong(packet -> packet.data().length).sum();
    }

    @Test
    @DisplayName("a zero threshold is the default and an out-of-range one is rejected")
    void defaults() {
        var config = Vp8VideoEncoderTest.config(176, 144, 200_000);
        assertEquals(0, config.encodeBreakout());
        assertEquals(1, config.withEncodeBreakout(1).encodeBreakout());
        assertThrows(IllegalArgumentException.class, () -> config.withEncodeBreakout(-1));
        assertThrows(IllegalArgumentException.class, () -> config.withEncodeBreakout((1 << 24) + 1));
    }

    @Test
    @DisplayName("on screen-like content WebRTC's threshold decodes to the reconstruction within half a decibel")
    void screenContent() throws IOException {
        var frames = screen(320, 240, 40);
        var config = Vp8VideoEncoderTest.with(Vp8VideoEncoderTest.config(320, 240, 300_000), 6, true, 1, 1, 1);
        var plain = Vp8VideoEncoderTest.encode(config, frames::get, frames.size(), true, i -> false, null);
        var broken = Vp8VideoEncoderTest.encode(config.withEncodeBreakout(1), frames::get, frames.size(), true,
                i -> false, null);
        Vp8VideoEncoderTest.assertFfmpegMatches(broken);
        var plainPsnr = psnr(frames::get, plain);
        var brokenPsnr = psnr(frames::get, broken);
        record("screen", plainPsnr, bytes(plain), brokenPsnr, bytes(broken));
        // the skipped residuals are bounded by a sixteenth of the squared step per macroblock
        assertTrue(brokenPsnr > plainPsnr - 0.5, "psnr " + brokenPsnr + " against " + plainPsnr);
        assertTrue(bytes(broken) <= bytes(plain) * 1.02, "size " + bytes(broken) + " against " + bytes(plain));
    }

    @Test
    @DisplayName("on a natural clip WebRTC's threshold decodes to the reconstruction and keeps quality")
    void naturalClip() throws IOException {
        var frames = Vp8VideoEncoderTest.clipOrSynthetic("johnny480.yuv", 640, 480, 30);
        var config = Vp8VideoEncoderTest.with(Vp8VideoEncoderTest.config(640, 480, 600_000), 6, true, 1, 1, 1);
        var plain = Vp8VideoEncoderTest.encode(config, frames, 30, true, i -> false, null);
        var broken = Vp8VideoEncoderTest.encode(config.withEncodeBreakout(1), frames, 30, true, i -> false, null);
        Vp8VideoEncoderTest.assertFfmpegMatches(broken);
        record("johnny480", psnr(frames, plain), bytes(plain), psnr(frames, broken), bytes(broken));
        assertTrue(psnr(frames, broken) > psnr(frames, plain) - 0.1);
        assertTrue(bytes(broken) <= bytes(plain) * 1.02);
        for (Packet packet : broken.packets()) {
            assertTrue(packet.data().length > 0);
        }
    }
}
