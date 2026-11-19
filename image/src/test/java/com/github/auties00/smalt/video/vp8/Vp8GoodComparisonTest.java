package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer;
import com.github.auties00.smalt.video.vp8.internal.RtEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the good-quality VP8 encoder with libvpx's good-quality encoder through FFmpeg on the
 * foreman clip: both code the same pictures at the same bitrates, the module's decoder decodes
 * both streams, and the module's rate-quality curve must stay within a margin of libvpx's, the
 * Bjontegaard delta of the luma PSNR at equal rate. The measured points are printed.
 */
@DisplayName("VP8 good quality against libvpx")
class Vp8GoodComparisonTest {
    static final int WIDTH = 176;

    static final int HEIGHT = 144;

    static final int FRAMES = 60;

    @TempDir
    Path temp;

    static List<YuvPicture> foreman(int frames) throws IOException {
        var file = Conformance.fetchListed("foreman_qcif.y4m");
        var bytes = Files.readAllBytes(file);
        var pos = 0;
        while (bytes[pos] != '\n') {
            pos++;
        }
        pos++;
        var pictures = new ArrayList<YuvPicture>();
        var luma = WIDTH * HEIGHT;
        var chroma = luma / 4;
        while (pictures.size() < frames && pos < bytes.length) {
            while (bytes[pos] != '\n') {
                pos++;
            }
            pos++;
            var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            for (var y = 0; y < HEIGHT; y++) {
                System.arraycopy(bytes, pos + y * WIDTH, picture.y(), y * picture.yStride(), WIDTH);
            }
            pos += luma;
            for (var y = 0; y < HEIGHT / 2; y++) {
                System.arraycopy(bytes, pos + y * (WIDTH / 2), picture.u(), y * picture.uvStride(), WIDTH / 2);
                System.arraycopy(bytes, pos + chroma + y * (WIDTH / 2), picture.v(), y * picture.uvStride(), WIDTH / 2);
            }
            pos += 2 * chroma;
            pictures.add(picture);
        }
        return pictures;
    }

    /** Returns the bitrate in kbit/s and the mean luma PSNR of a stream decoded with the module's decoder. */
    static double[] measure(List<byte[]> packets, List<YuvPicture> source) {
        long bytes = 0;
        var psnr = 0.0;
        var shown = new int[1];
        var sum = new double[1];
        try (var decoder = new Vp8Decoder(Vp8ConformanceTest.track(WIDTH, HEIGHT))) {
            var pts = 0L;
            for (var data : packets) {
                bytes += data.length;
                decoder.decode(new Packet(1, data, pts, pts, 1, false), frame -> {
                    var picture = (YuvPicture) frame.picture();
                    var original = source.get(shown[0]);
                    var squared = 0.0;
                    for (var y = 0; y < HEIGHT; y++) {
                        for (var x = 0; x < WIDTH; x++) {
                            var d = (picture.y()[y * picture.yStride() + x] & 0xFF)
                                    - (original.y()[y * original.yStride() + x] & 0xFF);
                            squared += d * d;
                        }
                    }
                    sum[0] += squared == 0 ? 100 : 10 * Math.log10(255.0 * 255.0 * WIDTH * HEIGHT / squared);
                    shown[0]++;
                });
                pts++;
            }
        }
        psnr = sum[0] / Math.max(shown[0], 1);
        return new double[]{bytes * 8.0 * 30 / source.size() / 1000, psnr, shown[0]};
    }

