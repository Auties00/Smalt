package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.container.Containers;
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

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Transport stream demux and mux throughput on the two-minute 1080p30 H.264 (8 Mb/s) + AAC
 * (128 kb/s) file of {@code ContainerBenchmark}, stored as a transport stream. Compare with
 * {@code ffmpeg -threads 1 -benchmark -i in.ts -c copy -f null -} and
 * {@code ffmpeg -threads 1 -benchmark -i in.mp4 -c copy -f mpegts -y out.ts}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class TsBenchmark {
    private Path ts;
    private Path mp4;
    private List<Packet> packets;
    private MediaSource source;

    @Setup(Level.Trial)
    public void setup() {
        mp4 = Fixtures.ffmpeg("mp4", List.of(
                        "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=30:duration=120",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=120"),
                List.of("-vf", "noise=alls=12:allf=t", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "8M", "-maxrate", "10M",
                        "-bufsize", "16M", "-g", "60", "-c:a", "aac", "-b:a", "128k"));
        ts = Fixtures.ffmpeg("ts", List.of("-i", mp4.toString()), List.of("-c", "copy"));
        source = MediaSource.of(mp4);
        packets = new ArrayList<>();
        try (var demuxer = Containers.openDemuxer(source)) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
    }

    @Benchmark
    public long demux() {
        try (var input = MediaSource.of(ts); var demuxer = new TsDemuxer(input, TsDemuxerOptions.defaults())) {
            var total = 0L;
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                total += packet.size();
            }
            return total;
        }
    }

    @Benchmark
    public long mux() {
        var sink = new CountingSink();
        try (var input = MediaSource.of(mp4); var demuxer = Containers.openDemuxer(input); var muxer = new TsMuxer(sink)) {
            demuxer.tracks().forEach(muxer::addTrack);
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.position();
    }

    /** Discards what it is given, counting the bytes. */
    private static final class CountingSink implements MediaSink {
        private long count;

        @Override
        public long position() {
            return count;
        }

        @Override
        public void position(long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSeekable() {
            return false;
        }

        @Override
        public void write(ByteBuffer data) {
            count += data.remaining();
            data.position(data.limit());
        }

        @Override
        public void close() {
        }
    }
}
