package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;
import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the SILK shared layer: the fixed-point macros, the entropy coding round trips (shell
 * coder, signs, gains), the NLSF chain, and the resampler bit-exactly against the reference.
 */
class SilkComponentsTest {
    @Nested
    @DisplayName("fixed-point macros")
    class Macros {
        @Test
        @DisplayName("multiply-and-shift macros match 64-bit arithmetic on edge values")
        void multiplies() {
            int[] values = {0, 1, -1, 32767, -32768, 65535, 65536, -65536, Integer.MAX_VALUE, Integer.MIN_VALUE, 123456789, -987654321};
            for (var a : values) {
                for (var b : values) {
                    assertEquals((int) (((long) a * (short) b) >> 16), SigProcFix.smulwb(a, b));
                    assertEquals((int) (a + (((long) b * (short) a) >> 16)), SigProcFix.smlawb(a, b, a));
                    assertEquals((int) (((long) a * b) >> 16), SigProcFix.smulww(a, b));
                    assertEquals((int) (((long) a * b) >> 32), SigProcFix.smmul(a, b));
                    assertEquals((short) a * (short) b, SigProcFix.smulbb(a, b));
                    var sum = (long) a + b;
                    assertEquals((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum)), SigProcFix.addSat32(a, b));
                    var diff = (long) a - b;
                    assertEquals((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, diff)), SigProcFix.subSat32(a, b));
                }
            }
        }

        @Test
        @DisplayName("rounding shifts, counts, rotations and saturation behave like the C macros")
        void shiftsAndCounts() {
            assertEquals(2, SigProcFix.rshiftRound(3, 1));
            assertEquals(1, SigProcFix.rshiftRound(5, 2));
            assertEquals(2, SigProcFix.rshiftRound(6, 2));
            assertEquals(-1, SigProcFix.rshiftRound(-5, 2));
            assertEquals(64, SigProcFix.clzFracQ7(192));
            assertEquals(64, SigProcFix.clzFracQ7(3 << 19));
            assertEquals(64, SigProcFix.clzFracQ7(3));
            assertEquals(0, SigProcFix.clzFracQ7(0));
            assertEquals(32767, SigProcFix.sat16(100000));
            assertEquals(-32768, SigProcFix.sat16(-100000));
            assertEquals(0x7FFFFFF0, SigProcFix.lshiftSat32(1 << 30, 4));
            assertEquals(5, SigProcFix.limit(10, 5, 0));
            assertEquals(0, SigProcFix.limit(-10, 5, 0));
            assertEquals(3, SigProcFix.limit(3, 0, 5));
            assertEquals(907633515, SigProcFix.rand(0));
            assertEquals(11796, SigProcFix.fixConst(0.18, 16));
            assertEquals(3277, SigProcFix.fixConst(0.1f, 15));
        }

        @Test
        @DisplayName("approximate square root, division and inverse stay within their documented error")
        void approximations() {
            var random = new Random(7);
            for (var i = 0; i < 20000; i++) {
                var x = random.nextInt(Integer.MAX_VALUE - 200) + 200;
                var approx = SigProcFix.sqrtApprox(x);
                assertEquals(Math.sqrt(x), approx, Math.sqrt(x) * (Math.sqrt(x) > 120 ? 0.025 : 0.1) + 1);
                var a = random.nextInt(1 << 20) + 1;
                var b = random.nextInt(1 << 24) + (1 << 10);
                assertEquals(((double) a * (1 << 16)) / b, SigProcFix.div32VarQ(a, b, 16),
                        Math.max(2, ((double) a * (1 << 16)) / b * 1e-3));
                assertEquals((double) (1L << 40) / b, SigProcFix.inverse32VarQ(b, 40), Math.max(2, (double) (1L << 40) / b * 1e-3));
            }
            assertEquals(0, SigProcFix.sqrtApprox(0));
            assertEquals(0, SigProcFix.sqrtApprox(-5));
        }

        @Test
        @DisplayName("log and linear conversions are close inverses and clamp at their limits")
        void logLin() {
            for (var x = 1; x < (1 << 30); x += 7919 + x / 3) {
                var back = LinLog.log2lin(LinLog.lin2log(x));
                assertEquals(x, back, x * 0.03 + 1);
            }
            assertEquals(0, LinLog.log2lin(-1));
            assertEquals(Integer.MAX_VALUE, LinLog.log2lin(3967));
            assertEquals(0, SigmQ15.sigmQ15(-200));
            assertEquals(32767, SigmQ15.sigmQ15(200));
            assertEquals(16384, SigmQ15.sigmQ15(0));
        }
    }

    @Nested
    @DisplayName("entropy coding")
    class Entropy {
        @Test
        @DisplayName("shell coder round-trips random pulse blocks")
        void shell() {
            var random = new Random(3);
            var buffer = new byte[40000];
            var encoder = new RangeEncoder(buffer, 0, buffer.length);
            var blocks = new int[2000][16];
            for (var block : blocks) {
                var total = random.nextInt(17);
                for (var k = 0; k < total; k++) {
                    block[random.nextInt(16)]++;
                }
                ShellCoder.shellEncoder(encoder, block, 0);
            }
            encoder.encDone();
            assertEquals(0, encoder.getError());
            var decoder = new RangeDecoder(buffer, 0, buffer.length);
            var out = new short[16];
            for (var block : blocks) {
                var sum = 0;
                for (var v : block) {
                    sum += v;
                }
                ShellCoder.shellDecoder(out, 0, decoder, sum);
                for (var k = 0; k < 16; k++) {
                    assertEquals(block[k], out[k]);
                }
            }
        }

        @Test
        @DisplayName("sign coding round-trips signed pulses for every signal and offset type")
        void signs() {
            var random = new Random(4);
            for (var signalType = 0; signalType < 3; signalType++) {
                for (var offset = 0; offset < 2; offset++) {
                    var length = 320;
                    var pulses = new byte[length];
                    var sums = new int[length / 16];
                    for (var i = 0; i < length; i++) {
                        pulses[i] = (byte) (random.nextInt(9) - 4);
                        sums[i / 16] += Math.abs(pulses[i]);
                    }
                    var buffer = new byte[4000];
                    var encoder = new RangeEncoder(buffer, 0, buffer.length);
                    CodeSigns.encodeSigns(encoder, pulses, 0, length, signalType, offset, sums);
                    encoder.encDone();
                    var decoded = new short[length];
                    for (var i = 0; i < length; i++) {
                        decoded[i] = (short) Math.abs(pulses[i]);
                    }
                    CodeSigns.decodeSigns(new RangeDecoder(buffer, 0, buffer.length), decoded, length, signalType, offset, sums);
                    for (var i = 0; i < length; i++) {
                        assertEquals(pulses[i], decoded[i]);
                    }
                }
            }
        }

        @Test
        @DisplayName("gain quantization and dequantization agree on gains and the running index")
        void gains() {
            var random = new Random(5);
            byte prevQuant = 10;
            byte prevDequant = 10;
            for (var frame = 0; frame < 2000; frame++) {
                var gains = new int[4];
                for (var k = 0; k < 4; k++) {
                    gains[k] = random.nextInt(1 << (10 + random.nextInt(20))) + 1;
                }
                var conditional = frame % 3 == 0 ? 0 : 1;
                var ind = new byte[4];
                prevQuant = GainQuant.gainsQuant(ind, gains, prevQuant, conditional, 4);
                var dequant = new int[4];
                prevDequant = GainQuant.gainsDequant(dequant, ind, prevDequant, conditional, 4);
                assertArrayEquals(gains, dequant);
                assertEquals(prevQuant, prevDequant);
            }
        }
    }

    @Nested
    @DisplayName("NLSF chain")
    class NlsfChain {
        @Test
        @DisplayName("stabilization yields sorted vectors that respect the minimum distances")
        void stabilize() {
            var random = new Random(6);
            for (var cb : new NlsfCodebook[]{Tables.silk_NLSF_CB_NB_MB, Tables.silk_NLSF_CB_WB}) {
                for (var n = 0; n < 5000; n++) {
                    var nlsf = new short[cb.order];
                    for (var i = 0; i < cb.order; i++) {
                        nlsf[i] = (short) random.nextInt(32768);
                    }
                    NlsfStabilize.nlsfStabilize(nlsf, cb.deltaMin_Q15, cb.order);
                    assertTrue(nlsf[0] >= cb.deltaMin_Q15[0]);
                    for (var i = 1; i < cb.order; i++) {
                        assertTrue(nlsf[i] - nlsf[i - 1] >= cb.deltaMin_Q15[i], "distance at " + i);
                    }
                    assertTrue(nlsf[cb.order - 1] <= 32768 - cb.deltaMin_Q15[cb.order]);
                }
            }
        }

        @Test
        @DisplayName("decoding any codebook path yields a stable LPC filter")
        void decodeToStableFilter() {
            var random = new Random(8);
            for (var cb : new NlsfCodebook[]{Tables.silk_NLSF_CB_NB_MB, Tables.silk_NLSF_CB_WB}) {
                for (var n = 0; n < 3000; n++) {
                    var path = new byte[cb.order + 1];
                    path[0] = (byte) random.nextInt(cb.nVectors);
                    for (var i = 1; i <= cb.order; i++) {
                        path[i] = (byte) (random.nextInt(21) - 10);
                    }
                    var nlsf = new short[cb.order];
                    NlsfDecode.nlsfDecode(nlsf, path, cb);
                    var a = new short[cb.order];
                    Nlsf2A.nlsf2a(a, nlsf, cb.order);
                    assertTrue(LpcInvPredGain.lpcInversePredGain(a, cb.order) > 0, "stable filter");
                }
            }
        }
    }

    @Nested
    @DisplayName("resampler")
    class ResamplerTests {
        @ParameterizedTest(name = "{0} -> {1} Hz, encoder={2}")
        @CsvSource({
                "8000,8000,0,1a052a96c3c97427670786966cda9dcb0f3dc15aa841f712eeb0b16cc25223da",
                "8000,12000,0,e7d6a537c8e23c42438b320ac6e2bf5fd3aa7a75393cbe7790c6702ff9ba9c23",
                "8000,16000,0,c03797a616ffecd79b7a2fb140692165f540568791eead736aaefe99db562f13",
                "8000,24000,0,264ce48f019a6089e00e7d0b756f267b8fed566143d092a7480020ad9cf97719",
                "8000,48000,0,6e7c2016ce2b7661d9269d7277d766a41b83a90c8b4e0b8ce30587c9a88a85fa",
                "12000,8000,0,a83577e2699c1d147159d9341fc30f943cf4c9e0de4fc3e2899d4da4c4c1be2c",
                "12000,12000,0,e51ff1aa2b9fb6efe03274f22ccc9834fdbc853aaa8d9768a0ed5945769b7823",
                "12000,16000,0,538678ff57d7183280b3fa2d045e5a7e9423089c7d62548d0bdc280852453dfe",
                "12000,24000,0,ac8dd0a1f232c25653bd9cd26116e30e70396fd91794b865b78389c546334aa8",
                "12000,48000,0,316a94b3728543ff04741cb6921a0309cd543f5021cd19e6d42684f2dfe00e8e",
                "16000,8000,0,712a6676c1d388a73ce15af73710364bef055883abe3a1f2e7db2c3a5a9c5d6b",
                "16000,12000,0,e164bb50d6d6dd51b7cc1c16694050cc1e4dd5c10294f7f7dd705dab1c84d6d0",
                "16000,16000,0,027e27bb7a67f3448a97293f90fc5a8cf704feff709da5664387cb43dae7d5c0",
                "16000,24000,0,7985edb6c8aec7ce2ee9465d50dcdfa7070c77a9ab4e320544529f660773f2c2",
                "16000,48000,0,9b80aabbf4be2ec7fa8cad8748fa392b4923d7f91c264adf409775c53441cad8",
                "8000,8000,1,94689eee3f081801fe3d36733a47df3a54da85a76531fe9b3fc45eb34501bc46",
                "12000,8000,1,a83577e2699c1d147159d9341fc30f943cf4c9e0de4fc3e2899d4da4c4c1be2c",
                "16000,8000,1,712a6676c1d388a73ce15af73710364bef055883abe3a1f2e7db2c3a5a9c5d6b",
                "24000,8000,1,51061132ad0cf48dc13042d263b3384f4ea5fc51d5ff850e911fec4f8735d0db",
                "48000,8000,1,3b0cfb9b38ed9e7f4aa276774f2c8eeefe487a3fad7e54092fcfde6ca0345402",
                "8000,12000,1,e7d6a537c8e23c42438b320ac6e2bf5fd3aa7a75393cbe7790c6702ff9ba9c23",
                "12000,12000,1,a9f1eac8bb0523b7f74c707795c9ac4fec7445ec516bafc43722036e1d86c610",
                "16000,12000,1,2c385575e755f712bd7f2e56f37c85cd8386baf8278bb0d2d8254ce5e978989f",
                "24000,12000,1,7db623b36d7ef12087a8d5ec03178c686df52b167e2d2e244556f1af403de74a",
                "48000,12000,1,32dc890c32271dd8b5ca225101489836ee33ec75e316effd3395fc3420c6dbff",
                "8000,16000,1,8026eaec0701229b497b74b18b3cf2726f1e4ef512586d57c9d89f25f5fd86b0",
                "12000,16000,1,e78378b2d3beed5b29b2185756b6368fc9e6aac74636d3651989bff5bbc76d0a",
                "16000,16000,1,0ae6fc138b6e89ea1a79ad2764f9bc9b27589f352cda4755fbc6fbed2fac198b",
                "24000,16000,1,7a272c0d67cf068ef2c3b78db65e3c6c5630d86a4622f59a0b079ced37f3d68d",
                "48000,16000,1,d7e4d80a112fe7497b1c6ebd4b954c4a61faf82b943f22a521651033644699cf"
        })
        @DisplayName("matches libopus bit for bit for every rate pair SILK uses")
        void bitExact(int in, int out, int forEnc, String sha256) throws Exception {
            var state = new ResamplerState();
            assertEquals(0, Resampler.resamplerInit(state, in, out, forEnc));
            var n = in * 2;
            var x = new short[n];
            var seed = 12345;
            for (var i = 0; i < n; i++) {
                seed = seed * 1103515245 + 12345;
                var noise = ((seed >>> 16) & 0x7FFF) - 16384;
                var period = in / 100 + 7;
                var tri = (i % period) * 40000 / period - 20000;
                var v = noise / 4 + tri;
                x[i] = (short) Math.max(-32768, Math.min(32767, v));
            }
            int[] chunks = {10, 20, 1, 7, 13, 5, 40};
            var digest = MessageDigest.getInstance("SHA-256");
            var buf = new short[48 * 40 * 2];
            var pos = 0;
            var c = 0;
            while (pos < n) {
                var ms = chunks[c++ % 7];
                var len = ms * (in / 1000);
                if (pos + len > n) {
                    break;
                }
                Resampler.resampler(state, buf, 0, x, pos, len);
                var count = ms * (out / 1000);
                var bytes = new byte[count * 2];
                for (var i = 0; i < count; i++) {
                    bytes[2 * i] = (byte) buf[i];
                    bytes[2 * i + 1] = (byte) (buf[i] >> 8);
                }
                digest.update(bytes);
                pos += len;
            }
            assertEquals(sha256, HexFormat.of().formatHex(digest.digest()));
        }

        @Test
        @DisplayName("rejects rate pairs outside the SILK matrix")
        void rejectsUnsupported() {
            var state = new ResamplerState();
            assertEquals(-1, Resampler.resamplerInit(state, 44100, 16000, 1));
            assertEquals(-1, Resampler.resamplerInit(state, 16000, 44100, 0));
            assertEquals(-1, Resampler.resamplerInit(state, 24000, 48000, 0));
        }
    }
}
