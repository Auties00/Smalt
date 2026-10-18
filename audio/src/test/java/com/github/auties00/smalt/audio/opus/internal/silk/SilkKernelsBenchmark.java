package com.github.auties00.smalt.audio.opus.internal.silk;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Compares the scalar and Vector API SILK resampler kernels on one 10 ms batch: 16 to 48 kHz
 * interpolation for the decoder and 48 to 16 kHz decimation for the encoder.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SilkKernelsBenchmark {
    @Param({"scalar", "vector"})
    public String impl;

    private SilkKernels kernels;
    private final short[] upBuf = new short[2 * 160 + 8];
    private final int[] downBuf = new int[480 + 36];
    private final short[] out = new short[960];
    private final int[] scratch = new int[SilkKernels.SCRATCH_SIZE];

    @Setup
    public void setup() {
        kernels = impl.equals("scalar") ? new ScalarSilkKernels() : VectorSilkKernels.createForTest();
        var random = new Random(1);
        for (var i = 0; i < upBuf.length; i++) {
            upBuf[i] = (short) random.nextInt(65536);
        }
        for (var i = 0; i < downBuf.length; i++) {
            downBuf[i] = random.nextInt(1 << 24) - (1 << 23);
        }
    }

    @Benchmark
    public int upsample16to48() {
        return kernels.iirFirInterpol(out, 0, upBuf, 160 << 17, 43691, scratch);
    }

    @Benchmark
    public int downsample48to16() {
        return kernels.downFirInterpol(out, 0, downBuf, ResamplerRom.silk_Resampler_1_3_COEFS, 2,
                ResamplerRom.RESAMPLER_DOWN_ORDER_FIR2, 1, 480 << 16, 3 << 16, scratch);
    }
}
