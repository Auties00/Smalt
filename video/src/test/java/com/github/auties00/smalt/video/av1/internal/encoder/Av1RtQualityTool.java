package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.video.vp9.internal.Vp9RtEncoder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measures the rate and quality of the real-time AV1 encoder against libaom's real-time mode and
 * against the module's VP9 real-time encoder, encoding a raw 4:2:0 clip at several bitrates and
 * reporting the luma PSNR, the bitrate, the encode time and the Bjontegaard rate differences.
 * Every AV1 stream is decoded with the module's decoder. Not a test: run its {@code main} by hand.
 *
 * <p>Arguments: {@code clip width height frames speed tileColumnsLog2 threads workDir modes kbps...},
 * where {@code modes} is a comma-separated subset of {@code ours}, {@code aom}, {@code aomcr}
 * (libaom with cyclic refresh) and {@code vp9}. The {@code av1rt.aomenc} property names the
 * {@code aomenc} executable.
 */
final class Av1RtQualityTool {
    private Av1RtQualityTool() {
    }

    record Point(double kbps, double psnr, double seconds) {
    }

    public static void main(String[] args) throws Exception {
        var clip = Path.of(args[0]);
        var width = Integer.parseInt(args[1]);
        var height = Integer.parseInt(args[2]);
        var frames = Integer.parseInt(args[3]);
        var speed = Integer.parseInt(args[4]);
        var tiles = Integer.parseInt(args[5]);
        var threads = Integer.parseInt(args[6]);
        var work = Path.of(args[7]);
        Files.createDirectories(work);
        var modes = List.of(args[8].split(","));
        var rates = new ArrayList<Integer>();
        for (var i = 9; i < args.length; i++) {
            rates.add(Integer.parseInt(args[i]));
        }
        var source = read(clip, width, height, frames);
        var results = new java.util.LinkedHashMap<String, List<Point>>();
        for (var mode : modes) {
            var points = new ArrayList<Point>();
            for (var kbps : rates) {
                for (var warm = 0; warm < Integer.getInteger("av1rt.warmup", 0) && mode.equals("ours"); warm++) {
                    ours(source, width, height, speed, tiles, threads, kbps, work);
                }
                var p = switch (mode) {
                    case "ours" -> ours(source, width, height, speed, tiles, threads, kbps, work);
                    case "aom" -> aom(clip, source, width, height, speed, tiles, threads, kbps, work, 0);
                    case "aomcr" -> aom(clip, source, width, height, speed, tiles, threads, kbps, work, 3);
                    case "vp9" -> vp9(source, width, height, Math.min(speed, 9), tiles, kbps);
                    default -> throw new IllegalArgumentException(mode);
                };
                points.add(p);
                System.out.printf(Locale.ROOT, "%-6s %5d kbps: %8.1f kbps  psnr %.3f  %.2fs (%.2f ms/frame)%n", mode, kbps,
                        p.kbps, p.psnr, p.seconds, p.seconds * 1000 / source.size());
            }
            results.put(mode, points);
        }
        var ours = results.get("ours");
        if (ours != null) {
            for (var e : results.entrySet()) {
                if (!e.getKey().equals("ours") && ours.size() >= 4) {
                    System.out.printf(Locale.ROOT, "BD-rate ours vs %s (PSNR-Y): %+.2f%%%n", e.getKey(),
                            bdRate(e.getValue(), ours));
                }
            }
        }
    }

