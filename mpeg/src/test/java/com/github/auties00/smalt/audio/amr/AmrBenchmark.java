package com.github.auties00.smalt.audio.amr;

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
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the AMR decoders and encoders on 60 seconds of the test signal (voiced sound, noise
 * and silence, looped) at the highest mode of each codec, with discontinuous transmission off.
 *
 * <p>Run with {@code org.openjdk.jmh.Main AmrBenchmark} from the sandbox directory; the setup
 * writes the input as {@code target/amr-bench/input-<rate>.raw} and Smalt's coded stream as
 * {@code target/amr-bench/<mode>.amr}, so the same work can be timed with
 * {@code ffmpeg -threads 1 -benchmark} and {@code libopencore_amrnb}, {@code libopencore_amrwb} and
 * {@code libvo_amrwbenc}. One minute of audio decoded in {@code t} milliseconds is a real-time
 * factor of {@code 60000 / t}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1)
public class AmrBenchmark {
    /** Sixty seconds of input and its coded frames. */
    @State(Scope.Benchmark)
    public static class Media {
        @Param({"NB_12_2", "WB_23_85"})
        public AmrMode mode;

        short[] pcm;
        List<Packet> packets;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            var rate = mode.sampleRate();
            var signal = AmrTestSupport.signal(rate);
            pcm = new short[rate * 60];
            for (var i = 0; i < pcm.length; i += signal.length) {
                System.arraycopy(signal, 0, pcm, i, Math.min(signal.length, pcm.length - i));
            }
            packets = AmrTestSupport.encode(AmrEncoderOptions.of(mode), pcm);
            var directory = Path.of("target", "amr-bench");
            Files.createDirectories(directory);
            Files.write(directory.resolve("input-" + rate + ".raw"), AmrTestSupport.bytes(pcm));
            Files.write(directory.resolve(mode + ".amr"), AmrTestSupport.storage(mode.codec(), packets));
        }
    }

    @Benchmark
    public void decode60s(Media media, Blackhole blackhole) {
        try (var decoder = new AmrDecoder(AmrTestSupport.track(media.mode.codec()))) {
            for (var packet : media.packets) {
                decoder.decode(packet, blackhole::consume);
            }
        }
    }

    @Benchmark
    public void encode60s(Media media, Blackhole blackhole) {
        var samples = AmrTestSupport.frameSamples(media.mode.codec());
        var frame = new byte[61];
        try (var encoder = new AmrEncoder(AmrEncoderOptions.of(media.mode))) {
            for (var offset = 0; offset + samples <= media.pcm.length; offset += samples) {
                blackhole.consume(encoder.encodeFrame(media.pcm, offset, frame, 0));
            }
        }
    }
}
