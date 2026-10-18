package com.github.auties00.smalt.image.jxl.internal.render;

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

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH A/B benchmark of the edge-preserving filter kernels: one 2048-sample row of step 0 (12 taps,
 * plus-shaped distances) and of step 1 (4 taps), scalar against Vector API. Run with
 * {@code mvn -o -DskipTests test-compile exec:exec -Dexec.executable=java
 * -Dexec.args="--add-modules jdk.incubator.vector -cp %classpath org.openjdk.jmh.Main EpfKernelBenchmark"}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-modules=jdk.incubator.vector", "-Xmx2g"})
@State(Scope.Benchmark)
public class EpfKernelBenchmark {
    static final int WIDTH = 2048;

    @Param({"scalar", "vector"})
    public String kernel;

    @Param({"0", "1"})
    public int step;

    EpfKernel implementation;
    float[][] planes;
    float[][] outputs;
    float[] slopes;
    int[] taps;
    int[] dist;
    float[] scratch;

    @Setup(Level.Trial)
    public void setup() {
        implementation = kernel.equals("scalar") ? new ScalarEpfKernel() : new VectorEpfKernel();
        var paddedWidth = WIDTH + 6;
        var random = new Random(3);
        planes = new float[3][paddedWidth * 7];
        for (var plane : planes) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = random.nextFloat();
            }
        }
        outputs = new float[3][WIDTH];
        slopes = new float[WIDTH];
        for (var x = 0; x < WIDTH; x++) {
            slopes[x] = -2.0f - (x & 7) * 0.1f;
        }
        taps = step == 0
                ? new int[]{-2 * paddedWidth, -paddedWidth - 1, -paddedWidth, -paddedWidth + 1, -2, -1, 1, 2,
                paddedWidth - 1, paddedWidth, paddedWidth + 1, 2 * paddedWidth}
                : new int[]{-paddedWidth, -1, 1, paddedWidth};
        dist = new int[]{-paddedWidth, 1, 0, -1, paddedWidth};
        scratch = new float[EpfKernel.SCRATCH];
    }

    @Benchmark
    public float[][] row() {
        implementation.filterRow(planes[0], planes[1], planes[2], outputs[0], outputs[1], outputs[2], 0,
                3 * (WIDTH + 6) + 3, WIDTH, slopes, taps, dist, 0.4f, 1.0f, 0.6f, scratch);
        return outputs;
    }
}
