package com.github.auties00.smalt.audio.opus.internal.silk;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Measures the SILK encoder alone on 60 s of synthetic speech: the voice-note configuration
 * (16 kHz mono, 16 kbps, complexity 10) and a 48 kHz stereo configuration.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = "--add-modules=jdk.incubator.vector")
public class SilkEncoderBenchmark {
    @Param({"voiceNote", "stereo48k"})
    public String config;

    private short[] pcm;

    private SilkEncoderHarness.Config cfg;

    @Setup
    public void setup() {
        cfg = switch (config) {
            case "voiceNote" -> new SilkEncoderHarness.Config(16000, 1, 1, 16000, 8000, 16000, 20, 16000, 10, 0, 0, 0,
                    0, 0, 0, 0, 0);
            default -> new SilkEncoderHarness.Config(48000, 2, 2, 16000, 8000, 16000, 20, 32000, 10, 0, 0, 0, 0, 0, 0,
                    0, 0);
        };
        pcm = SilkEncoderHarness.speechLike(cfg.apiFs(), cfg.chApi(), 60.0, 42);
    }

    @Benchmark
    public byte[] encode60s() {
        return SilkEncoderHarness.encode(pcm, cfg);
    }
}
