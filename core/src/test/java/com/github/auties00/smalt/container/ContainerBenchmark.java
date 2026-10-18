package com.github.auties00.smalt.container;

import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Demux and remux throughput on a two-minute 1080p30 H.264 (8 Mb/s) + AAC (128 kb/s) file, stored
 * as MP4 (moov at the end) and as Matroska. Compare with
 * {@code ffmpeg -threads 1 -benchmark -i in -c copy -f null -} and
 * {@code ffmpeg -threads 1 -benchmark -i in.mp4 -c copy -movflags +faststart out.mp4}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ContainerBenchmark {
    private Path mp4;
    private Path mkv;
    private Path output;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        mp4 = Fixtures.ffmpeg("mp4", List.of(
                        "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=30:duration=120",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=120"),
                List.of("-vf", "noise=alls=12:allf=t", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "8M", "-maxrate", "10M",
                        "-bufsize", "16M", "-g", "60", "-c:a", "aac", "-b:a", "128k"));
        mkv = Fixtures.ffmpeg("mkv", List.of("-i", mp4.toString()), List.of("-c", "copy"));
        output = Files.createTempFile("smalt-remux-bench-", ".mp4");
        output.toFile().deleteOnExit();
    }

    private static long drain(Demuxer demuxer) {
        var total = 0L;
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            total += packet.size();
        }
        return total;
    }

    @Benchmark
    public long demuxMp4() {
        try (var source = MediaSource.of(mp4); var demuxer = new Mp4Demuxer(source)) {
            return drain(demuxer);
        }
    }

    @Benchmark
    public long demuxMkv() {
        try (var source = MediaSource.of(mkv); var demuxer = new MatroskaDemuxer(source)) {
            return drain(demuxer);
        }
    }

    @Benchmark
    public long remuxMp4FastStart() throws IOException {
        try (var source = MediaSource.of(mp4); var channel = FileChannel.open(output, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            return Mp4Remuxer.remux(source, MediaSink.of(channel), RemuxOptions.video()).outputSize();
        }
    }

    @Benchmark
    public long remuxMkvFastStart() throws IOException {
        try (var source = MediaSource.of(mkv); var channel = FileChannel.open(output, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            return Mp4Remuxer.remux(source, MediaSink.of(channel), RemuxOptions.video()).outputSize();
        }
    }
}
