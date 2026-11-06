package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures what adaptive quantization by variance, the default, does to the extended engine's 10-bit streams:
 * at equal target bitrates on three synthetic ffmpeg sources, the stream with it must come out no more than 3%
 * larger than the stream without it, or the comparison would favor it, and ffmpeg's SSIM of ffmpeg's decode
 * against the source must not fall on average, nor in any case by more than a small margin. The measurements
 * justify the extended engine's default.
 */
class WideAdaptiveQuantizationQualityTest {
    private static final int WIDTH = 320;

    private static final int HEIGHT = 240;

    private static final int FRAMES = 40;

    private static Path source(String name) {
        var input = switch (name) {
            case "testsrc2" -> List.of("-f", "lavfi", "-i", "testsrc2=size=" + WIDTH + "x" + HEIGHT + ":rate=30");
            case "mandelbrot" -> List.of("-f", "lavfi", "-i", "mandelbrot=size=" + WIDTH + "x" + HEIGHT + ":rate=30");
            case "bars" -> List.of("-f", "lavfi", "-i", "smptehdbars=size=" + WIDTH + "x" + HEIGHT + ":rate=30,"
                    + "noise=alls=12:allf=t");
            default -> throw new IllegalArgumentException(name);
        };
        return Fixtures.ffmpeg("yuv", input, List.of("-frames:v", Integer.toString(FRAMES), "-f", "rawvideo",
                "-pix_fmt", "yuv420p10le"));
    }

    private static List<Yuv16Picture> pictures(Path raw) {
        byte[] data;
        try {
            data = Files.readAllBytes(raw);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var lumaSize = WIDTH * HEIGHT;
        var chromaSize = lumaSize / 4;
        var frameBytes = 2 * (lumaSize + 2 * chromaSize);
        var pictures = new ArrayList<Yuv16Picture>();
        for (var f = 0; f < FRAMES; f++) {
            var picture = Yuv16Picture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, 10, false, ColorInfo.BT709_LIMITED);
            var offset = f * frameBytes;
            offset = read(data, offset, picture.y(), lumaSize);
            offset = read(data, offset, picture.u(), chromaSize);
            read(data, offset, picture.v(), chromaSize);
            pictures.add(picture);
        }
        return pictures;
    }

    private static int read(byte[] data, int offset, short[] plane, int count) {
        for (var i = 0; i < count; i++) {
            plane[i] = (short) ((data[offset + 2 * i] & 0xFF) | (data[offset + 2 * i + 1] & 0xFF) << 8);
        }
        return offset + 2 * count;
    }

    /** The SSIM of a stream and its size in bytes. */
    private record Measure(double ssim, long bytes) {
    }

    private static Measure measure(Path raw, List<Yuv16Picture> pictures, H264AdaptiveQuantization mode,
                                   int bitrate) {
        var options = H264EncoderOptions.forFile().toBuilder().profile(H264Profile.HIGH_10).bitDepth(10)
                .bitrate(bitrate).frameRate(new Rational(30, 1)).timeBase(new Rational(1, 30))
                .packetFormat(H264PacketFormat.ANNEX_B).adaptiveQuantization(mode).build();
        var out = new ByteArrayOutputStream();
        try (var encoder = new H264Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i, 1, false), packet -> out.writeBytes(packet.data()));
            }
            encoder.flush((Packet packet) -> out.writeBytes(packet.data()));
        }
        var file = EncoderHarness.write("wide-aq-" + raw.getFileName() + "-" + mode + "-" + bitrate + ".264",
                out.toByteArray());
        var text = Oracle.runMerged(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "info", "-f", "h264",
                "-i", file.toString(), "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-s", WIDTH + "x" + HEIGHT,
                "-i", raw.toString(), "-lavfi", "[0:v]format=yuv420p10le[a];[a][1:v]ssim", "-f", "null", "-"));
        var matcher = Pattern.compile("SSIM .*All:([0-9.]+)").matcher(text);
        assertTrue(matcher.find(), text);
        return new Measure(Double.parseDouble(matcher.group(1)), out.size());
    }

    @Test
    @DisplayName("variance adaptive quantization keeps or raises SSIM at equal bitrates")
    void ssim() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var gains = new ArrayList<Double>();
        var sizes = new ArrayList<Double>();
        var report = new StringBuilder();
        for (var name : List.of("testsrc2", "mandelbrot", "bars")) {
            var raw = source(name);
            var pictures = pictures(raw);
            for (var bitrate : List.of(250_000, 800_000)) {
                var none = measure(raw, pictures, H264AdaptiveQuantization.NONE, bitrate);
                var variance = measure(raw, pictures, H264AdaptiveQuantization.VARIANCE, bitrate);
                gains.add(variance.ssim() - none.ssim());
                sizes.add((double) variance.bytes() / none.bytes());
                report.append(String.format("%s %d: none %.5f (%d bytes) variance %.5f (%d bytes)%n", name, bitrate,
                        none.ssim(), none.bytes(), variance.ssim(), variance.bytes()));
            }
        }
        System.out.print(report);
        assertTrue(sizes.stream().allMatch(ratio -> ratio <= 1.03), "adaptive quantization spends more\n" + report);
        var mean = gains.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertTrue(mean >= 0, "mean SSIM change " + mean + "\n" + report);
        assertTrue(gains.stream().allMatch(gain -> gain > -0.003), report.toString());
    }
}
