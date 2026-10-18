package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
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
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Decodes 60 s of 44.1 kHz stereo Vorbis (libvorbis, {@code -q:a 5}, about 156 kb/s) per
 * operation; compare with {@code ffmpeg -threads 1 -benchmark -c:a vorbis -i bench.ogg -f null -}.
 * The {@code simd} parameter forces the scalar or vector kernel.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class VorbisDecodeBenchmark {
    @Param({"vector", "scalar"})
    public String kernel;

    private AudioTrack track;
    private Packet[] packets;
    private VorbisKernel selected;

    static java.nio.file.Path input() {
        return Fixtures.ffmpeg("ogg", List.of(
                "-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.25:seed=7:sample_rate=44100:duration=60",
                "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=44100:duration=60",
                "-f", "lavfi", "-i", "sine=frequency=554:sample_rate=44100:duration=60"), List.of(
                "-filter_complex", "[0][1][2]amix=inputs=3:normalize=0,aformat=channel_layouts=mono[m];"
                        + "[m]asplit[a][b];[b]adelay=13[bd];[a][bd]join=inputs=2:channel_layout=stereo",
                "-c:a", "libvorbis", "-q:a", "5"));
    }

    @Setup(Level.Trial)
    public void setup() throws IOException {
        var link = OggVorbis.read(Files.readAllBytes(input())).getFirst();
        track = link.track();
        var audio = link.audio();
        packets = new Packet[audio.size()];
        for (var i = 0; i < packets.length; i++) {
            packets[i] = new Packet(0, audio.get(i), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true);
        }
        selected = "scalar".equals(kernel) ? new ScalarVorbisKernel() : VorbisKernel.INSTANCE;
    }

    @Benchmark
    public void decode60s(Blackhole blackhole) {
        var decoder = new VorbisDecoder(track, com.github.auties00.smalt.MediaLimits.DEFAULT, selected);
        for (var packet : packets) {
            decoder.decode(packet, (AudioBuffer buffer) -> blackhole.consume(buffer.plane(0)[buffer.frames() - 1]));
        }
    }
}
