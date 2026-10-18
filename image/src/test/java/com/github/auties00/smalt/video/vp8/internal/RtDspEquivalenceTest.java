package com.github.auties00.smalt.video.vp8.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the Vector API motion-search kernels produce exactly the results of the scalar
 * libvpx ports on randomized blocks, including saturated and flat ones, at every sub-pixel offset
 * and at unaligned offsets and strides.
 */
@DisplayName("real-time VP8 motion-search kernels: vector equals scalar")
class RtDspEquivalenceTest {
    static final int TRIALS = 20_000;
    static final RtDsp SCALAR = new ScalarRtDsp();
    static final RtDsp VECTOR = VectorRtDsp.create();

    static byte[] pixels(Random random, int size) {
        var buffer = new byte[size];
        switch (random.nextInt(4)) {
            case 0 -> random.nextBytes(buffer);
            case 1 -> {
                for (var i = 0; i < size; i++) {
                    buffer[i] = (byte) (random.nextBoolean() ? 0 : 255);
                }
            }
            case 2 -> {
                var base = random.nextInt(256);
                for (var i = 0; i < size; i++) {
                    buffer[i] = (byte) Math.clamp(base + random.nextInt(9) - 4, 0, 255);
                }
            }
            default -> Arrays.fill(buffer, (byte) random.nextInt(256));
        }
        return buffer;
    }

    @Test
    @DisplayName("SAD, variance and 4x4 SSE")
    void fullPixel() {
        var random = new Random(1);
        for (var trial = 0; trial < TRIALS; trial++) {
            var aStride = 16 + random.nextInt(48);
            var bStride = 16 + random.nextInt(48);
            var a = pixels(random, aStride * 17 + 64);
            var b = pixels(random, bStride * 17 + 64);
            var aOff = random.nextInt(32);
            var bOff = random.nextInt(32);
            assertEquals(SCALAR.sad16(a, aOff, aStride, b, bOff, bStride), VECTOR.sad16(a, aOff, aStride, b, bOff, bStride));
            assertEquals(SCALAR.variance16(a, aOff, aStride, b, bOff, bStride),
                    VECTOR.variance16(a, aOff, aStride, b, bOff, bStride));
            assertEquals(SCALAR.sse4x4(a, aOff, aStride, b, bOff, bStride), VECTOR.sse4x4(a, aOff, aStride, b, bOff, bStride));
            var scalarGroups = new byte[3 * RtDsp.GROUP];
            var vectorGroups = new byte[3 * RtDsp.GROUP];
            SCALAR.groupMacroblock(a, aOff, aStride, b, a, bOff, bStride / 2, scalarGroups);
            VECTOR.groupMacroblock(a, aOff, aStride, b, a, bOff, bStride / 2, vectorGroups);
            assertArrayEquals(scalarGroups, vectorGroups, "groups");
            var scalarColumns = new int[16];
            var vectorColumns = new int[16];
            assertEquals(SCALAR.blockStats16(a, aOff, aStride, scalarColumns), VECTOR.blockStats16(a, aOff, aStride, vectorColumns));
            assertArrayEquals(scalarColumns, vectorColumns);
            var edges = pixels(random, 48);
            var scalarErrors = new int[4];
            var vectorErrors = new int[4];
            var dcU = random.nextInt(256);
            var dcV = random.nextInt(256);
            SCALAR.chromaIntraErrors(a, aOff, aStride, edges, dcU, dcV, scalarErrors);
            VECTOR.chromaIntraErrors(a, aOff, aStride, edges, dcU, dcV, vectorErrors);
            assertArrayEquals(scalarErrors, vectorErrors, "chroma intra errors");
            var offs = new int[4];
            for (var i = 0; i < 4; i++) {
                offs[i] = random.nextInt(32);
            }
            var scalar = new int[6];
            var vector = new int[6];
            SCALAR.sad16x3(a, aOff, aStride, b, offs[0], offs[1], offs[2], bStride, scalar, 1);
            VECTOR.sad16x3(a, aOff, aStride, b, offs[0], offs[1], offs[2], bStride, vector, 1);
            assertArrayEquals(scalar, vector);
            assertEquals(SCALAR.sad16(a, aOff, aStride, b, offs[2], bStride), scalar[3]);
            SCALAR.sad16x4(a, aOff, aStride, b, offs[0], offs[1], offs[2], offs[3], bStride, scalar, 2);
            VECTOR.sad16x4(a, aOff, aStride, b, offs[0], offs[1], offs[2], offs[3], bStride, vector, 2);
            assertArrayEquals(scalar, vector);
            assertEquals(SCALAR.sad16(a, aOff, aStride, b, offs[3], bStride), scalar[5]);
        }
    }

