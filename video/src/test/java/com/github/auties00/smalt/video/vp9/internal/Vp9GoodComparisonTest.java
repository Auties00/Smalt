package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.Vp9Encoder;
import com.github.auties00.smalt.video.vp9.Vp9EncoderOptions;
import com.github.auties00.smalt.video.vp9.Vp9EncoderSpeed;
import com.github.auties00.smalt.video.vp9.Vp9EncoderUsage;
import com.github.auties00.smalt.video.vp9.Vp9FirstPass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the good-quality VP9 encoder with libvpx's good-quality encoder through FFmpeg on the
 * foreman clip: both code the same pictures at the same bitrates, the module's decoder decodes
 * both streams, and the module's rate-quality curve must stay within a margin of libvpx's, the
 * Bjontegaard delta of the luma PSNR at equal rate. The measured points are printed.
 */
@DisplayName("VP9 good quality against libvpx")
class Vp9GoodComparisonTest {
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
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        long bytes = 0;
        double psnr = 0;
        var shown = 0;
        var recon = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var data : packets) {
            bytes += data.length;
            decoder.decodePacket(data, 0, data.length);
            var frame = decoder.shownFrame();
            if (frame == null) {
                continue;
            }
            for (var y = 0; y < HEIGHT; y++) {
                System.arraycopy(frame.planes8[0], y * frame.strides[0], recon.y(), y * recon.yStride(), WIDTH);
            }
            psnr += Vp9RtQualityTool.psnr(Vp9RtQualityTool.mse(source.get(shown), recon));
            shown++;
        }
        return new double[]{bytes * 8.0 * 30 / source.size() / 1000, psnr / shown, shown};
    }

    /** Encodes a clip with this module's encoder; speed -1 stands for the best quality. */
    static List<byte[]> smalt(List<YuvPicture> source, int kbps, int speed, boolean twoPass) {
        var options = Vp9EncoderOptions.ofBitrate(kbps * 1000L).toBuilder().speed(Vp9EncoderSpeed.of(Math.max(speed, 0)))
                .usage(speed < 0 ? Vp9EncoderUsage.BEST_QUALITY : Vp9EncoderUsage.GOOD_QUALITY).build();
        if (twoPass) {
            var pass = new Vp9FirstPass(WIDTH, HEIGHT, options);
            for (var i = 0; i < source.size(); i++) {
                pass.add(new VideoFrame(source.get(i), i * 3000L, 3000, false));
            }
            options = options.toBuilder().firstPassStats(pass.finish()).build();
        }
        var packets = new ArrayList<byte[]>();
        try (var encoder = new Vp9Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < source.size(); i++) {
                encoder.encode(new VideoFrame(source.get(i), i * 3000L, 3000, false), p -> packets.add(p.data()));
            }
            encoder.flush(p -> packets.add(p.data()));
        }
        return packets;
    }

    /** Encodes a clip with libvpx through FFmpeg; speed -1 stands for the best quality. */
    List<byte[]> libvpx(List<YuvPicture> source, int kbps, int speed, boolean twoPass) throws Exception {
        var raw = temp.resolve("source.yuv");
        if (!Files.exists(raw)) {
            try (var out = Files.newOutputStream(raw)) {
                for (var picture : source) {
                    for (var y = 0; y < HEIGHT; y++) {
                        out.write(picture.y(), y * picture.yStride(), WIDTH);
                    }
                    for (var y = 0; y < HEIGHT / 2; y++) {
                        out.write(picture.u(), y * picture.uvStride(), WIDTH / 2);
                    }
                    for (var y = 0; y < HEIGHT / 2; y++) {
                        out.write(picture.v(), y * picture.uvStride(), WIDTH / 2);
                    }
                }
            }
        }
        var ivf = temp.resolve("libvpx-" + kbps + "-" + speed + "-" + twoPass + ".ivf");
        var common = List.of("-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", WIDTH + "x" + HEIGHT, "-r", "30", "-i",
                raw.toString(), "-c:v", "libvpx-vp9", "-deadline", speed < 0 ? "best" : "good", "-cpu-used",
                String.valueOf(Math.max(speed, 0)), "-b:v",
                kbps + "k", "-lag-in-frames", "25", "-auto-alt-ref", "1", "-arnr-maxframes", "7", "-arnr-strength", "5",
                "-g", "128", "-threads", "1", "-tile-columns", "0");
        if (twoPass) {
            var log = temp.resolve("pass-" + kbps + "-" + speed).toString();
            run(concat(List.of("ffmpeg", "-y", "-hide_banner", "-loglevel", "error"), common,
                    List.of("-pass", "1", "-passlogfile", log, "-f", "null", "-")));
            run(concat(List.of("ffmpeg", "-y", "-hide_banner", "-loglevel", "error"), common,
                    List.of("-pass", "2", "-passlogfile", log, "-f", "ivf", ivf.toString())));
        } else {
            run(concat(List.of("ffmpeg", "-y", "-hide_banner", "-loglevel", "error"), common,
                    List.of("-f", "ivf", ivf.toString())));
        }
        return Vp9RtQualityTool.parseIvf(Files.readAllBytes(ivf));
    }

    static List<String> concat(List<String> a, List<String> b, List<String> c) {
        var all = new ArrayList<String>(a);
        all.addAll(b);
        all.addAll(c);
        return all;
    }

    static void run(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("ffmpeg failed: " + output);
        }
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
    @CsvSource({"0, false, 0.15", "0, true, -0.55", "1, true, -0.65", "2, false, 0.05", "2, true, -0.55",
            "4, false, 0.1", "-1, false, 0.1", "-1, true, -0.55"})
    @DisplayName("the rate-quality curve stays close to libvpx's good-quality and best-quality encoder")
    void bdRate(int speed, boolean twoPass, double minDelta) throws Exception {
        Skip.unless(Vp9TestVideo.ffmpegAvailable(), "oracle:ffmpeg", "ffmpeg not available");
        var source = foreman(FRAMES);
        int[] rates = {80, 160, 320};
        var ours = new double[rates.length][];
        var theirs = new double[rates.length][];
        var report = new StringBuilder("foreman qcif " + (speed < 0 ? "best" : "speed " + speed)
                + (twoPass ? " two-pass" : " one-pass") + ":");
        for (var i = 0; i < rates.length; i++) {
            ours[i] = measure(smalt(source, rates[i], speed, twoPass), source);
            theirs[i] = measure(libvpx(source, rates[i], speed, twoPass), source);
            report.append(String.format(" target %d: smalt %.1f kbit/s %.2f dB, libvpx %.1f kbit/s %.2f dB;", rates[i],
                    ours[i][0], ours[i][1], theirs[i][0], theirs[i][1]));
            assertTrue(Math.abs(ours[i][0] - rates[i]) < rates[i] * 0.25, report.toString());
        }
        var delta = bdPsnr(ours, theirs);
        report.append(String.format(" BD-PSNR %.2f dB", delta));
        System.out.println(report);
        assertTrue(delta > minDelta, report.toString());
    }

    /** Returns libvpx's first-pass records of a clip, read from the pass log FFmpeg writes in base64. */
    List<double[]> libvpxFirstPass(List<YuvPicture> source, String codec, int fields, int recordBytes)
            throws Exception {
        var raw = temp.resolve("fp-source.yuv");
        try (var out = Files.newOutputStream(raw)) {
            for (var picture : source) {
                for (var y = 0; y < HEIGHT; y++) {
                    out.write(picture.y(), y * picture.yStride(), WIDTH);
                }
                for (var y = 0; y < HEIGHT / 2; y++) {
                    out.write(picture.u(), y * picture.uvStride(), WIDTH / 2);
                }
                for (var y = 0; y < HEIGHT / 2; y++) {
                    out.write(picture.v(), y * picture.uvStride(), WIDTH / 2);
                }
            }
        }
        var log = temp.resolve("fp-" + codec).toString();
        run(List.of("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s",
                WIDTH + "x" + HEIGHT, "-r", "30", "-i", raw.toString(), "-c:v", codec, "-deadline", "good",
                "-cpu-used", "2", "-b:v", "200k", "-threads", "1", "-pass", "1", "-passlogfile", log, "-f", "null", "-"));
        var text = Files.readString(Path.of(log + "-0.log")).strip();
        var bytes = java.util.Base64.getMimeDecoder().decode(text);
        var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        var records = new ArrayList<double[]>();
        for (var at = 0; at + recordBytes <= bytes.length; at += recordBytes) {
            var record = new double[fields];
            for (var k = 0; k < fields; k++) {
                record[k] = buffer.getDouble(at + 8 * k);
            }
            records.add(record);
        }
        return records;
    }

    /** Returns the ratio of the sums of a field of two record sets over the frames after the first. */
    static double sumRatio(double[] ours, int fields, List<double[]> theirs, int field, int frames) {
        double a = 0;
        double b = 0;
        for (var i = 1; i < frames; i++) {
            a += ours[i * fields + field];
            b += theirs.get(i)[field];
        }
        return a / b;
    }

    /** Returns the mean absolute difference of a field of two record sets over the frames after the first. */
    static double meanDifference(double[] ours, int fields, List<double[]> theirs, int field, int frames) {
        double sum = 0;
        for (var i = 1; i < frames; i++) {
            sum += Math.abs(ours[i * fields + field] - theirs.get(i)[field]);
        }
        return sum / (frames - 1);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the first-pass records match libvpx's first pass")
    void firstPass() throws Exception {
        Skip.unless(Vp9TestVideo.ffmpegAvailable(), "oracle:ffmpeg", "ffmpeg not available");
        var source = foreman(FRAMES);
        var theirs = libvpxFirstPass(source, "libvpx-vp9", Vp9FirstPassAnalyzer.FIELDS, 27 * 8);
        assertTrue(theirs.size() >= FRAMES, "libvpx records: " + theirs.size());
        var runner = new Vp9FirstPassRunner(WIDTH, HEIGHT, 1, 1, 8, 1);
        for (var picture : source) {
            runner.add(picture, Vp9RtEncoder.TICKS_PER_SECOND / 30);
        }
        var ours = runner.records();
        var f = Vp9FirstPassAnalyzer.FIELDS;
        var intra = sumRatio(ours, f, theirs, Vp9FirstPassAnalyzer.INTRA_ERROR, FRAMES);
        var coded = sumRatio(ours, f, theirs, Vp9FirstPassAnalyzer.CODED_ERROR, FRAMES);
        var second = sumRatio(ours, f, theirs, Vp9FirstPassAnalyzer.SR_CODED_ERROR, FRAMES);
        var inter = meanDifference(ours, f, theirs, Vp9FirstPassAnalyzer.PCNT_INTER, FRAMES);
        var motion = meanDifference(ours, f, theirs, Vp9FirstPassAnalyzer.PCNT_MOTION, FRAMES);
        var secondRef = meanDifference(ours, f, theirs, Vp9FirstPassAnalyzer.PCNT_SECOND_REF, FRAMES);
        var mvr = meanDifference(ours, f, theirs, Vp9FirstPassAnalyzer.MVR_ABS, FRAMES);
        var mvc = meanDifference(ours, f, theirs, Vp9FirstPassAnalyzer.MVC_ABS, FRAMES);
        var report = String.format("vp9 first pass vs libvpx: intra %.3f, coded %.3f, second reference %.3f (sum"
                        + " ratios); pcnt_inter %.3f, pcnt_motion %.3f, pcnt_second_ref %.3f, mvr_abs %.2f, mvc_abs"
                        + " %.2f (mean differences)", intra, coded, second, inter, motion, secondRef, mvr, mvc);
        System.out.println(report);
        assertTrue(Math.abs(intra - 1) < 0.01, report);
        assertTrue(Math.abs(coded - 1) < 0.01, report);
        assertTrue(Math.abs(second - 1) < 0.02, report);
        assertTrue(inter < 0.01 && motion < 0.02 && secondRef < 0.02, report);
    }
}