    static List<YuvPicture> read(Path file, int width, int height, int frames) throws IOException {
        var size = width * height * 3 / 2;
        var pictures = new ArrayList<YuvPicture>();
        try (var in = new RandomAccessFile(file.toFile(), "r")) {
            var buffer = new byte[size];
            for (var i = 0; i < frames; i++) {
                if ((long) (i + 1) * size > in.length()) {
                    break;
                }
                in.seek((long) i * size);
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

    static Point ours(List<YuvPicture> source, int width, int height, int speed, int tiles, int threads, int kbps,
                      Path work) throws IOException {
        var enc = new Av1RtEncoder(width, height, ColorInfo.BT601_LIMITED, MediaLimits.DEFAULT);
        var fixed = Integer.getInteger("av1rt.q", -1);
        enc.configure(new Av1RtEncoder.Settings(kbps * 1000L, 30, fixed < 0 ? 0 : fixed * 4,
                fixed < 0 ? 255 : fixed * 4, Integer.getInteger("av1rt.kf", 0), speed, false, 0, 1000, 600, 600, tiles,
                threads, !Boolean.getBoolean("av1rt.noaq"), Integer.getInteger("av1rt.maxintra", 0)));
        var units = new ArrayList<byte[]>();
        var nanos = 0L;
        for (var picture : source) {
            var t0 = System.nanoTime();
            var data = enc.encode(picture, false);
            nanos += System.nanoTime() - t0;
            units.add(data);
        }
        enc.close();
        var coded = units.stream().filter(java.util.Objects::nonNull).toList();
        Files.write(work.resolve("ours-" + kbps + ".ivf"), Av1EncTesting.ivf(width, height, coded));
        if (Boolean.getBoolean("av1rt.nomeasure")) {
            var bytes = coded.stream().mapToLong(u -> u.length).sum();
            return new Point(bytes * 8 / (source.size() / 30.0) / 1000, 0, nanos / 1e9);
        }
        var p = measure(coded, source, width, height);
        return new Point(p.kbps, p.psnr, nanos / 1e9);
    }

    static Point aom(Path clip, List<YuvPicture> source, int width, int height, int speed, int tiles, int threads,
                     int kbps, Path work, int aqMode) throws Exception {
        var out = work.resolve("aom" + aqMode + "-" + kbps + ".ivf");
        var exe = System.getProperty("av1rt.aomenc", "aomenc");
        var fixed = Integer.getInteger("av1rt.q", -1);
        var command = new ArrayList<>(List.of(exe, "--rt", "--cpu-used=" + speed, "--end-usage=cbr", "--target-bitrate=" + kbps,
                "--undershoot-pct=50", "--overshoot-pct=50", "--buf-sz=1000", "--buf-initial-sz=600",
                "--buf-optimal-sz=600", "--min-q=0", "--max-q=63", "--lag-in-frames=0", "--kf-max-dist=99999",
                "--threads=" + Math.max(1, threads), "--tile-columns=" + tiles, "--aq-mode=" + aqMode,
                "--width=" + width, "--height=" + height, "--fps=30/1", "--i420", "--limit=" + source.size(),
                "--ivf", "-q", "-o", out.toString(), clip.toString()));
        if (fixed >= 0) {
            command.set(3, "--end-usage=q");
            command.add(4, "--cq-level=" + fixed);
        }
        var t0 = System.nanoTime();
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var log = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("aomenc failed: " + log);
        }
        var seconds = (System.nanoTime() - t0) / 1e9;
        var p = measure(parseIvf(Files.readAllBytes(out)), source, width, height);
        return new Point(p.kbps, p.psnr, seconds);
    }

    static Point vp9(List<YuvPicture> source, int width, int height, int speed, int tiles, int kbps) {
        var enc = new Vp9RtEncoder(width, height, 1, tiles, 1, MediaLimits.DEFAULT);
        enc.configure(com.github.auties00.smalt.video.vp9.Vp9EncoderOptions.builder().bitrate(kbps * 1000L)
                .speed(com.github.auties00.smalt.video.vp9.Vp9EncoderSpeed.of(speed)).noPeriodicKeyframes()
                .adaptiveQuantization(!Boolean.getBoolean("av1rt.noaq"))
                .bufferSize(java.time.Duration.ofMillis(1000)).initialBufferLevel(java.time.Duration.ofMillis(600))
                .optimalBufferLevel(java.time.Duration.ofMillis(600)).build());
        var recon = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        long bytes = 0;
        double mseSum = 0;
        var tick = Vp9RtEncoder.TICKS_PER_SECOND / 30;
        var nanos = 0L;
        for (var i = 0; i < source.size(); i++) {
            var t0 = System.nanoTime();
            var data = enc.encode(source.get(i), i * tick, (i + 1) * tick, false);
            nanos += System.nanoTime() - t0;
            if (data != null) {
                bytes += data.length;
                enc.copyReconstruction(recon);
            }
            mseSum += mse(source.get(i), recon);
        }
        var duration = source.size() / 30.0;
        return new Point(bytes * 8 / duration / 1000, psnr(mseSum / source.size()), nanos / 1e9);
    }

    static Point measure(List<byte[]> units, List<YuvPicture> source, int width, int height) {
        var decoded = Av1EncTesting.decode(width, height, units);
        var skip = Integer.getInteger("av1rt.skip", 0);
        long bytes = 0;
        for (var i = skip; i < units.size(); i++) {
            bytes += units.get(i).length;
        }
        double mseSum = 0;
        YuvPicture last = null;
        for (var i = 0; i < source.size(); i++) {
            if (i < decoded.size()) {
                last = decoded.get(i);
            }
            var m = last == null ? 65025 : mse(source.get(i), last);
            if (i >= skip) {
                mseSum += m;
            }
            if (Boolean.getBoolean("av1rt.trace")) {
                System.out.printf(Locale.ROOT, "  frame %3d %7d bytes psnr %.3f%n", i,
                        i < units.size() ? units.get(i).length : 0, psnr(m));
            }
        }
        var duration = (source.size() - skip) / 30.0;
        return new Point(bytes * 8 / duration / 1000, psnr(mseSum / (source.size() - skip)), 0);
    }

    static double mse(YuvPicture a, YuvPicture b) {
        long sum = 0;
        for (var y = 0; y < a.height(); y++) {
            for (var x = 0; x < a.width(); x++) {
                var d = (a.y()[y * a.yStride() + x] & 0xFF) - (b.y()[y * b.yStride() + x] & 0xFF);
                sum += (long) d * d;
            }
        }
        return (double) sum / ((long) a.width() * a.height());
    }

    static double psnr(double mse) {
        return mse <= 0 ? 100 : 10 * Math.log10(255.0 * 255.0 / mse);
    }

    static List<byte[]> parseIvf(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var header = buffer.getShort(6) & 0xFFFF;
        var frames = new ArrayList<byte[]>();
        var pos = header;
        while (pos + 12 <= bytes.length) {
            var size = buffer.getInt(pos);
            var data = new byte[size];
            System.arraycopy(bytes, pos + 12, data, 0, size);
            frames.add(data);
            pos += 12 + size;
        }
        return frames;
    }

    /**
     * Returns the Bjontegaard rate difference of a test curve against a reference curve, from cubic
     * fits of log rate over PSNR integrated on the overlapping PSNR range.
     */
    static double bdRate(List<Point> reference, List<Point> test) {
        var pr = fit(reference);
        var pt = fit(test);
        var lo = Math.max(min(reference), min(test));
        var hi = Math.min(max(reference), max(test));
        var ir = integral(pr, lo, hi);
        var it = integral(pt, lo, hi);
        var avg = (it - ir) / (hi - lo);
        return (Math.exp(avg) - 1) * 100;
    }

    private static double min(List<Point> p) {
        return p.stream().mapToDouble(Point::psnr).min().orElseThrow();
    }

    private static double max(List<Point> p) {
        return p.stream().mapToDouble(Point::psnr).max().orElseThrow();
    }

    private static double integral(double[] c, double lo, double hi) {
        return poly(c, hi) - poly(c, lo);
    }

    private static double poly(double[] c, double x) {
        return c[0] * x + c[1] * x * x / 2 + c[2] * x * x * x / 3 + c[3] * x * x * x * x / 4;
    }

    private static double[] fit(List<Point> points) {
        var n = 4;
        var a = new double[n][n + 1];
        for (var p : points) {
            var x = p.psnr;
            var y = Math.log(p.kbps);
            var pow = new double[2 * n];
            pow[0] = 1;
            for (var i = 1; i < pow.length; i++) {
                pow[i] = pow[i - 1] * x;
            }
            for (var r = 0; r < n; r++) {
                for (var c = 0; c < n; c++) {
                    a[r][c] += pow[r + c];
                }
                a[r][n] += pow[r] * y;
            }
        }
        for (var col = 0; col < n; col++) {
            var pivot = col;
            for (var r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            var tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            for (var r = 0; r < n; r++) {
                if (r != col) {
                    var f = a[r][col] / a[col][col];
                    for (var c = col; c <= n; c++) {
                        a[r][c] -= f * a[col][c];
                    }
                }
            }
        }
        var out = new double[n];
        for (var i = 0; i < n; i++) {
            out[i] = a[i][n] / a[i][i];
        }
        return out;
    }
}