    @Test
    @DisplayName("bilinear sub-pixel variance at every offset")
    void subPixel() {
        var random = new Random(2);
        var scalarScratch = new short[RtDsp.SCRATCH_SIZE];
        var vectorScratch = new short[RtDsp.SCRATCH_SIZE];
        for (var trial = 0; trial < TRIALS; trial++) {
            var refStride = 17 + random.nextInt(48);
            var bStride = 16 + random.nextInt(48);
            var ref = pixels(random, refStride * 18 + 64);
            var b = pixels(random, bStride * 17 + 64);
            var refOff = random.nextInt(32);
            var bOff = random.nextInt(32);
            var x = trial & 7;
            var y = (trial >> 3) & 7;
            var expected = SCALAR.subpelVariance16(ref, refOff, refStride, x, y, b, bOff, bStride, scalarScratch);
            assertEquals(expected, VECTOR.subpelVariance16(ref, refOff, refStride, x, y, b, bOff, bStride, vectorScratch),
                    "16x16 at " + x + "," + y);
            if (x != 0 && y != 0) {
                SCALAR.horizontalPass16(ref, refOff, refStride, x, scalarScratch);
                VECTOR.horizontalPass16(ref, refOff, refStride, x, vectorScratch);
                assertArrayEquals(scalarScratch, vectorScratch, "horizontal pass at " + x);
                assertEquals(expected, SCALAR.verticalPassVariance16(scalarScratch, y, b, bOff, bStride));
                assertEquals(expected, VECTOR.verticalPassVariance16(vectorScratch, y, b, bOff, bStride));
            }
        }
    }

    @Test
    @DisplayName("fused half-pixel pairs equal two sub-pixel variances")
    void halfpelPairs() {
        var random = new Random(4);
        var scratch = new short[RtDsp.SCRATCH_SIZE];
        var scalar = new long[2];
        var vector = new long[2];
        for (var trial = 0; trial < TRIALS; trial++) {
            var refStride = 18 + random.nextInt(48);
            var bStride = 16 + random.nextInt(48);
            var ref = pixels(random, refStride * 19 + 64);
            var b = pixels(random, bStride * 17 + 64);
            var refOff = refStride + 1 + random.nextInt(32);
            var bOff = random.nextInt(32);
            SCALAR.halfpelHorizontalPair(ref, refOff, refStride, b, bOff, bStride, scalar);
            VECTOR.halfpelHorizontalPair(ref, refOff, refStride, b, bOff, bStride, vector);
            assertArrayEquals(scalar, vector, "horizontal");
            assertEquals(SCALAR.subpelVariance16(ref, refOff - 1, refStride, 4, 0, b, bOff, bStride, scratch), scalar[0]);
            assertEquals(SCALAR.subpelVariance16(ref, refOff, refStride, 4, 0, b, bOff, bStride, scratch), scalar[1]);
            SCALAR.halfpelVerticalPair(ref, refOff, refStride, b, bOff, bStride, scalar);
            VECTOR.halfpelVerticalPair(ref, refOff, refStride, b, bOff, bStride, vector);
            assertArrayEquals(scalar, vector, "vertical");
            assertEquals(SCALAR.subpelVariance16(ref, refOff - refStride, refStride, 0, 4, b, bOff, bStride, scratch),
                    scalar[0]);
            assertEquals(SCALAR.subpelVariance16(ref, refOff, refStride, 0, 4, b, bOff, bStride, scratch), scalar[1]);
        }
    }

