package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.video.vp9.Vp9EncoderOptions;
import com.github.auties00.smalt.video.vp9.Vp9EncoderSpeed;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Test video for the real-time VP9 encoder: synthetic natural-looking motion and the Xiph clips
 * cached by earlier work, when present.
 */
public final class Vp9TestVideo {
    private Vp9TestVideo() {
    }

    // The real-time settings the internal tests start from: 30 fps, quantizers 2 to 52, no periodic key frames,
    // no frame dropping and a one second buffer starting half full and aiming for 600 ms.
    public static Vp9EncoderOptions.Builder rtOptions(long bitrate, int speed) {
        return Vp9EncoderOptions.builder()
                .bitrate(bitrate)
                .speed(Vp9EncoderSpeed.of(speed))
                .quantizers(2, 52)
                .noPeriodicKeyframes()
                .bufferSize(Duration.ofMillis(1000))
                .initialBufferLevel(Duration.ofMillis(500))
                .optimalBufferLevel(Duration.ofMillis(600));
    }

    /** Synthesizes smooth value noise, a sinusoid and a moving spot, panning at sub-pixel speeds. */
    public static List<YuvPicture> synthesize(int width, int height, int frames) {
        var lattice = new int[64 * 64];
        var seed = 0x9E3779B9L;
        for (var i = 0; i < lattice.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            lattice[i] = (int) (seed >>> 56);
        }
        var pictures = new ArrayList<YuvPicture>(frames);
        for (var t = 0; t < frames; t++) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            var y = picture.y();
            for (var row = 0; row < height; row++) {
                for (var column = 0; column < width; column++) {
                    var u = (column + t * 1.37) / 9.0;
                    var v = (row - t * 0.61) / 9.0;
                    var noise = valueNoise(lattice, u, v);
                    var wave = 40 * Math.sin((column - t * 2.3) / 23.0) * Math.cos((row + t * 1.1) / 31.0);
                    var dx = column - width / 2.0 - Math.min(60, width / 4.0) * Math.sin(t / 9.0);
                    var dy = row - height / 2.0;
                    var spot = dx * dx + dy * dy < 400 ? 50 : 0;
                    y[row * picture.yStride() + column] = (byte) clamp((int) (60 + noise * 0.5 + wave + spot));
                }
            }
            for (var row = 0; row < picture.chromaHeight(); row++) {
                for (var column = 0; column < picture.chromaWidth(); column++) {
                    var noise = valueNoise(lattice, (column + t * 0.7) / 5.0 + 17, (row + t * 0.3) / 5.0 + 5);
                    picture.u()[row * picture.uvStride() + column] = (byte) clamp((int) (96 + noise / 4));
                    picture.v()[row * picture.uvStride() + column] = (byte) clamp((int) (160 - noise / 4));
                }
            }
            pictures.add(picture);
        }
        return pictures;
    }

    private static double valueNoise(int[] lattice, double u, double v) {
        var x0 = (int) Math.floor(u);
        var y0 = (int) Math.floor(v);
        var fx = u - x0;
        var fy = v - y0;
        var a = lattice[(y0 & 63) * 64 + (x0 & 63)];
        var b = lattice[(y0 & 63) * 64 + ((x0 + 1) & 63)];
        var c = lattice[((y0 + 1) & 63) * 64 + (x0 & 63)];
        var d = lattice[((y0 + 1) & 63) * 64 + ((x0 + 1) & 63)];
        var sx = fx * fx * (3 - 2 * fx);
        var sy = fy * fy * (3 - 2 * fy);
        return (a * (1 - sx) + b * sx) * (1 - sy) + (c * (1 - sx) + d * sx) * sy;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** Reads up to {@code frames} pictures of a raw planar 4:2:0 file, starting at frame {@code first}. */
    public static List<YuvPicture> read(Path file, int width, int height, int first, int frames) throws IOException {
        var size = width * height * 3 / 2;
        var pictures = new ArrayList<YuvPicture>();
        try (var in = new RandomAccessFile(file.toFile(), "r")) {
            var buffer = new byte[size];
            for (var i = 0; i < frames; i++) {
                var offset = (long) (first + i) * size;
                if (offset + size > in.length()) {
                    break;
                }
                in.seek(offset);
                in.readFully(buffer);
                var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                System.arraycopy(buffer, 0, picture.y(), 0, width * height);
                System.arraycopy(buffer, width * height, picture.u(), 0, width * height / 4);
                System.arraycopy(buffer, width * height * 5 / 4, picture.v(), 0, width * height / 4);
                pictures.add(picture);
            }
        }
        return pictures;
    }

    /**
     * Returns the path of a cached raw clip, or {@code null} when absent.
     *
     * <p>Clips are looked up in the directory named by the {@code smalt.test.clips} system property, by
     * default {@code ~/.smalt/cache/clips}; the tests that need one skip when it is missing.
     */
    public static Path clip(String name) {
        var root = Path.of(System.getProperty("smalt.test.clips",
                Path.of(System.getProperty("user.home"), ".smalt", "cache", "clips").toString()));
        var candidate = root.resolve(name);
        return Files.isRegularFile(candidate) ? candidate : null;
    }
}
