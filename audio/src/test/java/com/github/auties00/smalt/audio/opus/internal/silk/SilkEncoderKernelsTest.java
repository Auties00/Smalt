package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the Vector API implementations of the SILK encoder kernels are bit-identical to the
 * scalar reference implementations on randomized input.
 */
class SilkEncoderKernelsTest {
    @Nested
    @DisplayName("LPC analysis filter")
    class LpcFilter {
        @ParameterizedTest(name = "order {0}, length {1}")
        @CsvSource({"6,40", "8,97", "10,160", "12,23", "16,672", "16,16", "10,13"})
        @DisplayName("vector kernel matches the scalar kernel bit for bit")
        void matches(int order, int length) {
            Skip.unlessVector(Simd.ENABLED);
            var random = new Random(order * 1000L + length);
            var s = new float[length + 5];
            var coef = new float[order + 2];
            for (var i = 0; i < s.length; i++) {
                s[i] = (float) random.nextGaussian() * 3000f;
            }
            for (var i = 0; i < coef.length; i++) {
                coef[i] = (float) random.nextGaussian() * 0.5f;
            }
            var expected = new float[length + 3];
            var actual = new float[length + 3];
            new ScalarSilkFlpKernels().lpcAnalysisFilter(expected, 1, coef, 2, s, 3, length, order);
            VectorSilkFlpKernels.create().lpcAnalysisFilter(actual, 1, coef, 2, s, 3, length, order);
            assertArrayEquals(expected, actual);
        }
    }

    @Nested
    @DisplayName("delayed-decision quantizer")
    class DelDec {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("interleaved quantizer matches the straight port of libopus over consecutive frames")
        void matches(int seed) {
            var random = new Random(seed);
            var fsKhz = new int[]{8, 12, 16}[seed % 3];
            var nbSubfr = seed % 4 == 0 ? 2 : 4;
            var enc = new EncoderState();
            enc.fs_kHz = fsKhz;
            enc.nb_subfr = nbSubfr;
            enc.subfr_length = 5 * fsKhz;
            enc.frame_length = enc.subfr_length * nbSubfr;
            enc.ltp_mem_length = 20 * fsKhz;
            enc.predictLPCOrder = fsKhz == 16 ? 16 : 10;
            enc.shapingLPCOrder = new int[]{12, 14, 16, 20, 24}[seed % 5];
            enc.nStatesDelayedDecision = 2 + seed % 3;
            enc.warping_Q16 = seed % 2 == 0 ? fsKhz * 983 : 0;
            var scalarState = new NsqState();
            var vectorState = new NsqState();
            for (var i = 0; i < scalarState.xq.length; i++) {
                scalarState.xq[i] = (short) random.nextInt(-3000, 3000);
                scalarState.sLTP_shp_Q14[i] = random.nextInt(-1 << 22, 1 << 22);
            }
            for (var i = 0; i < scalarState.sLPC_Q14.length; i++) {
                scalarState.sLPC_Q14[i] = random.nextInt(-1 << 24, 1 << 24);
            }
            for (var i = 0; i < scalarState.sAR2_Q14.length; i++) {
                scalarState.sAR2_Q14[i] = random.nextInt(-1 << 22, 1 << 22);
            }
            scalarState.prev_gain_Q16 = 65536;
            scalarState.lagPrev = 100;
            vectorState.copyFrom(scalarState);
            var scalar = new NsqDelDecReference();
            var vector = new NsqDelDec();
            for (var frame = 0; frame < 25; frame++) {
                var scalarIndices = new SideInfoIndices();
                scalarIndices.Seed = (byte) random.nextInt(4);
                scalarIndices.signalType = (byte) random.nextInt(3);
                scalarIndices.quantOffsetType = (byte) random.nextInt(2);
                scalarIndices.NLSFInterpCoef_Q2 = (byte) (random.nextBoolean() ? 4 : random.nextInt(4));
                var vectorIndices = new SideInfoIndices();
                vectorIndices.copyFrom(scalarIndices);
                var x16 = new short[enc.frame_length];
                for (var i = 0; i < x16.length; i++) {
                    x16[i] = (short) (random.nextGaussian() * (frame % 5 == 0 ? 12000 : 2500));
                }
                var predCoef = new short[32];
                for (var i = 0; i < predCoef.length; i++) {
                    predCoef[i] = (short) random.nextInt(-2500, 2500);
                }
                var ltpCoef = new short[20];
                for (var i = 0; i < ltpCoef.length; i++) {
                    ltpCoef[i] = (short) random.nextInt(-3000, 9000);
                }
                var ar = new short[4 * 24];
                for (var i = 0; i < ar.length; i++) {
                    ar[i] = (short) random.nextInt(-9000, 9000);
                }
                int[] harm = new int[4], tilt = new int[4], lf = new int[4], gains = new int[4], pitch = new int[4];
                for (var i = 0; i < 4; i++) {
                    harm[i] = random.nextInt(0, 12000);
                    tilt[i] = random.nextInt(-8000, 0);
                    lf[i] = (random.nextInt(12000, 16000) << 16) | (random.nextInt(-16000, -12000) & 0xFFFF);
                    gains[i] = random.nextInt(1 << 12, 1 << 24);
                    pitch[i] = random.nextInt(2 * fsKhz + 3, 18 * fsKhz);
                }
                var lambda = random.nextInt(400, 3500);
                var scalarPulses = new byte[enc.frame_length + 16];
                var vectorPulses = new byte[enc.frame_length + 16];
                scalar.nsqDelDec(enc, scalarState, scalarIndices, x16, 0, scalarPulses, 0, predCoef, ltpCoef, ar, harm,
                        tilt, lf, gains, pitch, lambda, 12000);
                vector.nsqDelDec(enc, vectorState, vectorIndices, x16, 0, vectorPulses, 0, predCoef, ltpCoef, ar, harm,
                        tilt, lf, gains, pitch, lambda, 12000);
                assertArrayEquals(scalarPulses, vectorPulses, "pulses of frame " + frame);
                assertEquals(scalarIndices.Seed, vectorIndices.Seed, "seed of frame " + frame);
                assertArrayEquals(scalarState.xq, vectorState.xq, "xq of frame " + frame);
                assertArrayEquals(scalarState.sLTP_shp_Q14, vectorState.sLTP_shp_Q14, "sLTP_shp of frame " + frame);
                assertArrayEquals(java.util.Arrays.copyOf(scalarState.sLPC_Q14, 16),
                        java.util.Arrays.copyOf(vectorState.sLPC_Q14, 16), "sLPC of frame " + frame);
                assertArrayEquals(scalarState.sAR2_Q14, vectorState.sAR2_Q14, "sAR2 of frame " + frame);
                assertEquals(scalarState.sLF_AR_shp_Q14, vectorState.sLF_AR_shp_Q14);
                assertEquals(scalarState.sDiff_shp_Q14, vectorState.sDiff_shp_Q14);
                assertEquals(scalarState.prev_gain_Q16, vectorState.prev_gain_Q16);
                assertEquals(scalarState.lagPrev, vectorState.lagPrev);
            }
        }
    }

