package com.github.auties00.smalt.image.png;

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

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Measures each PNG filter kernel, scalar against vector, on one 4000-pixel RGBA scanline
 * (16000 bytes). Scores are per scanline.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class PngFilterBenchmark {
    @Param({"scalar", "vector"})
    public String implementation;

    @Param({"4", "3"})
    public int distance;

    private PngFilterKernel kernel;
    private byte[] row;
    private byte[] prior;
    private byte[] out;
    private byte[] recon;
    private byte[] recon2;
    private byte[] pairRaw;

    @Setup(Level.Trial)
    public void setup() {
        kernel = implementation.equals("scalar") ? new ScalarPngFilterKernel() : new VectorPngFilterKernel();
        var random = new Random(1);
        row = new byte[16000];
        prior = new byte[16000];
        out = new byte[16001];
        recon = new byte[16000];
        recon2 = new byte[16000];
        pairRaw = new byte[32002];
        random.nextBytes(pairRaw);
        random.nextBytes(row);
        random.nextBytes(prior);
    }

    @Benchmark
    public byte[] unfilterSub() {
        kernel.unfilterSub(row, 0, recon, 0, row.length, distance);
        return recon;
    }

    @Benchmark
    public byte[] unfilterUp() {
        kernel.unfilterUp(row, 0, recon, 0, prior, 0, row.length);
        return recon;
    }

    @Benchmark
    public byte[] unfilterAverage() {
        kernel.unfilterAverage(row, 0, recon, 0, prior, 0, row.length, distance);
        return recon;
    }

    @Benchmark
    public byte[] unfilterPaeth() {
        kernel.unfilterPaeth(row, 0, recon, 0, prior, 0, row.length, distance);
        return recon;
    }

    @Benchmark
    public byte[] unfilterPaethPair() {
        kernel.unfilterPaethPair(pairRaw, 1, 16002, recon, 0, recon2, 0, prior, 0, 16000, distance);
        return recon2;
    }

    @Benchmark
    public byte[] unfilterAveragePair() {
        kernel.unfilterAveragePair(pairRaw, 1, 16002, recon, 0, recon2, 0, prior, 0, 16000, distance);
        return recon2;
    }

    @Benchmark
    public int filterPaeth() {
        return kernel.filterPaeth(row, 0, prior, 0, row.length, distance, out, 1);
    }

    @Benchmark
    public int filterSub() {
        return kernel.filterSub(row, 0, row.length, distance, out, 1);
    }

    @Benchmark
    public int filterAverage() {
        return kernel.filterAverage(row, 0, prior, 0, row.length, distance, out, 1);
    }

    @Benchmark
    public int filterUp() {
        return kernel.filterUp(row, 0, prior, 0, row.length, out, 1);
    }

    @Benchmark
    public int filterNone() {
        return kernel.filterNone(row, 0, row.length, out, 1);
    }
}
