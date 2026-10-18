package com.github.auties00.smalt.image.ops;

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

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Compares the scalar and Vector API implementations of {@link EffectKernel} on rows of a
 * 4032-pixel RGBA picture (16128 samples), one kernel method per benchmark. The blends restore
 * their planes from a pristine copy first (a copy of 32 KB, the same for both implementations),
 * since blending in place drifts the values towards subnormal floats.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx1g"})
public class EffectKernelBenchmark {
    private static final int LENGTH = 16128;

    @Param({"scalar", "vector"})
    public String kernel;

    private EffectKernel effects;
    private byte[] rows;
    private byte[] out;
    private byte[] above;
    private byte[] middle;
    private byte[] below;
    private int[] sums;
    private float[][] planes;
    private float[][] pristine;
    private final int[] sepia = {25_756, 50_397, 12_386, 32_768, 22_872, 44_958, 11_010, 32_768, 17_826, 34_996, 8_585, 32_768};

    @Setup(Level.Trial)
    public void setup() {
        effects = kernel.equals("scalar") ? new ScalarEffectKernel() : VectorEffectKernel.create();
        var random = new SplittableRandom(3);
        rows = new byte[5 * LENGTH];
        random.nextBytes(rows);
        out = new byte[LENGTH];
        above = java.util.Arrays.copyOfRange(rows, 0, LENGTH);
        middle = java.util.Arrays.copyOfRange(rows, LENGTH, 2 * LENGTH);
        below = java.util.Arrays.copyOfRange(rows, 2 * LENGTH, 3 * LENGTH);
        sums = new int[LENGTH];
        planes = new float[8][LENGTH / 4];
        pristine = new float[8][LENGTH / 4];
        for (var plane : pristine) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = random.nextInt(256) / 255f;
            }
        }
    }

    @Benchmark
    public byte[] boxRow() {
        effects.boxRow(rows, 0, LENGTH, 2 * LENGTH, 3 * LENGTH, sums, 2_396_745, 0, out, 0, LENGTH);
        return out;
    }

    @Benchmark
    public byte[] mixRow() {
        effects.mixRow(rows, 0, LENGTH / 4, sepia, out, 0);
        return out;
    }

    @Benchmark
    public byte[] unsharpRow() {
        effects.unsharpRow(rows, 0, rows, LENGTH, LENGTH, 150, 3, out, 0);
        return out;
    }

    @Benchmark
    public byte[] median9Row() {
        effects.median9Row(above, middle, below, 4, LENGTH - 8, out, 0);
        return out;
    }

    private float[][] restore() {
        for (var i = 0; i < 8; i++) {
            System.arraycopy(pristine[i], 0, planes[i], 0, LENGTH / 4);
        }
        return planes;
    }

    @Benchmark
    public float[] blendSoftLight() {
        var p = restore();
        effects.blend(BlendMode.SOFT_LIGHT, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], 0.8f, LENGTH / 4);
        return p[0];
    }

    @Benchmark
    public float[] blendNormal() {
        var p = restore();
        effects.blend(BlendMode.NORMAL, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], 0.8f, LENGTH / 4);
        return p[0];
    }
}
