package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Times full decodes of ten-second x265 clips of a synthetic scene (ffmpeg's {@code mandelbrot}
 * and {@code testsrc2} sources blended): 1080p30 and 720p30 Main profile at preset medium and CRF
 * 28, with one and several threads.
 *
 * <p>Run with {@code org.openjdk.jmh.Main HevcDecoderBenchmark} from the sandbox directory. The
 * setup copies each clip to {@code target/hevc-bench/<stream>.hevc}, so the same input can be timed
 * with {@code ffmpeg -threads 1 -benchmark -i <file> -f null -}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g"})
public class HevcDecoderBenchmark {
    @Param({"1080p", "720p"})
    public String stream;

    @Param({"1", "4", "8"})
    public int threads;

    private VideoTrack track;
    private List<Packet> packets;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        var size = stream.equals("1080p") ? "1920x1080" : "1280x720";
        var file = Fixtures.ffmpeg("hevc", List.of(
                        "-f", "lavfi", "-i", "mandelbrot=size=" + size + ":rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p",
                        "-t", "10", "-c:v", "libx265", "-preset", "medium", "-crf", "28",
                        "-x265-params", "log-level=error", "-f", "hevc"));
        var directory = Path.of("target", "hevc-bench");
        Files.createDirectories(directory);
        Files.copy(file, directory.resolve(stream + ".hevc"), StandardCopyOption.REPLACE_EXISTING);
        packets = HevcTestStreams.splitAnnexB(Files.readAllBytes(file)).stream().map(HevcTestStreams::packet).toList();
        track = HevcTestStreams.track();
    }

    @Benchmark
    public int decode() {
        var count = new int[1];
        try (var decoder = new HevcDecoder(track, HevcDecoderOptions.builder().threads(threads).build())) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> count[0]++);
            }
            decoder.flush(frame -> count[0]++);
        }
        return count[0];
    }
}
