package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The nightly stress test of the Vector API kernels in the mixed, multi-threaded JVM of an application: media
 * workloads (video, audio, image and capture, several codecs per JVM) run in fresh JVMs ({@link JitWorkload}), one
 * after the other, a fixed number of warm-up rounds and then measured rounds, and a child fails on any allocation the
 * flight recording samples from the Vector API's Java fallback in C2 code after the warm-up, naming the kernel's frame,
 * and on any failed compilation of a Smalt method. Fallback samples from the interpreter or from C1 code (a kernel
 * path the workloads take too seldom to reach C2 during the warm-up) are printed as {@code COLD} lines and do not
 * fail. It complements the deterministic per-kernel guard of each module
 * ({@code testkit.jit.KernelGuard}): profiles here mix like an application's, and the outcome is statistical, so it
 * runs nightly ({@code -Dsmalt.jit.stress=true}) and never retries. The workloads decoding streams made by ffmpeg run
 * on Linux when ffmpeg is installed (CI provisions its oracle build there; the macOS build's libaom encoder crashes);
 * {@code -Djit.childArgs=a,b} adds JVM options to the children (for example
 * {@code -XX:MaxVectorSize=16}) and {@code -Dsmalt.jit.stress.children=5,6} runs only the children of those indices
 * (the {@code child-N} directories of the report).
 */
@DisplayName("JIT stress of the Vector API kernels")
class JitStressTest {
    private static final int WARM_ROUNDS = Integer.getInteger("smalt.jit.stress.warm", 40);
    private static final int MEASURED_ROUNDS = Integer.getInteger("smalt.jit.stress.measure", 8);

    @Test
    @DisplayName("video, audio, image and capture workloads allocate nothing from the Vector API fallback after warm-up")
    void steadyState() throws Exception {
        Skip.unless(Boolean.getBoolean("smalt.jit.stress"), "opt-in:jit-stress",
                "the nightly JIT stress test; -Dsmalt.jit.stress=true runs it");
        var decode = new ArrayList<>(List.of("h264dec:threads=1", "proresdec",
                "dnxhddec"));
        var ffmpeg = Oracle.find(Oracle.Tool.FFMPEG).isPresent()
                && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");
        if (ffmpeg) {
            decode.add("h264dec:threads=2:input=" + x264Stream());
            decode.add("h264dec:threads=1:input=" + x264InterlacedStream());
            decode.add("h264dec:threads=1:input=" + x264HighBitDepthStream());
            decode.add("h264dec:threads=1:input=" + x264Chroma422Stream());
            decode.add("vp8dec:input=" + libvpxStream());
            decode.add("vp9dec:input=" + libvpxVp9Stream());
            decode.add("hevcdec:threads=2:input=" + x265Stream());
        }
        var encode = new ArrayList<>(List.of("h264enc:speed=BALANCED:profile=main:threads=2",
                "h264enc:speed=FASTEST:profile=baseline:threads=1",
                "vp8enc:speed=6:threads=1", "vp8enc:speed=8:threads=2:resilient=false",
                "proresenc", "dnxhdenc"));
        var images = List.of("webpenc", "webpdec", "capture",
                "webpanim:concurrent=2:frames=6");
        var audio = new ArrayList<>(List.of("opusvoice", "opuscall", "aacenc",
                "ac3",
                "flacenc", "alacenc", "mp3enc", "mp2enc",
                "resample", "voice", "amrnb", "amrwb", "heaac"));
        if (ffmpeg) {
            audio.add("mp3dec:input=" + lameStream());
        }
        var stills = List.of("jpegsend", "pngdec", "gifdec",
                "convert", "effects");
        var mixed = new ArrayList<String>();
        mixed.addAll(decode);
        if (ffmpeg) {
            // The AV1 decoder's many kernels would push the mixed child past its compile budget.
            decode.add("av1dec:input=" + libaomStream());
        }
        mixed.addAll(encode);
        // The VP9 encoder's kernels, its own and the VP9 decoder's it reuses, would push the mixed
        // child past its compile budget.
        encode.addAll(List.of("vp9enc:speed=6:threads=1",
                "vp9enc:speed=8:threads=2:resilient=false", "vp9enc:speed=5:threads=1:layers=3"));
        // The MPEG-4 Part 2 and H.263 encoders and the decoder of their streams, kept out of the mixed child for its
        // compile budget like the VP9 encoder.
        encode.addAll(List.of("mpeg4enc:threads=1", "h263enc:threads=2"));
        decode.add("mpeg4dec:threads=2");
        // The AV1 encoder runs in a child of its own: it reuses the AV1 decoder's kernels, and some
        // of them (the 4-point ADST, the 8x4 DCT) run seldom enough that sharing the encoders'
        // child leaves them on the first compilation tier through the warm-up.
        var encodeAv1 = List.of("av1enc:speed=FASTER:threads=1",
                "av1enc:speed=BALANCED:threads=2");
        mixed.addAll(images);
        mixed.addAll(audio);
        mixed.addAll(stills);
        // The codecs of a call: the H.264 encoder in its real-time preset and the single-threaded
        // decoders.
        var realtimeVideo = new ArrayList<>(List.of("h264dec:threads=1",
                "h264enc:rt=true:threads=2",
                "vp8enc:speed=6:threads=2"));
        if (ffmpeg) {
            realtimeVideo.add("vp8dec:input=" + libvpxStream());
            realtimeVideo.add("av1dec:input=" + libaomStream());
            realtimeVideo.add("hevcdec:threads=1:input=" + x265Stream());
        }
        realtimeVideo.add("av1enc:speed=FASTER:threads=1");
        var children = List.of(decode, encode, encodeAv1, images, audio, stills, mixed, realtimeVideo);
        var failures = new ArrayList<String>();
        var selected = System.getProperty("smalt.jit.stress.children", "").strip();
        for (var i = 0; i < children.size(); i++) {
            if (!selected.isEmpty() && !List.of(selected.split(",")).contains(String.valueOf(i))) {
                continue;
            }
            var failure = run("child-" + i, children.get(i));
            if (failure != null) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            fail(String.join("\n\n", failures));
        }
    }

    private static String x264Stream() {
        return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                        "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-profile:v", "high", "-f", "h264"))
                .toAbsolutePath().toString();
    }

    private static String x264InterlacedStream() {
        return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                        "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-profile:v", "high",
                        "-x264-params", "interlaced=1:tff=1", "-f", "h264"))
                .toAbsolutePath().toString();
    }

    private static String x264HighBitDepthStream() {
        return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p10le", "-t", "2",
                        "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-f", "h264"))
                .toAbsolutePath().toString();
    }

    private static String x264Chroma422Stream() {
        return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv422p", "-t", "2",
                        "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-x264-params",
                        "interlaced=1:tff=1", "-f", "h264"))
                .toAbsolutePath().toString();
    }

    private static String x265Stream() {
        return Fixtures.ffmpeg("hevc", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                        "-c:v", "libx265", "-preset", "medium", "-crf", "26", "-x265-params", "log-level=error",
                        "-f", "hevc"))
                .toAbsolutePath().toString();
    }

    private static String libvpxStream() {
        return Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                        "-c:v", "libvpx", "-b:v", "1M", "-f", "ivf"))
                .toAbsolutePath().toString();
    }

    private static String libaomStream() {
        return Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                        "-c:v", "libaom-av1", "-usage", "realtime", "-cpu-used", "8", "-b:v", "1M", "-tiles", "2x1",
                        "-f", "ivf"))
                .toAbsolutePath().toString();
    }

    private static String libvpxVp9Stream() {
        return Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                        "-c:v", "libvpx-vp9", "-b:v", "1M", "-g", "10", "-tile-columns", "1", "-cpu-used", "4", "-f",
                        "ivf"))
                .toAbsolutePath().toString();
    }

    private static String lameStream() {
        return Fixtures.ffmpeg("mp3", List.of("-f", "lavfi", "-i", "aevalsrc=exprs='0.25*sin(2*PI*(220+55*floor(mod(t*2\\,4)))*t)"
                                + "*exp(-4*mod(t\\,0.5))+0.15*sin(2*PI*(330+110*floor(mod(t*3\\,5)))*t)|0.2*sin(2*PI*(277+55"
                                + "*floor(mod(t*2\\,4)))*t)+0.1*sin(2*PI*550*t)*exp(-8*mod(t\\,0.25))':s=44100:d=10",
                        "-f", "lavfi", "-i", "anoisesrc=color=pink:seed=7:amplitude=0.05:sample_rate=44100:duration=10"),
                List.of("-filter_complex", "[0][1]amix=inputs=2:normalize=0", "-c:a", "libmp3lame", "-b:a", "128k"))
                .toAbsolutePath().toString();
    }

    /** Runs one child JVM and returns a description of its failure, or null. */
    private static String run(String name, List<String> workloads) throws Exception {
        var directory = Path.of("target", "jit-stress", name).toAbsolutePath();
        Files.createDirectories(directory);
        var log = directory.resolve("stdout.log");
        var errors = directory.resolve("stderr.log");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-modules", "jdk.incubator.vector", "-Xmx512m", "-XX:+UseSerialGC"));
        for (var option : System.getProperty("jit.childArgs", "").split(",")) {
            if (!option.isBlank()) {
                command.add(option.strip());
            }
        }
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")), JitWorkload.class.getName(), "warm=" + WARM_ROUNDS,
                "measure=" + MEASURED_ROUNDS, "recording=" + directory));
        command.addAll(workloads);
        var process = new ProcessBuilder(command).redirectOutput(log.toFile()).redirectError(errors.toFile()).start();
        if (!process.waitFor(90, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor();
            return name + " did not finish within 90 minutes; see " + directory;
        }
        var lines = Files.readAllLines(log);
        lines.stream().filter(line -> line.startsWith("COLD ")).forEach(line -> System.out.println(name + " " + line));
        var problems = lines.stream().filter(line -> line.startsWith("FALLBACK ") || line.startsWith("FAILED ")
                || line.startsWith("WHY ")).toList();
        var done = lines.stream().anyMatch(line -> line.startsWith("DONE"));
        if (done && problems.isEmpty() && process.exitValue() == 0) {
            return null;
        }
        var tail = Files.readAllLines(errors);
        return name + (done ? "" : " ended without a result (exit code " + process.exitValue() + ")") + ":\n  "
                + String.join("\n  ", problems)
                + (done ? "" : "\nerror output:\n" + String.join("\n", tail.subList(Math.max(0, tail.size() - 20),
                tail.size())))
                + "\nreproduce with:\n  " + String.join(" ", command) + "\nlogs: " + directory;
    }
}
