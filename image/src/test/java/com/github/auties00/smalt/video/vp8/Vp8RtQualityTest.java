package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measures PSNR-Y against bitrate for this encoder and for libvpx (run through ffmpeg as a black
 * box at {@code -deadline realtime -cpu-used -6}, one-pass CBR with the same buffer, quantizer
 * window and no key frames after the first), on the cached Xiph clips, and reports the
 * Bjontegaard rate difference. It runs only with {@code -Dvp8rt.quality=true}, writing its table
 * to {@code vp8rt.quality.out}.
 */
@EnabledIfSystemProperty(named = "vp8rt.quality", matches = "true",
        disabledReason = "[opt-in:vp8rt-quality] set -Dvp8rt.quality=true to run the quality report")
@DisplayName("real-time VP8 quality against libvpx")
class Vp8RtQualityTest {
    record Clip(String name, int width, int height, int[] kbps) {
    }

    record Point(double kbps, double psnr) {
    }

    static final List<Clip> CLIPS = List.of(
            new Clip("foreman.yuv", 352, 288, new int[]{150, 250, 400, 650}),
            new Clip("johnny480.yuv", 640, 480, new int[]{200, 350, 600, 1000}),
            new Clip("kriste480.yuv", 640, 480, new int[]{200, 350, 600, 1000}),
            new Clip("stock480.yuv", 640, 480, new int[]{400, 700, 1100, 1800}),
            new Clip("johnny720.yuv", 1280, 720, new int[]{600, 900, 1400, 2200}),
            new Clip("stockholm720.yuv", 1280, 720, new int[]{900, 1500, 2400, 3800}));

    static YuvPicture readFrame(RandomAccessFile in, int width, int height, int index) throws IOException {
        var size = width * height * 3 / 2;
        var buffer = new byte[size];
        in.seek((long) index * size);
        in.readFully(buffer);
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        System.arraycopy(buffer, 0, picture.y(), 0, width * height);
        System.arraycopy(buffer, width * height, picture.u(), 0, width * height / 4);
        System.arraycopy(buffer, width * height * 5 / 4, picture.v(), 0, width * height / 4);
        return picture;
    }

    static double psnrY(byte[] a, int aOff, byte[] b, int bOff, int count) {
        var squared = 0.0;
        for (var i = 0; i < count; i++) {
            var d = (a[aOff + i] & 0xFF) - (b[bOff + i] & 0xFF);
            squared += d * d;
        }
        return squared == 0 ? 100 : 10 * Math.log10(255.0 * 255.0 * count / squared);
    }

