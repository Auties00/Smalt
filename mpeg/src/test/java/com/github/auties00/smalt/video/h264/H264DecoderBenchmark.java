package com.github.auties00.smalt.video.h264;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Times full decodes of ten-second x264 clips of a synthetic scene (ffmpeg's {@code mandelbrot}
 * and {@code testsrc2} sources blended): 1080p30 High profile with CABAC and B-frames (preset
 * medium, CRF 23, about 10 Mbit/s) and 720p30 Baseline profile with CAVLC, with one and several
 * frame threads.
 *
 * <p>Run with {@code org.openjdk.jmh.Main H264DecoderBenchmark} from the sandbox directory. The
 * setup copies each clip to {@code target/h264-bench/<stream>.h264}, so the same input can be
 * timed with {@code ffmpeg -threads 1 -benchmark -i <file> -f null -}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g"})
public class H264DecoderBenchmark {
    @Param({"1080p-high-cabac", "720p-baseline-cavlc"})
    public String stream;

    @Param({"1", "4", "8"})
    public int threads;

    private VideoTrack track;
    private List<Packet> packets;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        var size = stream.startsWith("1080p") ? "1920x1080" : "1280x720";
        var profile = stream.contains("high") ? "high" : "baseline";
        var file = Fixtures.ffmpeg("h264", List.of(
                        "-f", "lavfi", "-i", "mandelbrot=size=" + size + ":rate=30",
                        "-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=30"),
                List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p",
                        "-t", "10", "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-profile:v", profile, "-f", "h264"));
        var directory = Path.of("target", "h264-bench");
        Files.createDirectories(directory);
        Files.copy(file, directory.resolve(stream + ".h264"), StandardCopyOption.REPLACE_EXISTING);
        var payloads = new ArrayList<byte[]>();
        for (var unit : H264TestStreams.accessUnits(H264TestStreams.nalUnits(Files.readAllBytes(file)))) {
            payloads.add(H264TestStreams.annexB(unit));
        }
        packets = H264TestStreams.packets(payloads);
        track = H264TestStreams.track(Optional.empty(), Optional.empty());
    }

    @Benchmark
    public int decode() {
        var count = new int[1];
        try (var decoder = new H264Decoder(track, H264DecoderOptions.builder().threads(threads).build())) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> count[0]++);
            }
            decoder.flush(frame -> count[0]++);
        }
        return count[0];
    }
}
