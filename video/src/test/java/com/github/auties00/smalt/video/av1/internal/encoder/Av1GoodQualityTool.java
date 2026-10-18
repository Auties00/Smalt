package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Measures the rate and quality of the good-quality AV1 encoder against libaom's good-quality
 * mode and against the module's real-time encoder on a Y4M clip, reporting luma PSNR and SSIM,
 * bitrate, encode time and Bjontegaard rate differences. Every stream of ours is decoded with the
 * module's decoder, and with {@code aomdec} when the {@code av1good.aomdec} property names it, in
 * which case both decoders must agree byte for byte. Not a test: run its {@code main} by hand.
 *
 * <p>Arguments: {@code clip.y4m frames speed threads workDir modes cq...}, where {@code modes} is a
 * comma-separated subset of {@code good} (ours, constant quality), {@code aom} (aomenc
 * {@code --good}), {@code vbr} (ours at a variable bitrate, reading each {@code cq} as a target in
 * kilobits per second) and {@code rt} (ours in real time at the bitrates the good mode reached). The
 * {@code av1good.aomenc} property names the {@code aomenc} executable.
 */
final class Av1GoodQualityTool {
    private Av1GoodQualityTool() {
    }

    record Point(double kbps, double psnr, double ssim, double seconds) {
    }

    record Clip(int width, int height, double fps, List<YuvPicture> frames) {
    }

    public static void main(String[] args) throws Exception {
        var clipPath = Path.of(args[0]);
        var frames = Integer.parseInt(args[1]);
        var speed = Integer.parseInt(args[2]);
        var threads = Integer.parseInt(args[3]);
        var work = Path.of(args[4]);
        Files.createDirectories(work);
        var modes = List.of(args[5].split(","));
        var cqs = new ArrayList<Integer>();
        for (var i = 6; i < args.length; i++) {
            cqs.add(Integer.parseInt(args[i]));
        }
        var clip = readY4m(clipPath, frames);
        var name = clipPath.getFileName().toString().replace(".y4m", "");
        var results = new LinkedHashMap<String, List<Point>>();
        List<Point> good = null;
        for (var mode : modes) {
            var points = new ArrayList<Point>();
            for (var k = 0; k < cqs.size(); k++) {
                var cq = cqs.get(k);
                var p = switch (mode) {
                    case "good" -> good(clip, speed, threads, cq, 0, work.resolve(name + "-good-s" + speed + "-q" + cq + ".ivf"));
                    case "vbr" -> good(clip, speed, threads, -1, cq, work.resolve(name + "-vbr-s" + speed + "-k" + cq + ".ivf"));
                    case "aom" -> aom(clipPath, clip, speed, threads, cq, work.resolve(name + "-aom-s" + speed + "-q" + cq + ".ivf"));
                    case "rt" -> rt(clip, threads, good == null ? 1000 : (int) good.get(k).kbps,
                            work.resolve(name + "-rt-q" + cq + ".ivf"));
                    default -> throw new IllegalArgumentException(mode);
                };
                points.add(p);
                System.out.printf(Locale.ROOT, "%-5s %s cq %2d: %9.1f kbps  psnr %.3f  ssim %.5f  %.2fs (%.1f ms/frame)%n",
                        mode, name, cq, p.kbps, p.psnr, p.ssim, p.seconds, p.seconds * 1000 / clip.frames.size());
            }
            if (mode.equals("good")) {
                good = points;
            }
            results.put(mode, points);
        }
        var ours = results.get("good");
        if (ours != null) {
            for (var e : results.entrySet()) {
                if (!e.getKey().equals("good") && ours.size() >= 4) {
                    System.out.printf(Locale.ROOT, "BD-rate good vs %s on %s: PSNR-Y %+.2f%%  SSIM-Y %+.2f%%%n", e.getKey(),
                            name, Av1RtQualityTool.bdRate(toPsnr(e.getValue()), toPsnr(ours)),
                            Av1RtQualityTool.bdRate(toSsim(e.getValue()), toSsim(ours)));
                }
            }
        }
    }

    private static List<Av1RtQualityTool.Point> toPsnr(List<Point> points) {
        return points.stream().map(p -> new Av1RtQualityTool.Point(p.kbps, p.psnr, p.seconds)).toList();
    }

    private static List<Av1RtQualityTool.Point> toSsim(List<Point> points) {
        return points.stream().map(p -> new Av1RtQualityTool.Point(p.kbps, -10 * Math.log10(1 - p.ssim), p.seconds))
                .toList();
    }

