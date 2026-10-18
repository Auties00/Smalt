package com.github.auties00.smalt.capture.internal.convert;

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

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures the per-frame cost of the capture conversions with each kernel: a 1920x1080 BGRX screen
 * frame, a 1280x720 YUY2 camera frame and the chroma of a 1280x720 NV12 frame, to I420.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class ConversionBenchmark {
    private static final int SCREEN_WIDTH = 1920;
    private static final int SCREEN_HEIGHT = 1080;
    private static final int CAMERA_WIDTH = 1280;
    private static final int CAMERA_HEIGHT = 720;

    @Param({"scalar", "vector"})
    public String kernel;

    private ConversionKernel instance;
    private byte[] bgrx;
    private byte[] packed;
    private byte[] y;
    private byte[] u;
    private byte[] v;

    @Setup(Level.Trial)
    public void setup() {
        instance = kernel.equals("scalar") ? new ScalarConversionKernel() : VectorConversionKernel.create();
        var random = new SplittableRandom(1);
        bgrx = new byte[SCREEN_WIDTH * SCREEN_HEIGHT * 4];
        random.nextBytes(bgrx);
        packed = new byte[CAMERA_WIDTH * CAMERA_HEIGHT * 2];
        random.nextBytes(packed);
        y = new byte[SCREEN_WIDTH * SCREEN_HEIGHT];
        u = new byte[SCREEN_WIDTH * SCREEN_HEIGHT / 4];
        v = new byte[SCREEN_WIDTH * SCREEN_HEIGHT / 4];
    }

    @Benchmark
    public byte[] bgrx1080p() {
        var c = YuvCoefficients.BT709_LIMITED;
        for (var row = 0; row < SCREEN_HEIGHT; row += 2) {
            var upper = row * 4 * SCREEN_WIDTH;
            var lower = upper + 4 * SCREEN_WIDTH;
            instance.rgbToLuma(bgrx, upper, SCREEN_WIDTH, RgbLayout.BGRX, c, y, row * SCREEN_WIDTH);
            instance.rgbToLuma(bgrx, lower, SCREEN_WIDTH, RgbLayout.BGRX, c, y, (row + 1) * SCREEN_WIDTH);
            instance.rgbToChroma(bgrx, upper, lower, SCREEN_WIDTH / 2, RgbLayout.BGRX, c, u, v, row / 2 * (SCREEN_WIDTH / 2));
        }
        return y;
    }

    @Benchmark
    public byte[] yuy2720p() {
        for (var row = 0; row < CAMERA_HEIGHT; row += 2) {
            var upper = row * 2 * CAMERA_WIDTH;
            var lower = upper + 2 * CAMERA_WIDTH;
            instance.packedLuma(packed, upper, CAMERA_WIDTH, 0, y, row * CAMERA_WIDTH);
            instance.packedLuma(packed, lower, CAMERA_WIDTH, 0, y, (row + 1) * CAMERA_WIDTH);
            instance.packedChroma(packed, upper, lower, CAMERA_WIDTH / 2, 1, 3, u, v, row / 2 * (CAMERA_WIDTH / 2));
        }
        return y;
    }

    @Benchmark
    public byte[] nv12Chroma720p() {
        for (var row = 0; row < CAMERA_HEIGHT / 2; row++) {
            instance.deinterleave(packed, row * CAMERA_WIDTH, CAMERA_WIDTH / 2, u, v, row * (CAMERA_WIDTH / 2));
        }
        return u;
    }
}