    static Point ours(Path clip, Clip spec, int kbps, int frames, boolean errorResilient) throws IOException {
        var config = Vp8Setup.of(spec.width(), spec.height(), new Rational(30, 1), new Rational(1, 30),
                kbps * 1000, 4, 63, 0, 6, errorResilient, 1, false, 1000, 500, 833, 1, 1);
        var bytes = 0L;
        var psnr = 0.0;
        var recon = YuvPicture.allocate(spec.width(), spec.height(), ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        try (var in = new RandomAccessFile(clip.toFile(), "r"); var encoder = config.encoder()) {
            encoder.engine().keepReconstruction(true);
            for (var i = 0; i < frames; i++) {
                var source = readFrame(in, spec.width(), spec.height(), i);
                var packet = encoder.encodeFrame(new VideoFrame(source, i, 1, false)).orElse(null);
                bytes += packet.size();
                encoder.engine().copyReconstruction(recon);
                psnr += psnrY(source.y(), 0, recon.y(), 0, spec.width() * spec.height());
            }
        }
        return new Point(bytes * 8.0 * 30 / frames / 1000, psnr / frames);
    }

    static Point libvpx(Path clip, Clip spec, int kbps, int frames, boolean errorResilient, Path work) throws IOException {
        var ivf = work.resolve("libvpx.ivf");
        var yuv = work.resolve("libvpx.yuv");
        var arguments = new ArrayList<>(List.of("-hide_banner", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "yuv420p",
                "-s", spec.width() + "x" + spec.height(), "-r", "30", "-i", clip.toString(), "-frames:v", Integer.toString(frames),
                "-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "-6", "-b:v", kbps + "k", "-minrate", kbps + "k",
                "-maxrate", kbps + "k", "-bufsize", kbps + "k", "-rc_init_occupancy", (kbps / 2) + "k", "-qmin", "4",
                "-qmax", "63", "-g", "100000", "-auto-alt-ref", "0", "-lag-in-frames", "0", "-threads", "1",
                "-drop-threshold", "0"));
        if (errorResilient) {
            arguments.addAll(List.of("-error-resilient", "default"));
        }
        arguments.add(ivf.toString());
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(10), arguments);
        var bytes = 0L;
        for (var frame : Ivf.parse(Files.readAllBytes(ivf)).frames()) {
            bytes += frame.data().length;
        }
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(10), List.of("-v", "error", "-y", "-i", ivf.toString(), "-f", "rawvideo",
                "-pix_fmt", "yuv420p", yuv.toString()));
        var psnr = 0.0;
        var count = 0;
        try (var in = new RandomAccessFile(clip.toFile(), "r"); var out = new RandomAccessFile(yuv.toFile(), "r")) {
            var decodedFrames = (int) (out.length() / (spec.width() * spec.height() * 3L / 2));
            for (var i = 0; i < Math.min(frames, decodedFrames); i++) {
                var source = readFrame(in, spec.width(), spec.height(), i);
                var decoded = readFrame(out, spec.width(), spec.height(), i);
                psnr += psnrY(source.y(), 0, decoded.y(), 0, spec.width() * spec.height());
                count++;
            }
        }
        if (count != frames) {
            throw new IllegalStateException("libvpx produced " + count + " of " + frames + " frames");
        }
        return new Point(bytes * 8.0 * 30 / frames / 1000, psnr / frames);
    }

    /**
     * Returns the Bjontegaard rate difference of a test curve against a reference curve, in
     * percent: the mean log-rate gap of their cubic fits over the shared PSNR interval.
     */
    static double bdRate(List<Point> reference, List<Point> test) {
        var low = Math.max(reference.stream().mapToDouble(Point::psnr).min().orElseThrow(),
                test.stream().mapToDouble(Point::psnr).min().orElseThrow());
        var high = Math.min(reference.stream().mapToDouble(Point::psnr).max().orElseThrow(),
                test.stream().mapToDouble(Point::psnr).max().orElseThrow());
        var a = integral(fit(reference), low, high);
        var b = integral(fit(test), low, high);
        return (Math.pow(10, (b - a) / (high - low)) - 1) * 100;
    }

    /** Fits log10(rate) as a cubic polynomial of PSNR by least squares. */
    static double[] fit(List<Point> points) {
        var n = 4;
        var matrix = new double[n][n + 1];
        for (var p : points) {
            var powers = new double[2 * n];
            powers[0] = 1;
            for (var k = 1; k < powers.length; k++) {
                powers[k] = powers[k - 1] * p.psnr();
            }
            for (var r = 0; r < n; r++) {
                for (var c = 0; c < n; c++) {
                    matrix[r][c] += powers[r + c];
                }
                matrix[r][n] += powers[r] * Math.log10(p.kbps());
            }
        }
        for (var col = 0; col < n; col++) {
            var pivot = col;
            for (var r = col + 1; r < n; r++) {
                if (Math.abs(matrix[r][col]) > Math.abs(matrix[pivot][col])) {
                    pivot = r;
                }
            }
            var swap = matrix[col];
            matrix[col] = matrix[pivot];
            matrix[pivot] = swap;
            for (var r = 0; r < n; r++) {
                if (r != col) {
                    var factor = matrix[r][col] / matrix[col][col];
                    for (var c = col; c <= n; c++) {
                        matrix[r][c] -= factor * matrix[col][c];
                    }
                }
            }
        }
        var coefficients = new double[n];
        for (var r = 0; r < n; r++) {
            coefficients[r] = matrix[r][n] / matrix[r][r];
        }
        return coefficients;
    }

    static double integral(double[] c, double low, double high) {
        var sum = 0.0;
        for (var k = 0; k < c.length; k++) {
            sum += c[k] / (k + 1) * (Math.pow(high, k + 1) - Math.pow(low, k + 1));
        }
        return sum;
    }

    @Test
    void compare() throws IOException {
        var frames = Integer.getInteger("vp8rt.quality.frames", 300);
        var only = System.getProperty("vp8rt.quality.clips", "");
        var errorResilient = Boolean.getBoolean("vp8rt.quality.er");
        var out = Path.of(System.getProperty("vp8rt.quality.out", "quality.md"));
        var work = Files.createTempDirectory("vp8rt-quality");
        var report = new StringBuilder();
        report.append(String.format("frames %d, error resilient %s%n%n", frames, errorResilient));
        report.append("| clip | target kbps | libvpx kbps | libvpx PSNR-Y | ours kbps | ours PSNR-Y |\n|---|---|---|---|---|---|\n");
        var summary = new StringBuilder("\n| clip | BD-rate (ours vs libvpx) |\n|---|---|\n");
        var bdSum = 0.0;
        var bdCount = 0;
        for (var spec : CLIPS) {
            if (!only.isEmpty() && List.of(only.split(",")).stream().noneMatch(spec.name()::startsWith)) {
                continue;
            }
            var clip = RtTestVideo.clip(spec.name());
            if (clip == null) {
                continue;
            }
            var available = (int) (Files.size(clip) / (spec.width() * spec.height() * 3L / 2));
            var n = Math.min(frames, available);
            var reference = new ArrayList<Point>();
            var test = new ArrayList<Point>();
            for (var kbps : spec.kbps()) {
                var theirs = libvpx(clip, spec, kbps, n, errorResilient, work);
                var mine = ours(clip, spec, kbps, n, errorResilient);
                reference.add(theirs);
                test.add(mine);
                report.append(String.format(Locale.ROOT, "| %s | %d | %.1f | %.3f | %.1f | %.3f |%n", spec.name(), kbps,
                        theirs.kbps(), theirs.psnr(), mine.kbps(), mine.psnr()));
                Files.writeString(out, report.toString() + summary);
            }
            var bd = bdRate(reference, test);
            bdSum += bd;
            bdCount++;
            summary.append(String.format(Locale.ROOT, "| %s | %+.2f%% |%n", spec.name(), bd));
            Files.writeString(out, report.toString() + summary);
        }
        summary.append(String.format(Locale.ROOT, "| mean | %+.2f%% |%n", bdSum / Math.max(1, bdCount)));
        Files.writeString(out, report.toString() + summary);
        System.out.print(report.toString() + summary);
        Files.deleteIfExists(work.resolve("libvpx.ivf"));
        Files.deleteIfExists(work.resolve("libvpx.yuv"));
        Files.deleteIfExists(work);
    }
}