    /** Fills a matrix the way the real-time encoder does, for a quantizer index and a step scale. */
    static QuantMatrix matrix(int dc, int ac) {
        var m = new QuantMatrix();
        for (var i = 0; i < 16; i++) {
            var q = i == 0 ? dc : ac;
            m.q[i] = q;
            m.iq[i] = (1 << QuantMatrix.QFIX) / q;
            m.bias[i] = 48 << (QuantMatrix.QFIX - 7);
            m.zthresh[i] = ((1 << QuantMatrix.QFIX) - 1 - m.bias[i]) / m.iq[i];
        }
        return m;
    }

    /** Returns the largest DC and AC magnitudes of a group whose coefficients start at 8, packed like forwardDct8's result. */
    static int maxima(int[] coef) {
        var dc = 0;
        var ac = 0;
        for (var j = 0; j < 8; j++) {
            dc = Math.max(dc, Math.abs(coef[8 + j]));
            for (var p = 1; p < 16; p++) {
                ac = Math.max(ac, Math.abs(coef[8 + 8 * p + j]));
            }
        }
        return (dc << 16) | ac;
    }

    /** Copies block {@code j} of an interleaved group into a 32-byte-stride work buffer. */
    static void block(byte[] group, int j, byte[] out) {
        for (var r = 0; r < 4; r++) {
            System.arraycopy(group, 32 * r + 4 * j, out, r * Vp8EncDsp.BPS, 4);
        }
    }

