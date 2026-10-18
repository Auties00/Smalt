package com.github.auties00.smalt.image.raw.internal;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Compares the scalar and Vector API kernels of the raw pipeline on one 6000-photosite row: the
 * color matrix and the Bayer bilinear interpolation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class RawKernelBenchmark {
    private static final int WIDTH = 6000;

    @Param({"scalar", "vector"})
    public String kind;

    private ColorKernel color;
    private BayerKernel bayer;
    private float[] data;
    private float[] r;
    private float[] g;
    private float[] b;
    private final float[] matrix = {1.7f, -0.6f, -0.1f, -0.2f, 1.5f, -0.3f, 0.05f, -0.4f, 1.35f};

    @Setup
    public void setup() {
        color = kind.equals("vector") ? VectorColorKernel.create() : new ScalarColorKernel();
        bayer = kind.equals("vector") ? VectorBayerKernel.create() : new ScalarBayerKernel();
        var random = new SplittableRandom(1);
        data = new float[3 * WIDTH];
        for (var i = 0; i < data.length; i++) {
            data[i] = (float) random.nextDouble();
        }
        r = new float[WIDTH];
        g = new float[WIDTH];
        b = new float[WIDTH];
    }

    @Benchmark
    public float colorMatrix() {
        System.arraycopy(data, 0, r, 0, WIDTH);
        System.arraycopy(data, WIDTH, g, 0, WIDTH);
        System.arraycopy(data, 2 * WIDTH, b, 0, WIDTH);
        color.transform(r, g, b, WIDTH, matrix, 65535f);
        return r[17];
    }

    @Benchmark
    public float bayerRow() {
        bayer.row(data, 1, WIDTH + 1, 2 * WIDTH + 1, WIDTH - 2, BayerKernel.GREEN_RED, r, g, b, 0);
        return g[17];
    }
}
