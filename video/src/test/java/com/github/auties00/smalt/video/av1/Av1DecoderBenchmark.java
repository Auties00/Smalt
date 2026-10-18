package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.container.ivf.IvfDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
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
import java.util.concurrent.TimeUnit;

/**
 * Times full decodes of 100-frame AV1 clips of natural Xiph sequences encoded by libaom: 720p50
 * "parkrun" with the real-time encoder (cpu-used 8, 1.5 Mbit/s, one tile), 720p50 "shields" with
 * the good-quality encoder (cpu-used 6, CRF 32, one tile), and "shields" upscaled to 1080p
 * (cpu-used 6, CRF 32, 2x2 tiles). Each clip is decoded on one thread and with the module's
 * threads, with the Vector API kernels and with the scalar ones, so every ratio comes from one
 * invocation.
 *
 * <p>Run with {@code org.openjdk.jmh.Main Av1DecoderBenchmark} from the sandbox directory. The
 * setup copies each clip to {@code target/av1-bench/<stream>.ivf}, so the same input can be timed
 * with {@code ffmpeg -threads 1 -benchmark -c:v libdav1d -i <file> -f null -} (and
 * {@code libaom-av1}).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class Av1DecoderBenchmark {
    private static final String XIPH = "https://media.xiph.org/video/derf/y4m/";

    @Param({"720p-realtime", "720p-good", "1080p-good-tiles"})
    public String stream;

    private VideoTrack track;
    private List<Packet> packets;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        var parkrun = Conformance.fetch("720p50_parkrun_ter.y4m", XIPH + "720p50_parkrun_ter.y4m",
                "5a28e2b1e22877d6a8d8301a3e570100dee60c8071e5d2e352ba5cf3a45d848b");
        var shields = Conformance.fetch("720p50_shields_ter.y4m", XIPH + "720p50_shields_ter.y4m",
                "b5b09557dc76aec2a854dc34e0fe200bdb1ee5a2eee1dbb61cc1ec6861e6a742");
        var file = switch (stream) {
            case "720p-realtime" -> Fixtures.ffmpeg("ivf", List.of("-i", parkrun.toString(), "-frames:v", "100"),
                    List.of("-c:v", "libaom-av1", "-usage", "realtime", "-cpu-used", "8", "-b:v", "1500k",
                            "-threads", "8"));
            case "720p-good" -> Fixtures.ffmpeg("ivf", List.of("-i", shields.toString(), "-frames:v", "100"),
                    List.of("-c:v", "libaom-av1", "-cpu-used", "6", "-crf", "32", "-row-mt", "1", "-threads", "8"));
            default -> Fixtures.ffmpeg("ivf", List.of("-i", shields.toString(), "-frames:v", "100",
                            "-vf", "scale=1920:1080:flags=lanczos"),
                    List.of("-c:v", "libaom-av1", "-cpu-used", "6", "-crf", "32", "-tiles", "2x2", "-row-mt", "1",
                            "-threads", "8"));
        };
        var directory = Path.of("target", "av1-bench");
        Files.createDirectories(directory);
        Files.copy(file, directory.resolve(stream + ".ivf"), StandardCopyOption.REPLACE_EXISTING);
        try (var demuxer = new IvfDemuxer(MediaSource.of(Files.readAllBytes(file)))) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            var read = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                read.add(packet);
            }
            packets = read;
        }
    }

    private int decodeAll() {
        var count = new int[1];
        try (var decoder = new Av1Decoder(track)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> count[0]++);
            }
        }
        return count[0];
    }

    @Benchmark
    @Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g", "-Dsmalt.threads=1"})
    public int singleThread() {
        return decodeAll();
    }

    @Benchmark
    @Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g"})
    public int multiThread() {
        return decodeAll();
    }

    @Benchmark
    @Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g", "-Dsmalt.threads=1",
            "-Dsmalt.simd=false"})
    public int singleThreadScalar() {
        return decodeAll();
    }

    @Benchmark
    @Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g", "-Dsmalt.simd=false"})
    public int multiThreadScalar() {
        return decodeAll();
    }
}
