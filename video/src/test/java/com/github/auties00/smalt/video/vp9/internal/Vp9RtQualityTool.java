package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;

import java.io.ByteArrayOutputStream;
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
 * Measures the rate and quality of the real-time VP9 encoder against libvpx's, encoding a raw
 * clip at several bitrates with both, decoding libvpx's streams with the module's decoder, and
 * reporting the luma PSNR, the bitrate and the Bjontegaard rate difference. Not a test: run its
 * {@code main} by hand.
 *
 * <p>Arguments: {@code clip width height frames speed tileColumnsLog2 workDir mode kbps...}. The
 * modes {@code both}, {@code ours} and {@code libvpx} compare with the libvpx of the ffmpeg
 * command line (an older release), with an 833 ms optimal buffer as ffmpeg sets; the mode
 * {@code ref} compares with libvpx 1.17 through the harness named by the {@code vp9rt.harness}
 * property (default {@code work/vp9rt_steps.exe}), with a 600 ms optimal buffer for both.
 */
final class Vp9RtQualityTool {
    private Vp9RtQualityTool() {
    }

    record Point(double kbps, double psnr, double psnrFrames, double encodeSeconds) {
    }

    public static void main(String[] args) throws Exception {
        var clip = Path.of(args[0]);
        var width = Integer.parseInt(args[1]);
        var height = Integer.parseInt(args[2]);
        var frames = Integer.parseInt(args[3]);
        var speed = Integer.parseInt(args[4]);
        var tiles = Integer.parseInt(args[5]);
        var work = Path.of(args[6]);
        var mode = args[7];
        var rates = new ArrayList<Integer>();
        for (var i = 8; i < args.length; i++) {
            rates.add(Integer.parseInt(args[i]));
        }
        var source = read(clip, width, height, frames);
        var ours = new ArrayList<Point>();
        var libvpx = new ArrayList<Point>();
        var optimal = mode.equals("ref") ? 600 : 833;
        for (var kbps : rates) {
            if (!mode.equals("libvpx")) {
                var p = encodeOurs(source, width, height, speed, tiles, kbps, work, optimal);
                ours.add(p);
                System.out.printf(Locale.ROOT, "ours   %5d kbps target: %8.1f kbps  psnr %.3f (frames %.3f)  %.2fs%n", kbps,
                        p.kbps, p.psnr, p.psnrFrames, p.encodeSeconds);
            }
            if (mode.equals("ref")) {
                var p = encodeHarness(clip, source, width, height, speed, tiles, kbps, work);
                libvpx.add(p);
                System.out.printf(Locale.ROOT, "libvpx 1.17 %5d kbps target: %8.1f kbps  psnr %.3f (frames %.3f)  %.2fs%n",
                        kbps, p.kbps, p.psnr, p.psnrFrames, p.encodeSeconds);
            } else if (!mode.equals("ours")) {
                var p = encodeLibvpx(clip, source, width, height, speed, tiles, kbps, work);
                libvpx.add(p);
                System.out.printf(Locale.ROOT, "libvpx %5d kbps target: %8.1f kbps  psnr %.3f (frames %.3f)  %.2fs%n", kbps,
                        p.kbps, p.psnr, p.psnrFrames, p.encodeSeconds);
            }
        }
        if (ours.size() >= 4 && libvpx.size() >= 4) {
            System.out.printf(Locale.ROOT, "BD-rate (ours vs libvpx, PSNR-Y): %+.2f%%%n", bdRate(libvpx, ours));
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

    static Point encodeOurs(List<YuvPicture> source, int width, int height, int speed, int tiles, int kbps, Path work,
                            int optimalMillis) throws IOException {
        var enc = new Vp9RtEncoder(width, height, 1, tiles, 1, MediaLimits.DEFAULT);
        enc.configure(Vp9TestVideo.rtOptions(kbps * 1000L, speed).errorResilient(true).adaptiveQuantization(true)
                .optimalBufferLevel(java.time.Duration.ofMillis(optimalMillis)).build());
        var recon = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        var packets = new ArrayList<byte[]>();
        long bytes = 0;
        double mseSum = 0;
        double psnrSum = 0;
        var tick = Vp9RtEncoder.TICKS_PER_SECOND / 30;
        var start = System.nanoTime();
        var encodeNanos = 0L;
        for (var i = 0; i < source.size(); i++) {
            var t0 = System.nanoTime();
            var data = enc.encode(source.get(i), i * tick, (i + 1) * tick, false);
            encodeNanos += System.nanoTime() - t0;
            if (data != null) {
                packets.add(data);
                bytes += data.length;
                enc.copyReconstruction(recon);
            }
            var mse = mse(source.get(i), recon);
            mseSum += mse;
            psnrSum += psnr(mse);
        }
        Files.write(work.resolve("ours-" + kbps + ".ivf"), ivf(width, height, packets));
        var seconds = source.size() / 30.0;
        return new Point(bytes * 8 / seconds / 1000, psnr(mseSum / source.size()), psnrSum / source.size(),
                encodeNanos / 1e9);
    }

    static Point encodeHarness(Path clip, List<YuvPicture> source, int width, int height, int speed, int tiles,
                               int kbps, Path work) throws Exception {
        var out = work.resolve("libvpx117-" + kbps + ".ivf");
        var harness = System.getProperty("vp9rt.harness", "work/vp9rt_steps.exe");
        var command = List.of(harness, clip.toString(), String.valueOf(width), String.valueOf(height),
                String.valueOf(source.size()), "0", String.valueOf(speed), "1", String.valueOf(tiles), "52", "1",
                out.toString(), String.valueOf(kbps));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var log = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("harness failed: " + log);
        }
        var seconds = 0.0;
        for (var line : log.lines().toList()) {
            if (line.startsWith("encode seconds ")) {
                seconds = Double.parseDouble(line.split(" ")[2]);
            }
        }
        var point = measure(parseIvf(Files.readAllBytes(out)), source, width, height);
        return new Point(point.kbps, point.psnr, point.psnrFrames, seconds);
    }

    static Point measure(List<byte[]> packets, List<YuvPicture> source, int width, int height) {
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        long bytes = 0;
        double mseSum = 0;
        double psnrSum = 0;
        var recon = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var i = 0; i < source.size(); i++) {
            if (i < packets.size()) {
                var data = packets.get(i);
                bytes += data.length;
                decoder.decodePacket(data, 0, data.length);
                var frame = decoder.shownFrame();
                if (frame != null) {
                    for (var plane = 0; plane < 3; plane++) {
                        var dst = plane == 0 ? recon.y() : plane == 1 ? recon.u() : recon.v();
                        var stride = plane == 0 ? recon.yStride() : recon.uvStride();
                        for (var y = 0; y < frame.cropHeights[plane]; y++) {
                            System.arraycopy(frame.planes8[plane], y * frame.strides[plane], dst, y * stride,
                                    frame.cropWidths[plane]);
                        }
                    }
                }
            }
            var mse = mse(source.get(i), recon);
            mseSum += mse;
            psnrSum += psnr(mse);
        }
        var duration = source.size() / 30.0;
        return new Point(bytes * 8 / duration / 1000, psnr(mseSum / source.size()), psnrSum / source.size(), 0);
    }

