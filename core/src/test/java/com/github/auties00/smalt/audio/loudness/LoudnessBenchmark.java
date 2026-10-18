package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.ChannelLayout;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures the true-peak kernel alone (4096 samples of the 48 kHz filter, vector against scalar)
 * and the whole meter over 60 s of 48 kHz stereo noise, with and without the true peak; run with
 * {@code -Dsmalt.simd=false} for the scalar meter.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class LoudnessBenchmark {
    private static final int RATE = 48_000;

    private float[] input;
    private TruePeakMeter.Filter filter;
    private TruePeakKernel scalar;
    private TruePeakKernel vector;
    private float[][] program;

    @Setup
    public void setup() {
        var random = new SplittableRandom(5);
        filter = TruePeakMeter.filter(RATE);
        input = new float[filter.history() + 4096];
        for (var index = 0; index < input.length; index++) {
            input[index] = (float) random.nextDouble(-0.5, 0.5);
        }
        scalar = new ScalarTruePeakKernel();
        vector = VectorLoudnessKernels.truePeak();
        program = new float[2][60 * RATE];
        var low = 0.0;
        for (var index = 0; index < program[0].length; index++) {
            low += 0.05 * (random.nextDouble(-1, 1) - low);
            var envelope = 0.3 + 0.2 * Math.sin(index * 2 * Math.PI / (3.7 * RATE));
            program[0][index] = (float) (envelope * low * 3);
            program[1][index] = (float) (envelope * random.nextDouble(-0.2, 0.2));
        }
    }

    @Benchmark
    public float kernelVector() {
        return vector.peak(input, 4096, filter.coefficients(), filter.offsets(), filter.phases(), filter.taps());
    }

    @Benchmark
    public float kernelScalar() {
        return scalar.peak(input, 4096, filter.coefficients(), filter.offsets(), filter.phases(), filter.taps());
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public LoudnessStats meter60s() {
        var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE);
        for (var offset = 0; offset < program[0].length; offset += 4096) {
            meter.accept(program, offset, Math.min(4096, program[0].length - offset));
        }
        return meter.stats();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public LoudnessStats meter60sLoudnessOnly() {
        var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE, LoudnessMeterOptions.loudnessOnly());
        for (var offset = 0; offset < program[0].length; offset += 4096) {
            meter.accept(program, offset, Math.min(4096, program[0].length - offset));
        }
        return meter.stats();
    }
}
