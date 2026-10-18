package com.github.auties00.smalt.audio.opus.internal.silk;

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
 * Compares the straight port and the interleaved delayed-decision quantizers on one 20 ms wideband frame with four
 * decision states (the complexity-10 configuration).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = "--add-modules=jdk.incubator.vector")
public class DelDecQuantizerBenchmark {
    @Param({"reference", "interleaved"})
    public String impl;

    private NsqDelDecReference reference;

    private NsqDelDec interleaved;
    private EncoderState enc;
    private NsqState state;
    private SideInfoIndices indices;
    private short[] x16;
    private short[] predCoef;
    private short[] ltpCoef;
    private short[] ar;
    private int[] harm;
    private int[] tilt;
    private int[] lf;
    private int[] gains;
    private int[] pitch;
    private byte[] pulses;

    @Setup
    public void setup() {
        reference = new NsqDelDecReference();
        interleaved = new NsqDelDec();
        var random = new Random(7);
        enc = new EncoderState();
        enc.fs_kHz = 16;
        enc.nb_subfr = 4;
        enc.subfr_length = 80;
        enc.frame_length = 320;
        enc.ltp_mem_length = 320;
        enc.predictLPCOrder = 16;
        enc.shapingLPCOrder = 24;
        enc.nStatesDelayedDecision = 4;
        enc.warping_Q16 = 16 * 983;
        state = new NsqState();
        state.prev_gain_Q16 = 65536;
        state.lagPrev = 100;
        indices = new SideInfoIndices();
        indices.signalType = 2;
        indices.NLSFInterpCoef_Q2 = 4;
        x16 = new short[320];
        for (var i = 0; i < x16.length; i++) {
            x16[i] = (short) (random.nextGaussian() * 2500);
        }
        predCoef = new short[32];
        for (var i = 0; i < predCoef.length; i++) {
            predCoef[i] = (short) random.nextInt(-2500, 2500);
        }
        ltpCoef = new short[20];
        for (var i = 0; i < ltpCoef.length; i++) {
            ltpCoef[i] = (short) random.nextInt(-3000, 9000);
        }
        ar = new short[96];
        for (var i = 0; i < ar.length; i++) {
            ar[i] = (short) random.nextInt(-9000, 9000);
        }
        harm = new int[]{6000, 6000, 6000, 6000};
        tilt = new int[]{-4000, -4000, -4000, -4000};
        lf = new int[4];
        gains = new int[4];
        pitch = new int[]{120, 121, 122, 123};
        for (var i = 0; i < 4; i++) {
            lf[i] = (14000 << 16) | (-14000 & 0xFFFF);
            gains[i] = 1 << 20;
        }
        pulses = new byte[336];
    }

    @Benchmark
    public byte[] frame() {
        if (impl.equals("reference")) {
            reference.nsqDelDec(enc, state, indices, x16, 0, pulses, 0, predCoef, ltpCoef, ar, harm, tilt, lf, gains,
                    pitch, 1200, 12000);
        } else {
            interleaved.nsqDelDec(enc, state, indices, x16, 0, pulses, 0, predCoef, ltpCoef, ar, harm, tilt, lf, gains,
                    pitch, 1200, 12000);
        }
        return pulses;
    }
}
