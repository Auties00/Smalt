package com.github.auties00.smalt.audio.opus.internal.celt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the CELT building blocks in isolation: Laplace and PVQ codeword round trips, the FFT
 * against a naive DFT, the MDCT against its definition and its perfect reconstruction, the
 * bit-exact trigonometry of the band splitting, and the allocation tables.
 */
class CeltComponentsTest {
    private static final CeltMode MODE = CeltMode.MODE_48000_960;

    @Nested
    @DisplayName("Laplace coding")
    class LaplaceCoding {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("round-trips values over every model of the energy table, clamping like the reference")
        void roundTrip(int seed) {
            var random = new Random(seed);
            var n = 4000;
            var fs = new int[n];
            var decay = new int[n];
            var coded = new int[n];
            var buf = new byte[16000];
            var enc = new RangeEncoder(buf, 0, buf.length);
            for (var i = 0; i < n; i++) {
                fs[i] = (1 + random.nextInt(250)) << 7;
                decay[i] = (1 + random.nextInt(178)) << 6;
                var value = (int) Math.round(random.nextGaussian() * (random.nextBoolean() ? 2 : 40));
                coded[i] = Laplace.laplaceEncode(enc, value, fs[i], decay[i]);
                if (Math.abs(value) < 8) {
                    assertEquals(value, coded[i], "small values are coded exactly");
                }
            }
            enc.encDone();
            assertEquals(0, enc.getError());
            var dec = new RangeDecoder(buf, 0, buf.length);
            for (var i = 0; i < n; i++) {
                assertEquals(coded[i], Laplace.laplaceDecode(dec, fs[i], decay[i]), "value " + i);
            }
        }
    }

    @Nested
    @DisplayName("PVQ codewords")
    class Codewords {
        @ParameterizedTest(name = "N={0} K={1}")
        @CsvSource({"2,1", "2,5", "3,4", "4,4", "5,3", "8,2", "6,6", "2,40", "3,20"})
        @DisplayName("enumerates every codeword of small (N, K) as a bijection")
        void exhaustive(int n, int k) {
            var count = Cwrs.pvqV(n, k) & 0xFFFFFFFFL;
            var seen = new java.util.HashSet<String>();
            for (var index = 0L; index < count; index++) {
                var buf = new byte[64];
                var enc = new RangeEncoder(buf, 0, buf.length);
                enc.encUint((int) index, (int) count);
                enc.encDone();
                var y = new int[n];
                var yy = Cwrs.decodePulses(y, 0, n, k, new RangeDecoder(buf, 0, buf.length));
                var sum = 0;
                var sq = 0;
                for (var v : y) {
                    sum += Math.abs(v);
                    sq += v * v;
                }
                assertEquals(k, sum, "pulse count");
                assertEquals(sq, yy, "squared norm");
                assertTrue(seen.add(java.util.Arrays.toString(y)), "duplicate codeword");
                var out = new byte[64];
                var enc2 = new RangeEncoder(out, 0, out.length);
                Cwrs.encodePulses(y, 0, n, k, enc2);
                enc2.encDone();
                assertArrayEquals(buf, out, "index " + index);
            }
        }

