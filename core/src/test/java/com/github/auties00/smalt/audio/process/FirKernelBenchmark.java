package com.github.auties00.smalt.audio.process;

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
 * Measures the FIR kernel alone: 4096 outputs of a 44.1 kHz to 48 kHz (32 taps) or 44.1 kHz to
 * 16 kHz (96 taps) phase run, for the vector and the scalar implementation.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class FirKernelBenchmark {
    @Param({"48000", "16000"})
    public int outputRate;

    private float[] signal;
    private float[] table;
    private float[] out;
    private int taps;
    private int up;
    private int down;
    private FirKernel scalar;
    private FirKernel vector;

    @Setup
    public void setup() {
        var random = new SplittableRandom(2);
        up = outputRate == 48000 ? 160 : 160;
        down = outputRate == 48000 ? 147 : 441;
        taps = outputRate == 48000 ? 32 : 96;
        signal = new float[4096 * down / up + taps + 64];
        table = new float[up * taps];
        for (var i = 0; i < signal.length; i++) {
            signal[i] = (float) random.nextDouble(-1, 1);
        }
        for (var i = 0; i < table.length; i++) {
            table[i] = (float) random.nextDouble(-1, 1);
        }
        out = new float[4096];
        scalar = new ScalarFirKernel();
        vector = VectorProcessKernels.fir();
    }

    @Benchmark
    public float[] vector() {
        vector.run(signal, 0, 0, down / up, down % up, up, table, taps, out, 0, 4096);
        return out;
    }

    @Benchmark
    public float[] scalar() {
        scalar.run(signal, 0, 0, down / up, down % up, up, table, taps, out, 0, 4096);
        return out;
    }
}