    static Clip readY4m(Path file, int frames) throws IOException {
        try (var in = new RandomAccessFile(file.toFile(), "r")) {
            var header = new StringBuilder();
            int c;
            while ((c = in.read()) != '\n') {
                header.append((char) c);
            }
            var width = 0;
            var height = 0;
            var fps = 30.0;
            for (var token : header.toString().split(" ")) {
                switch (token.charAt(0)) {
                    case 'W' -> width = Integer.parseInt(token.substring(1));
                    case 'H' -> height = Integer.parseInt(token.substring(1));
                    case 'F' -> {
                        var parts = token.substring(1).split(":");
                        fps = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
                    }
                    default -> {
                    }
                }
            }
            var size = width * height * 3 / 2;
            var list = new ArrayList<YuvPicture>();
            var buffer = new byte[size];
            for (var i = 0; i < frames; i++) {
                var line = new StringBuilder();
                while ((c = in.read()) != '\n') {
                    if (c < 0) {
                        return new Clip(width, height, fps, list);
                    }
                    line.append((char) c);
                }
                in.readFully(buffer);
                var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                System.arraycopy(buffer, 0, picture.y(), 0, width * height);
                System.arraycopy(buffer, width * height, picture.u(), 0, width * height / 4);
                System.arraycopy(buffer, width * height * 5 / 4, picture.v(), 0, width * height / 4);
                list.add(picture);
            }
            return new Clip(width, height, fps, list);
        }
    }

    static Point good(Clip clip, int speed, int threads, int cq, int kbps, Path out) throws Exception {
        var enc = new Av1GoodEncoder(clip.width, clip.height, ColorInfo.BT601_LIMITED, MediaLimits.DEFAULT);
        enc.configure(new Av1GoodEncoder.Settings(speed, cq < 0 ? -1 : cq * 4, kbps > 0 ? kbps * 1000L : 1_000_000,
                clip.fps, 0, 255, 0, Integer.getInteger("av1good.lag", 35), 0, threads));
        var units = new ArrayList<byte[]>();
        var t0 = System.nanoTime();
        var trace = Boolean.getBoolean("av1good.trace");
        java.util.function.Consumer<Av1GoodEncoder.Unit> sink = u -> {
            units.add(u.data());
            if (trace) {
                System.out.printf(Locale.ROOT, "  tu %3d pts %3d %7d bytes q %3d%s%n", units.size() - 1, u.pts(),
                        u.data().length, u.qindex(), u.key() ? " key" : "");
            }
        };
        for (var i = 0; i < clip.frames.size(); i++) {
            enc.encode(clip.frames.get(i), i, 1, false, sink);
        }
        enc.flush(sink);
        var seconds = (System.nanoTime() - t0) / 1e9;
        enc.close();
        Files.write(out, Av1EncTesting.ivf(clip.width, clip.height, units));
        return measure(units, clip, seconds, out);
    }

    static Point rt(Clip clip, int threads, int kbps, Path out) throws Exception {
        var enc = new Av1RtEncoder(clip.width, clip.height, ColorInfo.BT601_LIMITED, MediaLimits.DEFAULT);
        enc.configure(new Av1RtEncoder.Settings(kbps * 1000L, clip.fps, 8, 255, 0, 7, false, 0, 1000, 600, 600, 0,
                threads, true, 0));
        var units = new ArrayList<byte[]>();
        var t0 = System.nanoTime();
        for (var picture : clip.frames) {
            var data = enc.encode(picture, false);
            if (data != null) {
                units.add(data);
            }
        }
        var seconds = (System.nanoTime() - t0) / 1e9;
        enc.close();
        Files.write(out, Av1EncTesting.ivf(clip.width, clip.height, units));
        return measure(units, clip, seconds, out);
    }

