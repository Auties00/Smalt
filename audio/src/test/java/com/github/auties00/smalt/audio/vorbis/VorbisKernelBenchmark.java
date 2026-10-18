package com.github.auties00.smalt.audio.vorbis;

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
 * Measures the inverse MDCT and inverse coupling kernels, scalar against vector, per block.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class VorbisKernelBenchmark {
    @Param({"256", "2048"})
    public int size;

    @Param({"vector", "scalar"})
    public String kernel;

    private VorbisKernel selected;
    private Imdct plan;
    private float[] spectrum;
    private float[] source;
    private float[] output;
    private float[] re;
    private float[] im;
    private float[] magnitude;
    private float[] angle;
    private float[] magnitudeSource;
    private float[] angleSource;

    @Setup(Level.Trial)
    public void setup() {
        selected = "scalar".equals(kernel) ? new ScalarVorbisKernel() : VectorVorbisKernel.create();
        plan = new Imdct(size);
        var random = new Random(1);
        source = new float[size / 2];
        for (var i = 0; i < source.length; i++) {
            source[i] = random.nextFloat() * 2 - 1;
        }
        spectrum = source.clone();
        output = new float[size];
        re = new float[size / 4];
        im = new float[size / 4];
        magnitude = source.clone();
        angle = source.clone();
        for (var i = 0; i < angle.length; i++) {
            angle[i] = random.nextFloat() * 2 - 1;
        }
        magnitudeSource = magnitude.clone();
        angleSource = angle.clone();
    }

    @Benchmark
    public float[] imdct() {
        System.arraycopy(source, 0, spectrum, 0, source.length);
        selected.imdct(plan, spectrum, output, re, im);
        return output;
    }

    @Benchmark
    public float[] coupling() {
        System.arraycopy(magnitudeSource, 0, magnitude, 0, magnitude.length);
        System.arraycopy(angleSource, 0, angle, 0, angle.length);
        selected.inverseCoupling(magnitude, angle, magnitude.length);
        return angle;
    }
}
