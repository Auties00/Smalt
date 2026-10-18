package com.github.auties00.smalt.video.h264.encoder.internal;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Scalar versus Vector API kernels of the encoder: the metrics of motion estimation and mode
 * decision, the fused luma transform and quantization, plane intra prediction and the half-sample
 * interpolation of a whole 720p reference.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
@State(Scope.Thread)
public class H264KernelBenchmark {
    @Param({"scalar", "vector"})
    public String implementation;

    private PixelKernels pixels;
    private TransformKernels transform;
    private InterpolationKernels interpolation;
    private final byte[] a = new byte[64 * 64];
    private final byte[] b = new byte[64 * 64];
    private final byte[] dst = new byte[64 * 64];
    private final int[] levels = new int[256];
    private final int[] counts = new int[16];
    private final short[] scratch = new short[PixelKernels.SATD_SCRATCH];
    private Frame frame;
    private short[] temp;
    private int position;

    @Setup
    public void setup() {
        var vector = implementation.equals("vector");
        pixels = vector ? VectorKernelsHolder.pixels() : new PixelKernels();
        transform = vector ? VectorKernelsHolder.transform() : new TransformKernels();
        interpolation = vector ? VectorKernelsHolder.interpolation() : new InterpolationKernels();
        var random = new Random(1);
        random.nextBytes(a);
        for (var i = 0; i < b.length; i++) {
            b[i] = (byte) ((a[i] & 0xFF) + random.nextInt(21) - 10);
        }
        frame = new Frame(1280, 720);
        random.nextBytes(frame.y);
        temp = new short[frame.lumaStride + 16];
    }

    /** Returns a varying block offset so that the JIT cannot fold the inputs. */
    private int offset() {
        position = (position + 5) & 15;
        return position;
    }

    @Benchmark
    public int sad16x16() {
        return pixels.sad(a, offset(), 64, b, 3, 64, 16, 16);
    }

    @Benchmark
    public int sad8x8() {
        return pixels.sad(a, offset(), 64, b, 3, 64, 8, 8);
    }

    @Benchmark
    public int satd16x16() {
        return pixels.satd(a, offset(), 64, b, 3, 64, 16, 16);
    }

    @Benchmark
    public int satd8x8() {
        return pixels.satd(a, offset(), 64, b, 3, 64, 8, 8);
    }

    @Benchmark
    public long satdQuadrants16x16() {
        return pixels.satdQuadrants(a, offset(), 64, b, 3, 64, scratch);
    }

    @Benchmark
    public long satdAverageQuadrants16x16() {
        return pixels.satdAverageQuadrants(a, offset(), 64, b, 3, a, 131, 64, scratch);
    }

    @Benchmark
    public int satdAverage16x16() {
        return pixels.satdAverage16(a, offset(), 64, b, 3, a, 131, 64, 16, scratch);
    }

    @Benchmark
    public int averageThenSatd16x16() {
        pixels.average(b, 3, 64, a, 131, 64, dst, 0, 16, 16, 16);
        return pixels.satd(a, offset(), 64, dst, 0, 16, 16, 16);
    }

    @Benchmark
    public long averageThenSatdQuadrants16x16() {
        pixels.average(b, 3, 64, a, 131, 64, dst, 0, 16, 16, 16);
        return pixels.satdQuadrants(a, offset(), 64, dst, 0, 16, scratch);
    }

    @Benchmark
    public long ssd16x16() {
        return pixels.ssd(a, offset(), 64, b, 3, 64, 16, 16);
    }

    @Benchmark
    public byte[] average16x16() {
        pixels.average(a, offset(), 64, b, 3, 64, dst, 0, 16, 16, 16);
        return dst;
    }

    @Benchmark
    public int[] forwardQuantizeLuma16x16() {
        transform.forwardQuantizeLuma(a, offset(), 64, b, 3, 64, 26, 1 << 16, 2063, levels, counts);
        return counts;
    }

    @Benchmark
    public int[] lumaMasks16x16() {
        transform.quantizeLumaMasks(a, offset(), 64, b, 3, 64, 26, levels, counts);
        return levels;
    }

    @Benchmark
    public byte[] planePrediction16x16() {
        pixels.planePrediction(dst, offset(), 64, 16, 4000, 37, -21, 7);
        return dst;
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Frame interpolate720p() {
        frame.interpolate(interpolation, temp);
        return frame;
    }
}
