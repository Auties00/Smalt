package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the encoder's rate-distortion performance against libx264 (run through ffmpeg as a
 * black box) at equal bitrates on synthetic sources and a freely redistributable natural clip:
 * PSNR and SSIM are measured on ffmpeg's decode of both streams against the same source.
 */
class QualityTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FRAMES = 60;
    private static final int FPS = 30;

    /** The Xiph "foreman" CIF clip (ITU-T/COST test sequence), pinned by hash. */
    private static final String FOREMAN_URL = "https://media.xiph.org/video/derf/y4m/foreman_cif.y4m";
    private static final String FOREMAN_SHA256 = "b0e3c35eff9fe1da91521a02eb9f0e3ef4bc5aec341ecef1567cd87f1711267b";

    private static Path source(String name) {
        var input = switch (name) {
            case "testsrc2" -> List.of("-f", "lavfi", "-i", "testsrc2=size=" + WIDTH + "x" + HEIGHT + ":rate=" + FPS);
            case "mandelbrot" -> List.of("-f", "lavfi", "-i", "mandelbrot=size=" + WIDTH + "x" + HEIGHT + ":rate=" + FPS);
            case "bars" -> List.of("-f", "lavfi", "-i", "smptehdbars=size=" + WIDTH + "x" + HEIGHT + ":rate=" + FPS
                    + ",noise=alls=12:allf=t");
            case "foreman" -> List.of("-i", Conformance.fetch("foreman_cif.y4m", FOREMAN_URL, FOREMAN_SHA256).toString(),
                    "-vf", "scale=" + WIDTH + ":" + HEIGHT);
            default -> throw new IllegalArgumentException(name);
        };
        return Fixtures.ffmpeg("yuv", input, List.of("-frames:v", Integer.toString(FRAMES), "-f", "rawvideo", "-pix_fmt",
                "yuv420p"));
    }

    private static List<YuvPicture> pictures(Path raw) {
        byte[] data;
        try {
            data = Files.readAllBytes(raw);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var frameSize = WIDTH * HEIGHT * 3 / 2;
        var pictures = new ArrayList<YuvPicture>();
        for (var i = 0; i < FRAMES; i++) {
            var y = new byte[WIDTH * HEIGHT];
            var u = new byte[WIDTH * HEIGHT / 4];
            var v = new byte[WIDTH * HEIGHT / 4];
            System.arraycopy(data, i * frameSize, y, 0, y.length);
            System.arraycopy(data, i * frameSize + y.length, u, 0, u.length);
            System.arraycopy(data, i * frameSize + y.length + u.length, v, 0, v.length);
            pictures.add(YuvPicture.builder().size(WIDTH, HEIGHT).chromaFormat(ChromaFormat.YUV420)
                    .color(ColorInfo.BT709_LIMITED)
                    .luma(y, WIDTH)
                    .chroma(u, v, WIDTH / 2)
                    .build());
        }
        return pictures;
    }

    /** Returns {kbps, psnr, ssim} of our encoder. */
    private static double[] ours(Path raw, H264EncoderSpeed speed, H264Profile profile, int kbps) {
        var config = H264Setup.of(WIDTH, HEIGHT, new Rational(FPS, 1), new Rational(1, FPS), profile, 0,
                H264RateControl.AVERAGE_BITRATE, kbps * 1000, 0, 0, 23, 2 * FPS, speed, ColorInfo.BT709_LIMITED, 1);
        var encoded = EncoderHarness.encode(config, pictures(raw));
        var file = EncoderHarness.write("quality-" + raw.getFileName() + "-" + speed + "-" + kbps + ".264",
                encoded.annexB());
        var metrics = EncoderReport.measure(raw, file, WIDTH, HEIGHT, FPS, FRAMES);
        var bits = encoded.packets().stream().mapToLong(Packet::size).sum() * 8.0;
        return new double[]{bits * FPS / FRAMES / 1000, metrics[0], metrics[1]};
    }

    private static double[] x264(Path raw, String preset, String profile, int kbps) {
        var out = EncoderHarness.workFile("quality-x264-" + raw.getFileName() + "-" + preset + "-" + kbps + ".264");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-y", "-f", "rawvideo", "-pix_fmt",
                "yuv420p", "-s", WIDTH + "x" + HEIGHT, "-r", Integer.toString(FPS), "-i", raw.toString(), "-c:v",
                "libx264", "-preset", preset, "-profile:v", profile, "-bf", "0", "-b:v", kbps + "k", "-maxrate",
                (2 * kbps) + "k", "-bufsize", (2 * kbps) + "k", "-g", Integer.toString(2 * FPS), "-threads", "1", "-f",
                "h264", out.toString()));
        var metrics = EncoderReport.measure(raw, out, WIDTH, HEIGHT, FPS, FRAMES);
        long size;
        try {
            size = Files.size(out);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return new double[]{size * 8.0 * FPS / FRAMES / 1000, metrics[0], metrics[1]};
    }

    /**
     * Returns our PSNR at a given bitrate, interpolated in the log-rate domain between encodes
     * aimed 10% below and 10% above it.
     */
    private static double oursAt(Path raw, H264EncoderSpeed speed, H264Profile profile, double rate) {
        var low = ours(raw, speed, profile, (int) Math.round(rate * 0.9));
        var high = ours(raw, speed, profile, (int) Math.round(rate * 1.1));
        var slope = (high[1] - low[1]) / Math.log(high[0] / low[0]);
        return low[1] + slope * Math.log(rate / low[0]);
    }

    @ParameterizedTest(name = "{0} at {1} kbps")
    @CsvSource({"testsrc2, 300", "mandelbrot, 500", "bars, 600", "foreman, 250"})
    @DisplayName("BALANCED Main is within 1 dB of x264 veryfast (Main, no B-frames) in PSNR at equal bitrate on two-second clips")
    void balancedVersusX264(String name, int kbps) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var raw = source(name);
        var reference = x264(raw, "veryfast", "main", kbps);
        var ours = oursAt(raw, H264EncoderSpeed.BALANCED, H264Profile.MAIN, reference[0]);
        assertTrue(ours >= reference[1] - 1.0, name + ": ours " + ours + " dB, x264 " + reference[1] + " dB at "
                + reference[0] + " kbps");
    }

    @ParameterizedTest(name = "{0} at {1} kbps")
    @CsvSource({"testsrc2, 300", "foreman, 250"})
    @DisplayName("FASTEST Constrained Baseline beats x264 ultrafast Baseline in PSNR at equal bitrate")
    void fastestVersusUltrafast(String name, int kbps) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var raw = source(name);
        var reference = x264(raw, "ultrafast", "baseline", kbps);
        var ours = oursAt(raw, H264EncoderSpeed.FASTEST, H264Profile.CONSTRAINED_BASELINE, reference[0]);
        assertTrue(ours >= reference[1], name + ": ours " + ours + " dB, x264 " + reference[1] + " dB at "
                + reference[0] + " kbps");
    }

    @Test
    @DisplayName("slower speeds never lose quality to faster ones")
    void speedsAreOrdered() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var raw = source("foreman");
        var previous = Double.NEGATIVE_INFINITY;
        for (var speed : H264EncoderSpeed.values()) {
            var psnr = oursAt(raw, speed, H264Profile.HIGH, 250);
            assertTrue(psnr >= previous - 0.05, speed + " at " + psnr + " dB after " + previous);
            previous = psnr;
        }
    }
}