    /** Encodes a clip with this module's encoder; speed -1 stands for the best quality. */
    static List<byte[]> smalt(List<YuvPicture> source, int kbps, int speed, boolean twoPass) {
        var options = Vp8EncoderOptions.ofBitrate(kbps * 1000L).toBuilder().speed(Vp8EncoderSpeed.of(Math.max(speed, 0)))
                .usage(speed < 0 ? Vp8EncoderUsage.BEST_QUALITY : Vp8EncoderUsage.GOOD_QUALITY).build();
        if (twoPass) {
            var pass = new Vp8FirstPass(WIDTH, HEIGHT, options);
            for (var i = 0; i < source.size(); i++) {
                pass.add(new VideoFrame(source.get(i), i * 3000L, 3000, false));
            }
            options = options.toBuilder().firstPassStats(pass.finish()).build();
        }
        var packets = new ArrayList<byte[]>();
        try (var encoder = new Vp8Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < source.size(); i++) {
                encoder.encode(new VideoFrame(source.get(i), i * 3000L, 3000, false), p -> packets.add(p.data()));
            }
            encoder.flush(p -> packets.add(p.data()));
        }
        return packets;
    }

    /** Encodes a clip with libvpx through FFmpeg; speed -1 stands for the best quality. */
    List<byte[]> libvpx(List<YuvPicture> source, int kbps, int speed, boolean twoPass) throws IOException {
        var raw = temp.resolve("source.yuv");
        if (!Files.exists(raw)) {
            try (var out = Files.newOutputStream(raw)) {
                for (var picture : source) {
                    out.write(Ivf.i420(picture));
                }
            }
        }
        var ivf = temp.resolve("libvpx-" + kbps + "-" + speed + "-" + twoPass + ".ivf");
        var common = List.of("-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", WIDTH + "x" + HEIGHT,
                "-r", "30", "-i", raw.toString(), "-c:v", "libvpx", "-deadline", speed < 0 ? "best" : "good",
                "-cpu-used", String.valueOf(Math.max(speed, 0)), "-b:v", kbps + "k", "-lag-in-frames", "25", "-auto-alt-ref", "1",
                "-arnr-maxframes", "7", "-arnr-strength", "5", "-g", "128", "-threads", "1");
        if (twoPass) {
            var log = temp.resolve("pass-" + kbps + "-" + speed).toString();
            var pass1 = new ArrayList<>(common);
            pass1.addAll(List.of("-pass", "1", "-passlogfile", log, "-f", "null", "-"));
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), pass1);
            var pass2 = new ArrayList<>(common);
            pass2.addAll(List.of("-pass", "2", "-passlogfile", log, "-f", "ivf", ivf.toString()));
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), pass2);
        } else {
            var pass = new ArrayList<>(common);
            pass.addAll(List.of("-f", "ivf", ivf.toString()));
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), pass);
        }
        var frames = new ArrayList<byte[]>();
        for (var frame : Ivf.parse(Files.readAllBytes(ivf)).frames()) {
            frames.add(frame.data());
        }
        return frames;
    }

    /**
     * Returns the mean luma PSNR difference of curve {@code a} over curve {@code b} at equal rate, in dB, by the
     * Bjontegaard method with piecewise linear interpolation in the log rate domain over the overlapping range.
     */
    static double bdPsnr(double[][] a, double[][] b) {
        var low = Math.max(Math.log(a[0][0]), Math.log(b[0][0]));
        var high = Math.min(Math.log(a[a.length - 1][0]), Math.log(b[b.length - 1][0]));
        if (high <= low) {
            return interpolate(a, Math.log(a[a.length / 2][0])) - interpolate(b, Math.log(a[a.length / 2][0]));
        }
        var steps = 64;
        var sum = 0.0;
        for (var i = 0; i <= steps; i++) {
            var r = low + (high - low) * i / steps;
            sum += interpolate(a, r) - interpolate(b, r);
        }
        return sum / (steps + 1);
    }

    static double interpolate(double[][] curve, double logRate) {
        for (var i = 0; i + 1 < curve.length; i++) {
            var r0 = Math.log(curve[i][0]);
            var r1 = Math.log(curve[i + 1][0]);
            if (logRate <= r1 || i + 2 == curve.length) {
                var t = (logRate - r0) / (r1 - r0);
                return curve[i][1] + t * (curve[i + 1][1] - curve[i][1]);
            }
        }
        return curve[curve.length - 1][1];
    }

    @ParameterizedTest(name = "speed {0}, two pass {1}")
    // the floors sit about 0.4 dB below the BD-PSNR measured against the CI runners' libvpx on foreman qcif
    @CsvSource({"4, false, 0.7", "4, true, -0.25", "2, true, -0.5", "0, true, -0.3", "-1, false, 0.3",
            "-1, true, -0.4"})
    @DisplayName("the rate-quality curve stays close to libvpx's good-quality and best-quality encoder")
    void bdRate(int speed, boolean twoPass, double minDelta) throws Exception {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = foreman(FRAMES);
        int[] rates = {80, 160, 320};
        var ours = new double[rates.length][];
        var theirs = new double[rates.length][];
        var report = new StringBuilder("vp8 foreman qcif " + (speed < 0 ? "best" : "speed " + speed)
                + (twoPass ? " two-pass" : " one-pass") + ":");
        for (var i = 0; i < rates.length; i++) {
            ours[i] = measure(smalt(source, rates[i], speed, twoPass), source);
            theirs[i] = measure(libvpx(source, rates[i], speed, twoPass), source);
            report.append(String.format(" target %d: smalt %.1f kbit/s %.2f dB, libvpx %.1f kbit/s %.2f dB;", rates[i],
                    ours[i][0], ours[i][1], theirs[i][0], theirs[i][1]));
        }
        var delta = bdPsnr(ours, theirs);
        report.append(String.format(" BD-PSNR %.2f dB", delta));
        System.out.println(report);
        for (var i = 0; i < rates.length; i++) {
            assertTrue(Math.abs(ours[i][0] - rates[i]) < rates[i] * 0.15, report.toString());
        }
        assertTrue(delta > minDelta, report.toString());
    }

    /** Returns libvpx's first-pass records of a clip, read from the pass log FFmpeg writes in base64. */
    List<double[]> libvpxFirstPass(List<YuvPicture> source) throws IOException {
        var raw = temp.resolve("fp-source.yuv");
        try (var out = Files.newOutputStream(raw)) {
            for (var picture : source) {
                out.write(Ivf.i420(picture));
            }
        }
        var log = temp.resolve("fp-vp8").toString();
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-y", "-f", "rawvideo", "-pix_fmt",
                "yuv420p", "-s", WIDTH + "x" + HEIGHT, "-r", "30", "-i", raw.toString(), "-c:v", "libvpx", "-deadline",
                "good", "-cpu-used", "2", "-b:v", "200k", "-threads", "1", "-pass", "1", "-passlogfile", log, "-f",
                "null", "-"));
        var bytes = java.util.Base64.getMimeDecoder().decode(Files.readString(Path.of(log + "-0.log")).strip());
        var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        var fields = FirstPassAnalyzer.FIELDS;
        var records = new ArrayList<double[]>();
        for (var at = 0; at + fields * 8 <= bytes.length; at += fields * 8) {
            var record = new double[fields];
            for (var k = 0; k < fields; k++) {
                record[k] = buffer.getDouble(at + 8 * k);
            }
            records.add(record);
        }
        return records;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the first-pass records match libvpx's first pass")
    void firstPass() throws Exception {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = foreman(FRAMES);
        var theirs = libvpxFirstPass(source);
        assertTrue(theirs.size() >= FRAMES, "libvpx records: " + theirs.size());
        var fields = FirstPassAnalyzer.FIELDS;
        var ours = new double[FRAMES * fields];
        var analyzer = new FirstPassAnalyzer(WIDTH, HEIGHT, com.github.auties00.smalt.MediaLimits.DEFAULT);
        for (var i = 0; i < FRAMES; i++) {
            analyzer.analyze(source.get(i), RtEncoder.TICKS_PER_SECOND / 30, ours, i * fields, 1);
        }
        // libvpx's VP8 record layout: frame, intra_error, coded_error, ssim_weighted_pred_err, pcnt_inter,
        // pcnt_motion, pcnt_second_ref, pcnt_neutral, MVr, mvr_abs, MVc, mvc_abs, ...
        double[] sums = new double[6];
        double[] diffs = new double[5];
        for (var i = 1; i < FRAMES; i++) {
            var o = i * fields;
            var t = theirs.get(i);
            sums[0] += ours[o + 1];
            sums[1] += t[1];
            sums[2] += ours[o + 2];
            sums[3] += t[2];
            sums[4] += ours[o + 3];
            sums[5] += t[3];
            diffs[0] += Math.abs(ours[o + 4] - t[4]);
            diffs[1] += Math.abs(ours[o + 5] - t[5]);
            diffs[2] += Math.abs(ours[o + 6] - t[6]);
            diffs[3] += Math.abs(ours[o + 9] - t[9]);
            diffs[4] += Math.abs(ours[o + 11] - t[11]);
        }
        for (var k = 0; k < diffs.length; k++) {
            diffs[k] /= FRAMES - 1;
        }
        var intra = sums[0] / sums[1];
        var coded = sums[2] / sums[3];
        var weighted = sums[4] / sums[5];
        var report = String.format("vp8 first pass vs libvpx: intra %.3f, coded %.3f, weighted %.3f (sum ratios);"
                        + " pcnt_inter %.3f, pcnt_motion %.3f, pcnt_second_ref %.3f, mvr_abs %.2f, mvc_abs %.2f (mean"
                        + " differences)", intra, coded, weighted, diffs[0], diffs[1], diffs[2], diffs[3], diffs[4]);
        System.out.println(report);
        assertTrue(Math.abs(intra - 1) < 0.02, report);
        assertTrue(Math.abs(coded - 1) < 0.03, report);
        assertTrue(diffs[0] < 0.02 && diffs[1] < 0.05 && diffs[2] < 0.05, report);
    }
}