        @Test
        @DisplayName("round-trips random vectors for every band size and pulse count the codec can allocate")
        void random() {
            var random = new Random(7);
            for (var LM = -1; LM <= 3; LM++) {
                for (var band = 0; band < MODE.nbEBands; band++) {
                    var index = MODE.cache.index[(LM + 1) * MODE.nbEBands + band];
                    var n = ((MODE.eBands[band + 1] - MODE.eBands[band]) << (LM + 1)) >> 1;
                    if (index < 0 || n < 2) {
                        continue;
                    }
                    var maxQ = MODE.cache.bits[index];
                    for (var q = 1; q <= maxQ; q++) {
                        var k = Rate.getPulses(q);
                        var y = new int[n];
                        for (var p = 0; p < k; p++) {
                            y[random.nextInt(n)] += 1;
                        }
                        for (var i = 0; i < n; i++) {
                            if (random.nextBoolean()) {
                                y[i] = -y[i];
                            }
                        }
                        var buf = new byte[64];
                        var enc = new RangeEncoder(buf, 0, buf.length);
                        Cwrs.encodePulses(y, 0, n, k, enc);
                        enc.encDone();
                        var out = new int[n];
                        Cwrs.decodePulses(out, 0, n, k, new RangeDecoder(buf, 0, buf.length));
                        assertArrayEquals(y, out, "N=" + n + " K=" + k);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("FFT")
    class Fft {
        @ParameterizedTest(name = "shift {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("matches a naive DFT scaled by 1/N")
        void dft(int shift) {
            var st = MODE.mdct.kfft[shift];
            var n = st.nfft;
            var random = new Random(shift);
            var in = new float[2 * n];
            for (var i = 0; i < in.length; i++) {
                in[i] = (float) random.nextGaussian();
            }
            var out = new float[2 * n];
            KissFft.opusFft(st, in, out);
            for (var k = 0; k < n; k++) {
                double re = 0;
                double im = 0;
                for (var t = 0; t < n; t++) {
                    var angle = -2 * Math.PI * k * t / n;
                    re += in[2 * t] * Math.cos(angle) - in[2 * t + 1] * Math.sin(angle);
                    im += in[2 * t] * Math.sin(angle) + in[2 * t + 1] * Math.cos(angle);
                }
                assertEquals(re / n, out[2 * k], 2e-5, "re " + k);
                assertEquals(im / n, out[2 * k + 1], 2e-5, "im " + k);
            }
        }

        @ParameterizedTest(name = "shift {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("runs identically on split and interleaved layouts")
        void splitLayout(int shift) {
            var st = MODE.mdct.kfft[shift];
            var n = st.nfft;
            var random = new Random(10 + shift);
            var interleaved = new float[2 * n];
            var split = new float[2 * n];
            for (var i = 0; i < n; i++) {
                interleaved[2 * i] = split[i] = (float) random.nextGaussian() * 100;
                interleaved[2 * i + 1] = split[n + i] = (float) random.nextGaussian() * 100;
            }
            KissFft.opusFftImpl(st, interleaved, 0);
            FftSoa.impl(st, split, 0, n);
            for (var i = 0; i < n; i++) {
                assertEquals(Float.floatToRawIntBits(interleaved[2 * i]), Float.floatToRawIntBits(split[i]));
                assertEquals(Float.floatToRawIntBits(interleaved[2 * i + 1]), Float.floatToRawIntBits(split[n + i]));
            }
        }
    }

    @Nested
    @DisplayName("MDCT")
    class MdctTransform {
        @ParameterizedTest(name = "N = {0}")
        @ValueSource(ints = {1920, 960, 480, 240})
        @DisplayName("forward transform matches the MDCT definition above 60 dB SNR (test_unit_mdct)")
        void forwardDefinition(int nfft) {
            var shift = Integer.numberOfTrailingZeros(1920 / nfft);
            var random = new Random(nfft);
            var in = new float[nfft];
            for (var k = 0; k < nfft; k++) {
                in[k] = (random.nextInt(32768) - 16384) * 32768f;
            }
            var window = new float[nfft / 2];
            java.util.Arrays.fill(window, 1.0f);
            var out = new float[nfft / 2];
            Mdct.cltMdctForward(MODE.mdct, in.clone(), 0, out, 0, window, nfft / 2, shift, 1, new float[960], new float[960]);
            double err = 0;
            double sig = 0;
            for (var bin = 0; bin < nfft / 2; bin++) {
                double ans = 0;
                for (var k = 0; k < nfft; k++) {
                    ans += in[k] * Math.cos(2 * Math.PI * (k + .5 + .25 * nfft) * (bin + .5) / nfft) / (nfft / 4.0);
                }
                err += (ans - out[bin]) * (ans - out[bin]);
                sig += ans * ans;
            }
            assertTrue(10 * Math.log10(sig / err) > 60, "SNR " + 10 * Math.log10(sig / err));
        }

        @ParameterizedTest(name = "N = {0}")
        @ValueSource(ints = {1920, 960, 480, 240})
        @DisplayName("inverse transform matches the IMDCT definition above 60 dB SNR (test_unit_mdct)")
        void inverseDefinition(int nfft) {
            var shift = Integer.numberOfTrailingZeros(1920 / nfft);
            var random = new Random(nfft + 1);
            var in = new float[nfft];
            for (var k = 0; k < nfft; k++) {
                in[k] = (random.nextInt(32768) - 16384) * 32768f / nfft;
            }
            var window = new float[nfft / 2];
            java.util.Arrays.fill(window, 1.0f);
            var out = new float[nfft];
            Mdct.cltMdctBackward(MODE.mdct, in, 0, out, 0, window, nfft / 2, shift, 1, new float[960]);
            for (var k = 0; k < nfft / 4; k++) {
                out[nfft - k - 1] = out[nfft / 2 + k];
            }
            double err = 0;
            double sig = 0;
            for (var bin = 0; bin < nfft; bin++) {
                double ans = 0;
                for (var k = 0; k < nfft / 2; k++) {
                    ans += in[k] * Math.cos(2 * Math.PI * (bin + .5 + .25 * nfft) * (k + .5) / nfft);
                }
                err += (ans - out[bin]) * (ans - out[bin]);
                sig += ans * ans;
            }
            assertTrue(10 * Math.log10(sig / err) > 60, "SNR " + 10 * Math.log10(sig / err));
        }

        @ParameterizedTest(name = "shift {0}")
        @ValueSource(ints = {0, 3})
        @DisplayName("reconstructs the signal by overlap-add of consecutive inverse transforms")
        void perfectReconstruction(int shift) {
            var n2 = (MODE.mdct.n >> shift) / 2;
            var overlap = MODE.overlap;
            var frames = 6;
            var random = new Random(3);
            var signal = new float[(frames + 1) * n2 + overlap];
            for (var i = 0; i < signal.length; i++) {
                signal[i] = (float) random.nextGaussian();
            }
            var out = new float[(frames + 1) * n2 + overlap];
            var coefficients = new float[n2];
            for (var f = 0; f < frames; f++) {
                Mdct.cltMdctForward(MODE.mdct, signal.clone(), f * n2, coefficients, 0, MODE.window, overlap, shift,
                        1, new float[960], new float[960]);
                Mdct.cltMdctBackward(MODE.mdct, coefficients, 0, out, f * n2, MODE.window, overlap, shift, 1,
                        new float[960]);
            }
            for (var i = overlap; i < frames * n2; i++) {
                assertEquals(signal[i], out[i], 2e-4, "sample " + i);
            }
        }
    }

    @Nested
    @DisplayName("band trigonometry and allocation")
    class BandMath {
        @ParameterizedTest(name = "cos({0}) = {1}")
        @CsvSource({"100,32767", "1000,32618", "4096,30274", "8192,23171", "12000,13371", "16383,3"})
        @DisplayName("bitexact_cos matches the reference integer arithmetic")
        void bitexactCos(int x, int expected) {
            assertEquals(expected, Bands.bitexactCos(x));
        }

        @ParameterizedTest(name = "log2tan({0}, {1}) = {2}")
        @CsvSource({"32767,1,30690", "1,32767,-30690", "23170,23170,0", "30000,12000,2716", "100,20000,-15646"})
        @DisplayName("bitexact_log2tan matches the reference integer arithmetic")
        void bitexactLog2tan(int isin, int icos, int expected) {
            assertEquals(expected, Bands.bitexactLog2tan(isin, icos));
        }

        @Test
        @DisplayName("haar1 applied twice restores the input up to float rounding")
        void haarInvolution() {
            var random = new Random(1);
            for (var stride : new int[]{1, 2, 4, 8}) {
                var x = new float[64];
                for (var i = 0; i < x.length; i++) {
                    x[i] = (float) random.nextGaussian();
                }
                var y = x.clone();
                Bands.haar1(y, 0, 64 / stride, stride);
                Bands.haar1(y, 0, 64 / stride, stride);
                for (var i = 0; i < x.length; i++) {
                    assertEquals(x[i], y[i], 1e-6);
                }
            }
        }

        @Test
        @DisplayName("pulse cache costs grow with the pulse count and bits2pulses inverts pulses2bits")
        void pulseCache() {
            for (var LM = -1; LM <= 3; LM++) {
                for (var band = 0; band < MODE.nbEBands; band++) {
                    if (MODE.cache.index[(LM + 1) * MODE.nbEBands + band] < 0) {
                        continue;
                    }
                    var previous = -1;
                    var maxQ = MODE.cache.bits[MODE.cache.index[(LM + 1) * MODE.nbEBands + band]];
                    for (var q = 0; q <= maxQ; q++) {
                        var bits = Rate.pulses2bits(MODE, band, LM, q);
                        assertTrue(bits >= previous, "non-decreasing costs");
                        previous = bits;
                        var back = Rate.bits2pulses(MODE, band, LM, bits);
                        assertEquals(bits, Rate.pulses2bits(MODE, band, LM, back), "band " + band + " LM " + LM);
                    }
                }
            }
        }

        @Test
        @DisplayName("the allocation never exceeds its budget")
        void allocationBudget() {
            var nb = MODE.nbEBands;
            var random = new Random(4);
            var scratch = new int[][]{new int[nb], new int[nb], new int[nb], new int[nb]};
            for (var trial = 0; trial < 300; trial++) {
                var LM = random.nextInt(4);
                var C = 1 + random.nextInt(2);
                var start = random.nextBoolean() ? 0 : 17;
                var end = start == 17 ? 21 : 13 + random.nextInt(9);
                var cap = new int[nb];
                Celt.initCaps(MODE, cap, LM, C);
                var offsets = new int[nb];
                var total = random.nextInt(1275 * 64);
                var pulses = new int[nb];
                var ebits = new int[nb];
                var prio = new int[nb];
                var buf = new byte[1275];
                var enc = new RangeEncoder(buf, 0, buf.length);
                var coded = Rate.cltComputeAllocation(MODE, start, end, offsets, cap, random.nextInt(11), new int[]{end},
                        new int[]{0}, total, new int[1], pulses, ebits, prio, C, LM, enc, 1, 0, end - 1, scratch);
                assertTrue(coded > start && coded <= end);
                var used = 0L;
                for (var i = start; i < end; i++) {
                    assertTrue(pulses[i] >= 0 && ebits[i] >= 0 && ebits[i] <= Rate.MAX_FINE_BITS);
                    used += pulses[i] + ((long) C * ebits[i] << EntropyCoder.BITRES);
                }
                assertTrue(used <= Math.max(total, 0) + (8 << EntropyCoder.BITRES), "used " + used + " of " + total);
            }
        }
    }
}
