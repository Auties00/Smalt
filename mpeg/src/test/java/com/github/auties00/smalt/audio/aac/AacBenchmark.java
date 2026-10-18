package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AacKernel;
import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.Synthesis;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
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
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the AAC decoder and encoder on 60 seconds of 48 kHz stereo, the HE-AAC decoder on
 * about 60 seconds of looped conformance streams, and the synthesis filterbank with both kernels.
 *
 * <p>Run with {@code org.openjdk.jmh.Main AacBenchmark} from the sandbox directory; the setup
 * writes the benchmark input as {@code target/aac-bench/input.wav} and the ffmpeg-coded stream as
 * a fixture, so the same files can be timed with {@code ffmpeg -threads 1 -benchmark}. The HE-AAC
 * setup writes {@code target/aac-bench/<stream>.loops}, the number of times the stream is decoded,
 * for {@code ffmpeg -threads 1 -benchmark -stream_loop <loops - 1> -i <stream>.mp4 -f null -}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class AacBenchmark {
    /** Sixty seconds of music-like stereo and its ffmpeg-coded 128 kbps stream. */
    @State(Scope.Benchmark)
    public static class Media {
        AacTestSupport.Stream stream;
        List<Packet> packets;
        float[][] input;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            input = TestSignals.generate(TestSignals.Kind.MUSIC, 48000, 2, 60);
            var directory = Path.of("target", "aac-bench");
            Files.createDirectories(directory);
            Files.write(directory.resolve("input.wav"), TestSignals.wav(input, 48000));
            var file = Fixtures.ffmpeg("aac", List.of("-i", directory.resolve("input.wav").toString()),
                    List.of("-c:a", "aac", "-b:a", "128000"));
            Files.copy(file, directory.resolve("ffmpeg-128k.aac"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            stream = AacTestSupport.parseAdts(Files.readAllBytes(file));
            packets = stream.frames().stream().map(frame -> new Packet(1, frame, 0, 0, 0, true)).toList();
        }
    }

    @Benchmark
    public void decode60s(Media media, Blackhole blackhole) {
        var decoder = new AacDecoder(media.stream.track());
        for (var packet : media.packets) {
            decoder.decode(packet, blackhole::consume);
        }
    }

    @Benchmark
    public void encode60s128k(Media media, Blackhole blackhole) {
        encode(media.input, 128000, blackhole);
    }

    @Benchmark
    public void encode60s192k(Media media, Blackhole blackhole) {
        encode(media.input, 192000, blackhole);
    }

    private static void encode(float[][] input, int bitrate, Blackhole blackhole) {
        var encoder = new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.STEREO, bitrate));
        encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, input, input[0].length), blackhole::consume);
        encoder.flush(blackhole::consume);
    }

    /** An HE-AAC conformance stream, repeated to about 60 seconds. */
    @State(Scope.Benchmark)
    public static class HighEfficiency {
        @Param({"al_sbr_sr_48_2_fsaac24", "al_sbr_ps_01"})
        public String name;

        AacTestSupport.Stream stream;
        List<Packet> packets;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            stream = AacTestSupport.parseMp4(Files.readAllBytes(HeAacConformanceTest.fetch(name)));
            var seconds = stream.frames().size() * 1024.0 / stream.sampleRate();
            var loops = (int) Math.ceil(60 / seconds);
            var repeated = new java.util.ArrayList<Packet>();
            for (var loop = 0; loop < loops; loop++) {
                for (var frame : stream.frames()) {
                    repeated.add(new Packet(1, frame, 0, 0, 0, true));
                }
            }
            packets = repeated;
            var directory = Path.of("target", "aac-bench");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name + ".loops"), Integer.toString(loops));
        }
    }

    @Benchmark
    public void decodeHighEfficiency60s(HighEfficiency media, Blackhole blackhole) {
        var decoder = new AacDecoder(HeAacConformanceTest.detectingTrack(media.stream));
        for (var packet : media.packets) {
            decoder.decode(packet, blackhole::consume);
        }
    }

    /** One long-window synthesis state per kernel. */
    @State(Scope.Thread)
    public static class Filterbank {
        @Param({"scalar", "vector"})
        public String kernel;

        Synthesis synthesis;
        float[] spectrum = new float[1024];
        float[] overlap = new float[1024];
        float[] out = new float[1024];

        @Setup(Level.Trial)
        public void setUp() {
            AacKernel implementation = kernel.equals("scalar") ? AacKernels.scalar() : AacKernels.vector();
            synthesis = new Synthesis(1024, implementation);
            var random = new Random(1);
            for (var i = 0; i < spectrum.length; i++) {
                spectrum[i] = (float) random.nextGaussian();
            }
        }
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public float synthesizeLong(Filterbank state) {
        state.synthesis.synthesize(state.spectrum, 0, 1, 1, state.overlap, state.out, 0);
        return state.out[17];
    }
}