    static Point aom(Path clipPath, Clip clip, int speed, int threads, int cq, Path out) throws Exception {
        var exe = System.getProperty("av1good.aomenc", "aomenc");
        var command = new ArrayList<>(List.of(exe, "--good", "--cpu-used=" + speed, "--end-usage=q",
                "--cq-level=" + cq, "--threads=" + Math.max(1, threads), "--limit=" + clip.frames.size(),
                "--kf-max-dist=9999", "--ivf", "-q", "-o", out.toString(), clipPath.toString()));
        var timeFile = out.resolveSibling(out.getFileName() + ".time");
        double seconds;
        if (Boolean.getBoolean("av1good.reuse") && Files.exists(out) && Files.exists(timeFile)) {
            seconds = Double.parseDouble(Files.readString(timeFile).trim());
        } else {
            var t0 = System.nanoTime();
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("aomenc failed: " + log);
            }
            seconds = (System.nanoTime() - t0) / 1e9;
            Files.writeString(timeFile, Double.toString(seconds));
        }
        var units = Av1RtQualityTool.parseIvf(Files.readAllBytes(out));
        if (Boolean.getBoolean("av1good.trace")) {
            for (var i = 0; i < units.size(); i++) {
                System.out.printf(Locale.ROOT, "  aom tu %3d %7d bytes%n", i, units.get(i).length);
            }
        }
        return measure(units, clip, seconds, null);
    }

    static Point measure(List<byte[]> units, Clip clip, double seconds, Path ivf) throws Exception {
        var decoded = Av1EncTesting.decode(clip.width, clip.height, units);
        if (decoded.size() != clip.frames.size()) {
            throw new IllegalStateException("decoded " + decoded.size() + " of " + clip.frames.size() + " frames");
        }
        var aomdec = System.getProperty("av1good.aomdec");
        if (ivf != null && aomdec != null) {
            var raw = ivf.resolveSibling(ivf.getFileName() + ".yuv");
            var process = new ProcessBuilder(aomdec, "--rawvideo", "-o", raw.toString(), ivf.toString())
                    .redirectErrorStream(true).start();
            var log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("aomdec failed: " + log);
            }
            var bytes = Files.readAllBytes(raw);
            var frameSize = clip.width * clip.height * 3 / 2;
            if (bytes.length != frameSize * decoded.size()) {
                throw new IllegalStateException("aomdec produced " + bytes.length / frameSize + " frames");
            }
            for (var i = 0; i < decoded.size(); i++) {
                var mine = Av1EncTesting.raw(decoded.get(i));
                for (var k = 0; k < frameSize; k++) {
                    if (mine[k] != bytes[i * frameSize + k]) {
                        throw new IllegalStateException("aomdec and smalt differ at frame " + i + " byte " + k);
                    }
                }
            }
            Files.delete(raw);
        }
        long bytes = 0;
        for (var u : units) {
            bytes += u.length;
        }
        double mseSum = 0;
        double ssimSum = 0;
        for (var i = 0; i < clip.frames.size(); i++) {
            mseSum += Av1RtQualityTool.mse(clip.frames.get(i), decoded.get(i));
            ssimSum += ssim(clip.frames.get(i), decoded.get(i));
        }
        var n = clip.frames.size();
        var duration = n / clip.fps;
        return new Point(bytes * 8 / duration / 1000, Av1RtQualityTool.psnr(mseSum / n), ssimSum / n, seconds);
    }

    /**
     * Returns the mean SSIM of the luma planes over 8x8 windows on a 4-sample grid (the
     * {@code aom_dsp/ssim.c} variant).
     */
    static double ssim(YuvPicture a, YuvPicture b) {
        var w = a.width();
        var h = a.height();
        var c1 = (0.01 * 255) * (0.01 * 255) * 4096;
        var c2 = (0.03 * 255) * (0.03 * 255) * 4096;
        double total = 0;
        var count = 0;
        var ay = a.y();
        var by = b.y();
        var as = a.yStride();
        var bs = b.yStride();
        for (var y = 0; y + 8 <= h; y += 4) {
            for (var x = 0; x + 8 <= w; x += 4) {
                long sa = 0;
                long sb = 0;
                long saa = 0;
                long sbb = 0;
                long sab = 0;
                for (var r = 0; r < 8; r++) {
                    for (var cc = 0; cc < 8; cc++) {
                        var va = ay[(y + r) * as + x + cc] & 0xFF;
                        var vb = by[(y + r) * bs + x + cc] & 0xFF;
                        sa += va;
                        sb += vb;
                        saa += va * va;
                        sbb += vb * vb;
                        sab += va * vb;
                    }
                }
                var num = (2.0 * sa * sb + c1) * (2.0 * (64.0 * sab - (double) sa * sb) + c2);
                var den = ((double) sa * sa + (double) sb * sb + c1) * (64.0 * (saa + sbb) - (double) sa * sa
                        - (double) sb * sb + c2);
                total += num / den;
                count++;
            }
        }
        return total / count;
    }
}