    @Test
    @DisplayName("grouped forward DCT, quantizer and inverse DCT match the per-block kernels")
    void groups() {
        var random = new Random(3);
        var enc = new ScalarVp8EncDsp();
        var dec = new ScalarVp8Dsp();
        var qIndex = new int[]{0, 1, 4, 10, 30, 60, 90, 127};
        for (var trial = 0; trial < 4000; trial++) {
            var src = pixels(random, RtDsp.GROUP);
            var pred = pixels(random, RtDsp.GROUP);
            var q = qIndex[trial & 7];
            var m = switch ((trial >> 3) % 3) {
                case 0 -> matrix(Vp8Tables.DC_QLOOKUP[q], Vp8Tables.AC_QLOOKUP[q]);
                case 1 -> matrix(Vp8Tables.DC_QLOOKUP[q] * 2, Math.max((Vp8Tables.AC_QLOOKUP[q] * 101581) >> 16, 8));
                default -> matrix(Math.min(Vp8Tables.DC_QLOOKUP[q], 132), Vp8Tables.AC_QLOOKUP[q]);
            };
            var first = random.nextInt(2);
            var scalarCoef = new int[RtDsp.GROUP + 8];
            var vectorCoef = new int[RtDsp.GROUP + 8];
            var scalarMaxima = SCALAR.forwardDct8(src, 0, pred, 0, scalarCoef, 8);
            assertEquals(scalarMaxima, VECTOR.forwardDct8(src, 0, pred, 0, vectorCoef, 8), "maxima");
            assertArrayEquals(scalarCoef, vectorCoef, "forward");
            assertEquals(maxima(scalarCoef), scalarMaxima, "maxima of the coefficients");
            var srcBlock = new byte[4 * Vp8EncDsp.BPS];
            var predBlock = new byte[4 * Vp8EncDsp.BPS];
            var reference = new short[16];
            for (var j = 0; j < 8; j++) {
                block(src, j, srcBlock);
                block(pred, j, predBlock);
                enc.fTransform(srcBlock, 0, predBlock, 0, reference, 0);
                for (var p = 0; p < 16; p++) {
                    assertEquals(reference[p], scalarCoef[8 + 8 * p + j], "forward block " + j + " position " + p);
                }
            }
            if (random.nextInt(4) == 0) {
                for (var i = 8; i < scalarCoef.length; i++) {
                    scalarCoef[i] = vectorCoef[i] = random.nextInt(65536) - 32768;
                }
            }
            if (random.nextInt(3) == 0) {
                var scale = 1 + random.nextInt(64);
                for (var i = 8; i < scalarCoef.length; i++) {
                    scalarCoef[i] = vectorCoef[i] = scalarCoef[i] / scale;
                }
            }
            var groupMaxima = maxima(scalarCoef);
            var zero = (groupMaxima & 0xFFFF) <= m.zthresh[1] && (first > 0 || (groupMaxima >>> 16) <= m.zthresh[0]);
            var original = scalarCoef.clone();
            var scalarLevels = new short[16 * RtDsp.LEVEL_STRIDE + 8];
            var vectorLevels = new short[16 * RtDsp.LEVEL_STRIDE + 8];
            var scalarEob = new int[16];
            var vectorEob = new int[16];
            SCALAR.quantize8(scalarCoef, 8, first, m, scalarLevels, 8, scalarEob, 4);
            VECTOR.quantize8(vectorCoef, 8, first, m, vectorLevels, 8, vectorEob, 4);
            assertArrayEquals(scalarCoef, vectorCoef, "dequantized");
            assertArrayEquals(scalarLevels, vectorLevels, "levels");
            assertArrayEquals(scalarEob, vectorEob, "eob");
            var allZero = true;
            for (var j = 0; j < 8; j++) {
                allZero &= scalarEob[4 + j] == 0;
            }
            assertEquals(allZero, zero, "zero test against the quantizer");
            var in = new short[16];
            var levels = new short[16];
            for (var j = 0; j < 8; j++) {
                for (var p = 0; p < 16; p++) {
                    in[p] = (short) original[8 + 8 * p + j];
                }
                if (first == 1) {
                    in[0] = 0;
                }
                var nonzero = enc.quantizeBlock(in, 0, levels, 0, m);
                var last = 0;
                for (var n = 0; n < 16; n++) {
                    assertEquals(levels[n], scalarLevels[8 + n * RtDsp.LEVEL_STRIDE + j], "level " + n + " of " + j);
                    if (levels[n] != 0) {
                        last = n + 1;
                    }
                }
                assertEquals(last, scalarEob[4 + j]);
                assertEquals(nonzero != 0, last != 0);
                for (var p = 0; p < 16; p++) {
                    assertEquals(in[p], scalarCoef[8 + 8 * p + j], "dequantized " + p + " of " + j);
                }
            }
            if (random.nextInt(3) == 0) {
                for (var i = 8; i < scalarCoef.length; i++) {
                    scalarCoef[i] = vectorCoef[i] = (short) random.nextInt(65536);
                }
            }
            var dequantized = scalarCoef.clone();
            var scalarOut = new byte[RtDsp.GROUP];
            var vectorOut = new byte[RtDsp.GROUP];
            SCALAR.inverseDctAdd8(scalarCoef, 8, pred, 0, scalarOut, 0);
            VECTOR.inverseDctAdd8(vectorCoef, 8, pred, 0, vectorOut, 0);
            assertArrayEquals(scalarOut, vectorOut, "inverse");
            var recon = new byte[4 * 64];
            for (var j = 0; j < 8; j++) {
                for (var r = 0; r < 4; r++) {
                    System.arraycopy(pred, 32 * r + 4 * j, recon, r * 64, 4);
                }
                var c = new short[16];
                for (var p = 0; p < 16; p++) {
                    c[p] = (short) dequantized[8 + 8 * p + j];
                }
                dec.idctAdd(c, 0, recon, 0, 64);
                for (var r = 0; r < 4; r++) {
                    for (var x = 0; x < 4; x++) {
                        assertEquals(recon[r * 64 + x], scalarOut[32 * r + 4 * j + x], "inverse block " + j);
                    }
                }
            }
        }
    }
}