    @Nested
    @DisplayName("warped autocorrelation")
    class Warped {
        @ParameterizedTest(name = "order {0}, length {1}")
        @CsvSource({"16,160", "20,240", "24,240", "12,120", "14,200"})
        @DisplayName("batched kernels match four separate reference computations bit for bit")
        void matches(int order, int length) {
            var random = new Random(order * 7919L + length);
            var stride = 250;
            var input = new float[4 * stride];
            for (var i = 0; i < input.length; i++) {
                input[i] = (float) random.nextGaussian() * 4000f;
            }
            var warping = 0.25f + random.nextFloat() * 0.1f;
            var expected = new float[4 * 25];
            var single = new float[25];
            // The work arrays are shared and start dirty: the kernel must clear them on every call.
            var state = new double[25];
            var corr = new double[25];
            java.util.Arrays.fill(state, 1e9);
            java.util.Arrays.fill(corr, -1e9);
            for (var c = 0; c < 4; c++) {
                SigProcFlp.warpedAutocorrelationFlp(single, input, c * stride, warping, length, order, state, corr);
                System.arraycopy(single, 0, expected, c * 25, order + 1);
            }
            var scalar = new float[4 * 25];
            var work = new double[SilkFlpKernels.warpedScratchLength(length)];
            new ScalarSilkFlpKernels().warpedAutocorrelation4(scalar, 25, input, stride, warping, length, order, work);
            assertArrayEquals(expected, scalar);
            Skip.unlessVector(Simd.ENABLED);
            var vector = new float[4 * 25];
            VectorSilkFlpKernels.create().warpedAutocorrelation4(vector, 25, input, stride, warping, length, order, work);
            assertArrayEquals(expected, vector);
        }
    }
}
