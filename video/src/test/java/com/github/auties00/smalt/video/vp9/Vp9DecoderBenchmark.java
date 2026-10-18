package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.video.vp9.internal.Vp9FrameDecoder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Times full decodes of 60-frame libvpx-vp9 clips of natural video: the Xiph {@code stockholm}
 * 720p clip at 2 Mbit/s and the {@code parkrun} clip scaled to 1080p at 6 Mbit/s, both with
 * four tile columns (the libvpx default for these sizes) and alternate reference frames, on
 * the calling thread alone and with the module's helper threads.
 *
 * <p>Run with {@code org.openjdk.jmh.Main Vp9DecoderBenchmark} from the sandbox directory; the
 * Xiph clips must be cached in {@code Conformance.directory()}. The setup writes each
 * clip to {@code target/vp9-bench/<stream>.webm}, so the same input can be timed with
 * {@code ffmpeg -threads 1 -c:v vp9 -benchmark -i <file> -f null -} and with
 * {@code -c:v libvpx-vp9}. The time per frame is the score divided by 60. One decoder decodes the clip
 * over and over, restarting at its key frame, so its frame buffers are allocated once; the long
 * warm-up lets the compilers finish the decoder's many large methods.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g"})
public class Vp9DecoderBenchmark {
    @Param({"720p-stockholm", "1080p-parkrun"})
    public String stream;

    @Param({"false", "true"})
    public boolean parallel;

    private List<byte[]> packets;

    private Vp9FrameDecoder decoder;

    static Path clip(String stream) throws IOException {
        var directory = Path.of("target", "vp9-bench");
        var target = directory.resolve(stream + ".webm");
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(directory);
        var cache = Path.of(com.github.auties00.smalt.testkit.Conformance.directory().toString());
        var source = cache.resolve(stream.startsWith("720p") ? "720p5994_stockholm_ter.y4m" : "720p50_parkrun_ter.y4m");
        var temporary = directory.resolve(stream + ".tmp.webm");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(20), List.of("-v", "error", "-y", "-i", source.toString(),
                "-vf", stream.startsWith("720p") ? "null" : "scale=1920:1080", "-frames:v", "60", "-c:v", "libvpx-vp9",
                "-b:v", stream.startsWith("720p") ? "2M" : "6M", "-tile-columns", "2", "-cpu-used", "2",
                "-auto-alt-ref", "1", "-lag-in-frames", "16", "-an", "-f", "webm", temporary.toString()));
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        packets = Vp9DifferentialTest.webmPackets(clip(stream));
        decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, parallel);
    }

    @Benchmark
    public int decode() {
        var shown = 0;
        for (var packet : packets) {
            decoder.decodePacket(packet, 0, packet.length);
            if (decoder.shownFrame() != null) {
                shown++;
            }
        }
        return shown;
    }
}
