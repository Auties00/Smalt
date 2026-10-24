package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line report generator (not a test): encodes raw sequences with this encoder and with
 * x264 through ffmpeg at several bitrates and prints size, speed, PSNR and SSIM, the data behind
 * the quality and speed figures of the workstream report.
 *
 * <p>Usage: {@code EncoderReport <yuv file> <width> <height> <frames> <fps> <speed|x264preset> <kbps>...}
 */
public final class EncoderReport {
    private EncoderReport() {
        throw new AssertionError();
    }

    public static void main(String[] args) throws IOException {
        var input = Path.of(args[0]);
        var width = Integer.parseInt(args[1]);
        var height = Integer.parseInt(args[2]);
        var frames = Integer.parseInt(args[3]);
        var fps = Integer.parseInt(args[4]);
        var mode = args[5];
        if (mode.equals("measure")) {
            // measure <coded file> <label>: scores a stream produced by another encoder
            var coded = Path.of(args[6]);
            var metrics = measure(input, coded, width, height, fps, frames);
            System.out.printf(Locale.ROOT, "%s -> %7.1f kbps  PSNR %6.3f  SSIM %.5f%n", args[7],
                    Files.size(coded) * 8.0 * fps / frames / 1000, metrics[0], metrics[1]);
            return;
        }
        var profile =args.length > 6 && args[6].startsWith("profile=") ? args[6].substring(8) : "main";
        var first = profile.equals(args.length > 6 ? args[6].substring(Math.min(8, args[6].length())) : "") ? 7 : 6;
        var threads = 1;
        for (var i = first; i < args.length; i++) {
            if (args[i].startsWith("threads=")) {
                threads = Integer.parseInt(args[i].substring(8));
                continue;
            }
            var cq = args[i].startsWith("q");
            var kbps = Integer.parseInt(cq ? args[i].substring(1) : args[i]);
            if (mode.startsWith("x264")) {
                runX264(input, width, height, frames, fps, mode.substring(5), profile, kbps, threads, cq);
            } else {
                runOurs(input, width, height, frames, fps, H264EncoderSpeed.valueOf(mode), profile, kbps, threads, cq);
            }
        }
    }

