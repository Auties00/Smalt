package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures the still encoder's rate and quality against libaom's all-intra encoder on raw 4:2:0
 * pictures: for each quantizer the coded size, the luma PSNR and the encode time of both.
 *
 * <p>Usage: {@code StillQualityTool <raw.yuv> <width> <height> <speed> <aomenc|-> <aomSpeed> <q...>}, the
 * quantizers being AV1 quantizer levels 0 to 63 (the encoder's index is four times the level).
 * Lines start with {@code ours} or {@code aom}.
 */
public final class StillQualityTool {
    private static final int threads = Integer.getInteger("still.threads", 1);

    private StillQualityTool() {
    }

    public static void main(String[] args) throws Exception {
        var raw = Path.of(args[0]);
        var width = Integer.parseInt(args[1]);
        var height = Integer.parseInt(args[2]);
        var speed = Integer.parseInt(args[3]);
        var aomenc = args[4];
        var aomSpeed = Integer.parseInt(args[5]);
        var qs = new ArrayList<Integer>();
        for (var i = 6; i < args.length; i++) {
            qs.add(Integer.parseInt(args[i]));
        }
        var planes = read(raw, width, height);
        for (var q : qs) {
            var qindex = q == 0 ? 0 : Math.min(255, q * 4);
            var settings = StillSettings.of(qindex, speed).threads(threads).reducedHeader(true);
            for (var name : System.getProperties().stringPropertyNames()) {
                if (name.startsWith("still.set.")) {
                    var field = StillSettings.class.getDeclaredField(name.substring(10));
                    var value = System.getProperty(name);
                    if (field.getType() == boolean.class) {
                        field.setBoolean(settings, Boolean.parseBoolean(value));
                    } else if (field.getType() == float.class) {
                        field.setFloat(settings, Float.parseFloat(value));
                    } else {
                        field.setInt(settings, Integer.parseInt(value));
                    }
                }
            }
            var holder = new StillFrameEncoder[1];
            var reps = Integer.getInteger("still.reps", 1);
            byte[] obus = null;
            var ms = Double.MAX_VALUE;
            var cpu = Double.MAX_VALUE;
            for (var rep = 0; rep < reps; rep++) {
                if (holder[0] != null) {
                    holder[0].close();
                }
                var start = System.nanoTime();
                var cpuStart = processCpu();
                obus = StillTesting.encode(planes, settings, holder);
                ms = Math.min(ms, (System.nanoTime() - start) / 1e6);
                cpu = Math.min(cpu, (processCpu() - cpuStart) / 1e6);
            }
            var decoded = StillTesting.decode(obus, width, height);
            var diff = StillTesting.firstDifference(decoded, holder[0].reconstruction(), planes);
            holder[0].close();
            System.out.printf("ours q%2d: %8d bytes  psnr-y %.3f  psnr %.3f  %.0f ms  cpu %.0f ms%s%n", q, obus.length,
                    psnrY(planes, decoded), StillTesting.psnr(planes, decoded), ms, cpu,
                    diff == null ? "" : "  MISMATCH " + diff);
        }
        if (!aomenc.equals("-")) {
            for (var q : qs) {
                var out = Files.createTempFile("aom", ".obu");
                var dec = Files.createTempFile("aom", ".yuv");
                var start = System.nanoTime();
                var aomCpu = run(List.of(aomenc, "--allintra", "--cpu-used=" + aomSpeed, "--end-usage=q", "--cq-level=" + q,
                        "--deltaq-mode=0", "--threads=" + threads, "--i420", "--width=" + width,
                        "--height=" + height, "--limit=1", "--obu", "-o", out.toString(), raw.toString()));
                var ms = (System.nanoTime() - start) / 1e6;
                run(List.of("ffmpeg", "-v", "error", "-y", "-c:v", "libaom-av1", "-f", "obu", "-i", out.toString(),
                        "-f", "rawvideo", "-pix_fmt", "yuv420p", dec.toString()));
                var decoded = read(dec, width, height);
                var size = Files.size(out);
                System.out.printf("aom  q%2d: %8d bytes  psnr-y %.3f  psnr %.3f  %.0f ms  cpu %.0f ms%n", q, size,
                        psnrY(planes, decoded), psnr(planes, decoded), ms, aomCpu / 1e6);
                Files.deleteIfExists(out);
                Files.deleteIfExists(dec);
            }
        }
    }

    private static long processCpu() {
        if (threads == 1) {
            try {
                var factory = Class.forName("java.lang.management.ManagementFactory");
                var bean = factory.getMethod("getThreadMXBean").invoke(null);
                var method = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime");
                return (Long) method.invoke(bean);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return ProcessHandle.current().info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(0L);
    }

    private static long run(List<String> command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (process.waitFor() != 0) {
            throw new IOException("failed: " + command);
        }
        return process.info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L);
    }

    static StillTesting.Planes read(Path raw, int width, int height) throws IOException {
        var bytes = Files.readAllBytes(raw);
        var cw = (width + 1) / 2;
        var ch = (height + 1) / 2;
        var y = new short[width * height];
        var u = new short[cw * ch];
        var v = new short[cw * ch];
        for (var i = 0; i < y.length; i++) {
            y[i] = (short) (bytes[i] & 0xFF);
        }
        for (var i = 0; i < u.length; i++) {
            u[i] = (short) (bytes[y.length + i] & 0xFF);
            v[i] = (short) (bytes[y.length + u.length + i] & 0xFF);
        }
        return new StillTesting.Planes(width, height, SequenceHeader.LAYOUT_I420, 8, new short[][]{y, u, v},
                new int[]{width, cw, cw});
    }

    private static double psnrY(StillTesting.Planes source, com.github.auties00.smalt.image.Picture decoded) {
        long sse = 0;
        for (var y = 0; y < source.height(); y++) {
            for (var x = 0; x < source.width(); x++) {
                var d = source.data()[0][y * source.strides()[0] + x] - StillTesting.sample(decoded, 0, x, y);
                sse += (long) d * d;
            }
        }
        return sse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 * source.width() * source.height() / sse);
    }

    private static double psnrY(StillTesting.Planes source, StillTesting.Planes decoded) {
        long sse = 0;
        for (var i = 0; i < source.width() * source.height(); i++) {
            var d = source.data()[0][i] - decoded.data()[0][i];
            sse += (long) d * d;
        }
        return sse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 * source.width() * source.height() / sse);
    }

    private static double psnr(StillTesting.Planes source, StillTesting.Planes decoded) {
        long sse = 0;
        long n = 0;
        for (var p = 0; p < 3; p++) {
            for (var i = 0; i < source.data()[p].length; i++) {
                var d = source.data()[p][i] - decoded.data()[p][i];
                sse += (long) d * d;
                n++;
            }
        }
        return sse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 * n / sse);
    }
}
