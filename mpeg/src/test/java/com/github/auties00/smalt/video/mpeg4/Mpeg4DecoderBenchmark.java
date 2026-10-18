package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
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
import java.util.concurrent.TimeUnit;

/**
 * Times full decodes of five-second 720p30 MPEG-4 Part 2 clips of a synthetic scene (ffmpeg's
 * {@code mandelbrot} and {@code testsrc2} sources blended): an Advanced Simple Profile stream from
 * ffmpeg's encoder (two B-VOPs, quarter sample, four vectors) and a Simple Profile stream, with
 * one and several threads.
 *
 * <p>Run with {@code org.openjdk.jmh.Main Mpeg4DecoderBenchmark} from the sandbox directory. The
 * setup copies each clip to {@code target/mpeg4-bench/<stream>.avi}, so the same input can be
 * timed with {@code ffmpeg -threads 1 -benchmark -i <file> -f null -}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g"})
public class Mpeg4DecoderBenchmark {
    @Param({"asp", "sp"})
    public String stream;

    @Param({"1", "4"})
    public int threads;

    private VideoTrack track;
    private List<Packet> packets;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        var options = stream.equals("asp") ? List.of("-bf", "2", "-flags", "+qpel+mv4", "-trellis", "1")
                : List.of("-bf", "0", "-flags", "+mv4");
        var output = new ArrayList<>(List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p",
                "-t", "5", "-c:v", "mpeg4", "-b:v", "3M", "-g", "60"));
        output.addAll(options);
        var file = Fixtures.ffmpeg("avi", List.of(
                "-f", "lavfi", "-i", "mandelbrot=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30"), output);
        var directory = Path.of("target", "mpeg4-bench");
        Files.createDirectories(directory);
        Files.copy(file, directory.resolve(stream + ".avi"), StandardCopyOption.REPLACE_EXISTING);
        var list = new ArrayList<Packet>();
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                list.add(packet);
            }
        }
        packets = list;
    }

    @Benchmark
    public int decode() {
        var count = new int[1];
        try (var decoder = new Mpeg4Decoder(track, VideoDecoderOptions.builder().threads(threads).build())) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> count[0]++);
            }
            decoder.flush(frame -> count[0]++);
        }
        return count[0];
    }
}