    static Point encodeLibvpx(Path clip, List<YuvPicture> source, int width, int height, int speed, int tiles, int kbps,
                              Path work) throws Exception {
        var out = work.resolve("libvpx-" + kbps + ".ivf");
        var command = List.of("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt",
                "yuv420p", "-s", width + "x" + height, "-r", "30", "-i", clip.toString(), "-frames:v",
                String.valueOf(source.size()), "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used",
                String.valueOf(speed), "-b:v", kbps + "k", "-minrate", kbps + "k", "-maxrate", kbps + "k", "-bufsize",
                kbps + "k", "-rc_init_occupancy", (kbps / 2) + "k", "-undershoot-pct", "50", "-overshoot-pct", "50",
                "-qmin", "2", "-qmax", "52", "-aq-mode", "3", "-error-resilient", "default", "-lag-in-frames", "0",
                "-g", "100000", "-keyint_min", "100000", "-threads", "1", "-tile-columns", String.valueOf(tiles),
                "-row-mt", "1", "-static-thresh", "0", "-f", "ivf", out.toString());
        var t0 = System.nanoTime();
        var process = new ProcessBuilder(command).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("ffmpeg failed");
        }
        var seconds = (System.nanoTime() - t0) / 1e9;
        var packets = parseIvf(Files.readAllBytes(out));
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        long bytes = 0;
        double mseSum = 0;
        double psnrSum = 0;
        var recon = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var i = 0; i < source.size(); i++) {
            if (i < packets.size()) {
                var data = packets.get(i);
                bytes += data.length;
                decoder.decodePacket(data, 0, data.length);
                var frame = decoder.shownFrame();
                if (frame != null) {
                    for (var plane = 0; plane < 3; plane++) {
                        var dst = plane == 0 ? recon.y() : plane == 1 ? recon.u() : recon.v();
                        var stride = plane == 0 ? recon.yStride() : recon.uvStride();
                        for (var y = 0; y < frame.cropHeights[plane]; y++) {
                            System.arraycopy(frame.planes8[plane], y * frame.strides[plane], dst, y * stride,
                                    frame.cropWidths[plane]);
                        }
                    }
                }
            }
            var mse = mse(source.get(i), recon);
            mseSum += mse;
            psnrSum += psnr(mse);
        }
        var duration = source.size() / 30.0;
        return new Point(bytes * 8 / duration / 1000, psnr(mseSum / source.size()), psnrSum / source.size(), seconds);
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
            pos += 12;
            var data = new byte[size];
            System.arraycopy(bytes, pos, data, 0, size);
            frames.add(data);
            pos += size;
        }
        return frames;
    }

    static byte[] ivf(int width, int height, List<byte[]> frames) {
        var out = new ByteArrayOutputStream();
        var header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'D', 'K', 'I', 'F'}).putShort((short) 0).putShort((short) 32)
                .put(new byte[]{'V', 'P', '9', '0'}).putShort((short) width).putShort((short) height)
                .putInt(30).putInt(1).putInt(frames.size()).putInt(0);
        out.writeBytes(header.array());
        var pts = 0L;
        for (var frame : frames) {
            var fh = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(frame.length).putLong(pts++);
            out.writeBytes(fh.array());
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    static double bdRate(List<Point> anchor, List<Point> test) {
        var pa = fit(anchor);
        var pt = fit(test);
        var minP = Math.max(min(anchor), min(test));
        var maxP = Math.min(max(anchor), max(test));
        var ia = integral(pa, maxP) - integral(pa, minP);
        var it = integral(pt, maxP) - integral(pt, minP);
        var avg = (it - ia) / (maxP - minP);
        return (Math.pow(10, avg) - 1) * 100;
    }

    private static double min(List<Point> points) {
        return points.stream().mapToDouble(Point::psnr).min().orElseThrow();
    }

    private static double max(List<Point> points) {
        return points.stream().mapToDouble(Point::psnr).max().orElseThrow();
    }

    private static double[] fit(List<Point> points) {
        var n = points.size();
        var a = new double[4][5];
        for (var p : points) {
            var x = p.psnr;
            var y = Math.log10(p.kbps);
            var pw = new double[7];
            pw[0] = 1;
            for (var i = 1; i < 7; i++) {
                pw[i] = pw[i - 1] * x;
            }
            for (var r = 0; r < 4; r++) {
                for (var c = 0; c < 4; c++) {
                    a[r][c] += pw[r + c];
                }
                a[r][4] += pw[r] * y;
            }
        }
        for (var col = 0; col < 4; col++) {
            var pivot = col;
            for (var r = col + 1; r < 4; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            var tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            for (var r = 0; r < 4; r++) {
                if (r != col) {
                    var f = a[r][col] / a[col][col];
                    for (var c = col; c < 5; c++) {
                        a[r][c] -= f * a[col][c];
                    }
                }
            }
        }
        var coef = new double[4];
        for (var i = 0; i < 4; i++) {
            coef[i] = a[i][4] / a[i][i];
        }
        return coef;
    }

    private static double integral(double[] c, double x) {
        return c[0] * x + c[1] * x * x / 2 + c[2] * x * x * x / 3 + c[3] * x * x * x * x / 4;
    }
}
