package com.github.auties00.smalt.image.webp.internal.lossless;

import com.github.auties00.smalt.internal.simd.Simd;
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

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmarks of the lossless encoder kernels, scalar against vector: the predictor residuals
 * of one 32x32 tile per mode.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules=jdk.incubator.vector", "-Xmx1g"})
public class LosslessKernelBenchmark {
    @Param({"scalar", "vector"})
    public String impl;

    @Param({"0", "1", "5", "10", "11", "12", "13"})
    public int mode;

    LosslessDsp dsp;
    int[] image;
    int[] out = new int[32];

    @Setup
    public void setup() {
        dsp = impl.equals("vector") && Simd.ENABLED ? VectorLosslessDsp.create() : new ScalarLosslessDsp();
        image = new int[64 * 40];
        var random = new Random(1);
        for (var i = 0; i < image.length; i++) {
            image[i] = random.nextInt() & 0x0F3F7FFF;
        }
    }

    @Benchmark
    public int[] tile() {
        for (var y = 1; y < 33; y++) {
            var pos = y * 64 + 1;
            dsp.predictorResiduals(mode, image, pos, image, pos - 64, 32, out, 0);
        }
        return out;
    }
}
