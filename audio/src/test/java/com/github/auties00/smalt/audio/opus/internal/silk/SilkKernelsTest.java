package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the Vector API SILK kernels are bit-identical to the scalar reference kernels on
 * randomized input, including full-scale values that exercise wrap-around and saturation.
 */
class SilkKernelsTest {
    private static SilkKernels vector() {
        Skip.unlessVector(Simd.ENABLED);
        return VectorSilkKernels.createForTest();
    }

    @ParameterizedTest(name = "increment {0}")
    @ValueSource(ints = {43691, 32768, 21846, 65536, 49152, 10923, 16384, 87382, 98304, 65537, 43690, 30001})
    @DisplayName("iirFirInterpol matches the scalar kernel bit for bit")
    void iirFir(int increment) {
        var kernels = vector();
        var random = new Random(increment);
        for (var round = 0; round < 50; round++) {
            var n = 1 + random.nextInt(480);
            var buf = new short[2 * n + 8 + 16];
            for (var i = 0; i < buf.length; i++) {
                buf[i] = (short) (round % 5 == 0 ? (random.nextBoolean() ? 32767 : -32768) : random.nextInt(65536) - 32768);
            }
            var expected = new short[16 * n + 16];
            var actual = new short[16 * n + 16];
            var max = n << 17;
            var e = new ScalarSilkKernels().iirFirInterpol(expected, 3, buf, max, increment, new int[SilkKernels.SCRATCH_SIZE]);
            var a = kernels.iirFirInterpol(actual, 3, buf, max, increment, new int[SilkKernels.SCRATCH_SIZE]);
            assertEquals(e, a);
            assertArrayEquals(expected, actual);
        }
    }

    @ParameterizedTest(name = "order {0}, fracs {1}, increment {2}")
    @CsvSource({"18,3,87382", "18,2,98304", "24,1,131072", "36,1,196608", "36,1,262144", "36,1,393216",
            "24,1,131073", "36,1,196607"})
    @DisplayName("downFirInterpol matches the scalar kernel bit for bit")
    void downFir(int order, int fracs, int increment) {
        var kernels = vector();
        var coefs = switch (order) {
            case 18 -> fracs == 3 ? ResamplerRom.silk_Resampler_3_4_COEFS : ResamplerRom.silk_Resampler_2_3_COEFS;
            case 24 -> ResamplerRom.silk_Resampler_1_2_COEFS;
            default -> increment == 196608 ? ResamplerRom.silk_Resampler_1_3_COEFS
                    : increment == 262144 ? ResamplerRom.silk_Resampler_1_4_COEFS : ResamplerRom.silk_Resampler_1_6_COEFS;
        };
        var random = new Random(order * 31L + increment);
        for (var round = 0; round < 50; round++) {
            var n = 1 + random.nextInt(480);
            var buf = new int[n + order + 8];
            for (var i = 0; i < buf.length; i++) {
                buf[i] = round % 7 == 0 ? random.nextInt() : random.nextInt(1 << 26) - (1 << 25);
            }
            var expected = new short[n + 8];
            var actual = new short[n + 8];
            var max = n << 16;
            var e = new ScalarSilkKernels().downFirInterpol(expected, 1, buf, coefs, 2, order, fracs, max, increment,
                    new int[SilkKernels.SCRATCH_SIZE]);
            var a = kernels.downFirInterpol(actual, 1, buf, coefs, 2, order, fracs, max, increment,
                    new int[SilkKernels.SCRATCH_SIZE]);
            assertEquals(e, a);
            assertArrayEquals(expected, actual);
        }
    }

    @ParameterizedTest(name = "order {0}")
    @ValueSource(ints = {6, 10, 16, 24})
    @DisplayName("lpcAnalysisFilter matches the scalar kernel bit for bit")
    void lpcAnalysis(int order) {
        var kernels = vector();
        var random = new Random(order);
        for (var round = 0; round < 100; round++) {
            var len = order + random.nextInt(340);
            var in = new short[len + 5];
            for (var i = 0; i < in.length; i++) {
                in[i] = (short) (round % 4 == 0 ? (random.nextBoolean() ? 32767 : -32768) : random.nextInt(65536) - 32768);
            }
            var b = new short[order];
            for (var i = 0; i < order; i++) {
                b[i] = (short) (random.nextInt(65536) - 32768);
            }
            var expected = new short[len + 3];
            var actual = new short[len + 3];
            new ScalarSilkKernels().lpcAnalysisFilter(expected, 2, in, 3, b, 0, len, order);
            kernels.lpcAnalysisFilter(actual, 2, in, 3, b, 0, len, order);
            assertArrayEquals(expected, actual);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    @DisplayName("nsqDotProducts matches the scalar kernel and a direct smulwb sum bit for bit")
    void nsqDots(int seed) {
        var kernels = vector();
        var random = new Random(seed);
        var stride = 325;
        for (var round = 0; round < 400; round++) {
            var dd = new int[4 * stride];
            for (var i = 0; i < dd.length; i++) {
                dd[i] = switch (round % 4) {
                    case 0 -> random.nextInt();
                    case 1 -> random.nextBoolean() ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                    default -> random.nextInt(-1 << 24, 1 << 24);
                };
            }
            var lpc = new int[16];
            var ar = new int[24];
            for (var i = 0; i < 16; i++) {
                lpc[i] = round % 7 == 0 ? (random.nextBoolean() ? 32767 : -32768) : random.nextInt(-32768, 32768);
            }
            for (var i = 0; i < 24; i++) {
                ar[i] = round % 7 == 1 ? (random.nextBoolean() ? 32767 : -32768) : random.nextInt(-32768, 32768);
            }
            var lpcOffset = random.nextInt(0, 96 - 16 + 1);
            var expected = new int[8];
            var actual = new int[8];
            new ScalarSilkKernels().nsqDotProducts(dd, stride, lpcOffset, lpc, 296, ar, expected);
            kernels.nsqDotProducts(dd, stride, lpcOffset, lpc, 296, ar, actual);
            assertArrayEquals(expected, actual);
            for (var k = 0; k < 4; k++) {
                var sum = 0;
                for (var m = 0; m < 16; m++) {
                    sum += (int) ((dd[k * stride + lpcOffset + m] * (long) lpc[m]) >> 16);
                }
                assertEquals(sum, expected[k]);
            }
        }
    }
}