    private static void runOurs(Path input, int width, int height, int frames, int fps, H264EncoderSpeed speed,
                                String profileName, int kbps, int threads, boolean cq) throws IOException {
        var data = Files.readAllBytes(input);
        var frameSize = width * height * 3 / 2;
        frames = Math.min(frames, data.length / frameSize);
        var profile = switch (profileName) {
            case "baseline" -> H264Profile.CONSTRAINED_BASELINE;
            case "high" -> H264Profile.HIGH;
            default -> H264Profile.MAIN;
        };
        var config = H264Setup.of(width, height, new Rational(fps, 1), new Rational(1, fps), profile, 0,
                cq ? H264RateControl.CONSTANT_QUALITY : H264RateControl.AVERAGE_BITRATE,
                cq ? 0 : kbps * 1000, cq ? 0 : kbps * 2000, cq ? 0 : kbps * 2000, cq ? kbps : 23,
                fps * 2, speed, ColorInfo.BT709_LIMITED, threads);
        var pictures = new ArrayList<YuvPicture>(frames);
        for (var i = 0; i < frames; i++) {
            var y = new byte[width * height];
            var u = new byte[width * height / 4];
            var v = new byte[width * height / 4];
            System.arraycopy(data, i * frameSize, y, 0, y.length);
            System.arraycopy(data, i * frameSize + y.length, u, 0, u.length);
            System.arraycopy(data, i * frameSize + y.length + u.length, v, 0, v.length);
            pictures.add(YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420)
                    .color(ColorInfo.BT709_LIMITED)
                    .luma(y, width)
                    .chroma(u, v, width / 2)
                    .build());
        }
        var best = Long.MAX_VALUE;
        EncoderHarness.Encoded encoded = null;
        for (var run = 0; run < 2; run++) {
            var packets = new ArrayList<Packet>();
            var start = System.nanoTime();
            try (var encoder = config.encoder()) {
                if (System.getenv("REPORT_AQ") != null) {
                    encoder.core().setAdaptiveQuantization(Double.parseDouble(System.getenv("REPORT_AQ")));
                }
                for (var i = 0; i < frames; i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i, 1, false), packets::add);
                    if (run == 0 && System.getenv("REPORT_VERBOSE") != null) {
                        System.out.println("frame " + i + " qp=" + encoder.core().lastQp() + " bytes="
                                + packets.getLast().size() + " cplx=" + encoder.core().lastComplexity() + " key=" + packets.getLast().keyframe());
                    }
                }
                var elapsed = System.nanoTime() - start;
                best = Math.min(best, elapsed);
                if (run == 1 && System.getenv("REPORT_STAGES") != null) {
                    var stages = encoder.core().stageNanos();
                    System.out.printf(Locale.ROOT, "stages ms/frame: convert %.2f prepare %.2f slices %.2f finish %.2f%n",
                            stages[0] / 1e6 / frames, stages[1] / 1e6 / frames, stages[2] / 1e6 / frames,
                            stages[3] / 1e6 / frames);
                    System.out.println("types I4x4,I8x8,I16x16,PCM,P16x16,P16x8,P8x16,P8x8,Skip,T8x8 = "
                            + java.util.Arrays.toString(encoder.core().typeCounts()));
                }
                encoded = new EncoderHarness.Encoded(config, packets, List.of(), encoder.core().sequenceParameterSet(),
                        encoder.core().pictureParameterSet(), encoder.codecConfig().orElseThrow(),
                        encoder.core().typeCounts());
            }
        }
        var file = EncoderHarness.write("report-" + speed + "-" + kbps + ".264", encoded.annexB());
        var metrics = measure(input, file, width, height, fps, frames);
        System.out.printf(Locale.ROOT, "ours %-8s %-8s %5d kbps -> %7.1f kbps  %6.2f fps  PSNR %6.3f  SSIM %.5f%n",
                speed, profileName, kbps, encoded.totalBytes() * 8.0 * fps / frames / 1000,
                frames / (best / 1e9), metrics[0], metrics[1]);
    }

    private static void runX264(Path input, int width, int height, int frames, int fps, String preset,
                                String profile, int kbps, int threads, boolean cq) {
        var out = EncoderHarness.workFile("x264-" + preset + "-" + kbps + ".264");
        var arguments = new ArrayList<>(List.of("-v", "error", "-y", "-benchmark", "-threads", Integer.toString(threads),
                "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", width + "x" + height, "-r", Integer.toString(fps),
                "-i", input.toString(), "-frames:v", Integer.toString(frames), "-c:v", "libx264", "-preset", preset,
                "-profile:v", profile, "-bf", "0", "-g", Integer.toString(fps * 2), "-x264-params", "threads=" + threads
                        + ":lookahead-threads=1:sliced-threads=0"));
        if (cq) {
            arguments.addAll(List.of("-qp", Integer.toString(kbps)));
        } else {
            arguments.addAll(List.of("-b:v", kbps + "k", "-maxrate", (2 * kbps) + "k", "-bufsize", (2 * kbps) + "k"));
        }
        arguments.addAll(List.of("-f", "h264", out.toString()));
        var start = System.nanoTime();
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(30), arguments);
        var elapsed = System.nanoTime() - start;
        var metrics = measure(input, out, width, height, fps, frames);
        long size;
        try {
            size = Files.size(out);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        System.out.printf(Locale.ROOT, "x264 %-8s %-8s %5d kbps -> %7.1f kbps  %6.2f fps  PSNR %6.3f  SSIM %.5f%n",
                preset, profile, kbps, size * 8.0 * fps / frames / 1000, frames / (elapsed / 1e9), metrics[0],
                metrics[1]);
    }

    /** Returns the overall PSNR (6:1:1 plane weighting over the whole sequence) and the mean luma SSIM. */
    static double[] measure(Path source, Path coded, int width, int height, int fps, int frames) {
        var decoded = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(30), List.of("-v", "error", "-f", "h264", "-i",
                coded.toString(), "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
        byte[] original;
        try {
            original = Files.readAllBytes(source);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return Metrics.compare(original, decoded, width, height, frames);
    }
}
