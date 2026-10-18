package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures JPEG 2000 decoding and encoding of a 2048x1536 photo-like RGB image (ffmpeg's testsrc2
 * with uniform noise), coded by {@code opj_compress} losslessly (5-3, one tile) and lossily (9-7 at
 * 20:1), at full size and at a quarter of the size. Run with
 * {@code mvn -o -DskipTests test-compile exec:exec -Dexec.executable=java
 * -Dexec.args="--add-modules jdk.incubator.vector -cp %classpath org.openjdk.jmh.Main Jp2Benchmark"},
 * adding {@code -jvmArgsAppend -Dsmalt.threads=1} for the single-threaded figures; compare with
 * {@code opj_decompress -threads 1} and {@code -threads N} on the same files, which
 * {@code target/jp2-bench} keeps.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(value = 3, jvmArgsAppend = {"--add-modules=jdk.incubator.vector", "-Xmx1g"})
public class Jp2Benchmark {
    static final int WIDTH = 2048;
    static final int HEIGHT = 1536;

    @Param({"photo_lossless.jp2", "photo_r20.jp2"})
    public String input;

    private byte[] data;
    private Picture picture;

    @Setup
    public void setup() throws IOException {
        data = Files.readAllBytes(ensure(input));
        picture = Jp2Decoder.decode(MediaSource.of(data)).picture();
    }

    @Benchmark
    public Object decodeFull() {
        return Jp2Decoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults()).picture();
    }

    @Benchmark
    public Object decodeQuarter() {
        return Jp2Decoder.decode(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(WIDTH / 4, HEIGHT / 4)).picture();
    }

    @Benchmark
    public Object encode() {
        var options = input.contains("lossless") ? Jp2EncoderOptions.lossless() : Jp2EncoderOptions.lossy(20);
        return Jp2Encoder.encode(picture, options);
    }

    static Path path(String name) {
        return Path.of(System.getProperty("jp2.bench.dir", "target/jp2-bench")).resolve(name);
    }

    static synchronized Path ensure(String name) {
        var path = path(name);
        if (Files.isRegularFile(path)) {
            return path;
        }
        try {
            Files.createDirectories(path.getParent());
            switch (name) {
                case "photo.ppm" -> Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-y",
                        "-f", "lavfi", "-i", "testsrc2=size=" + WIDTH + "x" + HEIGHT + ":rate=1,noise=alls=30:allf=u",
                        "-frames:v", "1", "-pix_fmt", "rgb24", path.toString()));
                case "photo_lossless.jp2" -> opjCompress(ensure("photo.ppm"), path, List.of());
                case "photo_r20.jp2" -> opjCompress(ensure("photo.ppm"), path, List.of("-I", "-r", "20"));
                default -> throw new IllegalArgumentException("unknown benchmark input " + name);
            }
            return path;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void opjCompress(Path input, Path output, List<String> options) throws IOException {
        var command = new ArrayList<String>(List.of("opj_compress", "-i", input.toString(), "-o", output.toString()));
        command.addAll(options);
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        try {
            if (process.waitFor() != 0) {
                throw new IOException("opj_compress failed for " + output);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }
}
