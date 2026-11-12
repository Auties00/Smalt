package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers the 64-bit coding paths of the encoder for deep channels: the block coder's windowed form against its
/// 31-bit form, and the 64-bit forward 5-3 wavelet against the 32-bit one and the decoder's inverse. The codestream
/// round trips of `Jp2DeepChannelTest` check that the decoder restores what the 64-bit form codes.
@DisplayName("JPEG 2000 deep channel coding")
class Jp2DeepCodingTest {
    private static final MediaLimits LIMITS = MediaLimits.DEFAULT;

    private static final int[] STYLES = {
            0,
            ComponentCoding.BYPASS,
            ComponentCoding.TERMINATE_ALL | ComponentCoding.RESET,
            ComponentCoding.VERTICALLY_CAUSAL | ComponentCoding.PREDICTABLE_TERMINATION,
            ComponentCoding.BYPASS | ComponentCoding.SEGMENTATION_SYMBOLS | ComponentCoding.PREDICTABLE_TERMINATION,
            ComponentCoding.BYPASS | ComponentCoding.RESET | ComponentCoding.TERMINATE_ALL
                    | ComponentCoding.VERTICALLY_CAUSAL | ComponentCoding.PREDICTABLE_TERMINATION
                    | ComponentCoding.SEGMENTATION_SYMBOLS
    };

    static Stream<Arguments> blocks() {
        var list = Stream.<Arguments>builder();
        var sizes = new int[][]{{64, 64}, {32, 16}, {13, 7}, {1, 1}, {4, 1}, {1, 9}};
        for (var style : STYLES) {
            for (var size : sizes) {
                list.add(Arguments.of(size[0], size[1], style));
            }
        }
        return list.build();
    }

    /// Fills a block with signed values of up to `bits` magnitude bits, a quarter of them zero.
    static int[] values(Random random, int count, int bits) {
        var values = new int[count];
        for (var i = 0; i < count; i++) {
            var magnitude = random.nextInt(4) == 0 ? 0 : random.nextInt(1 << random.nextInt(1, bits + 1));
            values[i] = random.nextBoolean() ? -magnitude : magnitude;
        }
        return values;
    }

    @Nested
    @DisplayName("block coder")
    class BlockCoder {
        @ParameterizedTest(name = "{0}x{1} style {2}")
        @MethodSource("com.github.auties00.smalt.image.jp2.internal.Jp2DeepCodingTest#blocks")
        @DisplayName("the 64-bit form codes a block that fits 31 bits exactly as the 31-bit form does")
        void sameAsNarrow(int width, int height, int style) {
            var random = new Random(width * 131L + height * 7L + style);
            var samples = values(random, width * height, 24);
            var wide = Arrays.stream(samples).asLongStream().toArray();
            var narrow = new EncodedBlock(0, 0, width, height);
            var widened = new EncodedBlock(0, 0, width, height);
            var expected = new Tier1Encoder().encode(narrow, samples, null, null, 0, width, 3, 1, 1, true, style,
                    1.0, null, 0, 0);
            var actual = new Tier1Encoder().encode(widened, null, wide, null, 0, width, 3, 1, 1, true, style, 1.0,
                    null, 0, 0);
            assertEquals(expected, actual);
            assertSame(narrow, widened, narrow.totalPasses);
        }

        @ParameterizedTest(name = "{0}x{1} style {2}")
        @MethodSource("com.github.auties00.smalt.image.jp2.internal.Jp2DeepCodingTest#blocks")
        @DisplayName("a region mask scales the same coefficients in both forms")
        void sameAsNarrowWithRegion(int width, int height, int style) {
            var random = new Random(width * 17L + height * 3L + style);
            var samples = values(random, width * height, 10);
            var mask = new byte[width * height];
            for (var i = 0; i < mask.length; i++) {
                mask[i] = (byte) (random.nextInt(3) == 0 ? 1 : 0);
            }
            var wide = Arrays.stream(samples).asLongStream().toArray();
            var narrow = new EncodedBlock(0, 0, width, height);
            var widened = new EncodedBlock(0, 0, width, height);
            var expected = new Tier1Encoder().encode(narrow, samples, null, null, 0, width, 1, 2, 1, true, style,
                    1.0, mask, 11, 0);
            var actual = new Tier1Encoder().encode(widened, null, wide, null, 0, width, 1, 2, 1, true, style, 1.0,
                    mask, 11, 0);
            assertEquals(expected, actual);
            assertSame(narrow, widened, narrow.totalPasses);
        }

        @ParameterizedTest(name = "shift {0}")
        @org.junit.jupiter.params.provider.ValueSource(ints = {1, 7, 20, 25, 33})
        @DisplayName("coefficients shifted up by many bit-planes code their top passes exactly as the unshifted ones")
        void shiftedPlanes(int shift) {
            // every pass terminated, so each pass's bytes depend only on the decisions coded up to it
            var style = ComponentCoding.TERMINATE_ALL;
            var width = 32;
            var height = 32;
            var random = new Random(shift);
            var samples = values(random, width * height, 24);
            var shifted = Arrays.stream(samples).asLongStream().map(v -> v << shift).toArray();
            var base = new EncodedBlock(0, 0, width, height);
            var deep = new EncodedBlock(0, 0, width, height);
            new Tier1Encoder().encode(base, samples, null, null, 0, width, 2, 1, 1, true, style, 1.0, null, 0, 0);
            new Tier1Encoder().encode(deep, null, shifted, null, 0, width, 2, 1, 1, true, style, 1.0, null, 0, 0);
            assertEquals(base.bitPlanes + shift, deep.bitPlanes);
            var passes = base.totalPasses;
            assertEquals(passes + 3 * shift, deep.totalPasses);
            for (var p = 0; p < passes; p++) {
                assertEquals(base.passRate[p], deep.passRate[p], "rate of pass " + p);
                assertTrue(deep.passTerminated[p]);
            }
            var length = base.passRate[passes - 1];
            assertEquals(base.data.length, length);
            assertArrayEquals(base.data, Arrays.copyOf(deep.data, length));
            // the distortion of a bit-plane above the last weighs 4^shift as much; the last one reads another table
            var scale = Math.scalb(1.0, 2 * shift);
            var planes = base.bitPlanes;
            for (var p = 0; p < passes; p++) {
                var plane = planes - 1 - (p + 2) / 3;
                if (plane == 0) {
                    break;
                }
                var expected = (base.passDistortion[p] - (p == 0 ? 0 : base.passDistortion[p - 1])) * scale;
                var actual = deep.passDistortion[p] - (p == 0 ? 0 : deep.passDistortion[p - 1]);
                assertEquals(expected, actual, Math.abs(expected) * 1e-9, "distortion of pass " + p);
            }
        }

        @Test
        @DisplayName("a coefficient beyond the 57 magnitude bit-planes of the 64-bit form is rejected")
        void tooDeep() {
            var samples = new long[]{1L << 57, 0, 0, 0};
            var block = new EncodedBlock(0, 0, 2, 2);
            assertThrows(IllegalArgumentException.class, () -> new Tier1Encoder().encode(block, null, samples, null,
                    0, 2, 0, 0, 1, true, 0, 1.0, null, 0, 0));
            var fits = new long[]{(1L << 57) - 1, -((1L << 57) - 1), 5, 0};
            new Tier1Encoder().encode(block, null, fits, null, 0, 2, 0, 0, 1, true, 0, 1.0, null, 0, 0);
            assertEquals(Tier1Encoder.MAX_BIT_PLANES, block.bitPlanes);
            assertEquals(3 * Tier1Encoder.MAX_BIT_PLANES - 2, block.totalPasses);
        }

        static Stream<Arguments> tinyBlocks() {
            var list = Stream.<Arguments>builder();
            for (var style : STYLES) {
                for (var size : new int[][]{{1, 1}, {2, 1}, {1, 3}, {2, 2}, {5, 1}}) {
                    list.add(Arguments.of(size[0], size[1], style));
                }
            }
            return list.build();
        }

        @ParameterizedTest(name = "{0}x{1} style {2}")
        @MethodSource("tinyBlocks")
        @DisplayName("a tiny block of 57 magnitude bit-planes fits its output buffer under every style")
        void tinyDeepBlocks(int width, int height, int style) {
            var random = new Random(width * 31L + height * 5L + style);
            var samples = new long[width * height];
            samples[0] = random.nextBoolean() ? (1L << 57) - 1 : -((1L << 57) - 1);
            for (var i = 1; i < samples.length; i++) {
                samples[i] = random.nextLong(-(1L << 57) + 1, 1L << 57);
            }
            var block = new EncodedBlock(0, 0, width, height);
            new Tier1Encoder().encode(block, null, samples, null, 0, width, 3, 0, 1, true, style, 1.0, null, 0, 0);
            assertEquals(Tier1Encoder.MAX_BIT_PLANES, block.bitPlanes);
            assertEquals(3 * Tier1Encoder.MAX_BIT_PLANES - 2, block.totalPasses);
            for (var p = 1; p < block.totalPasses; p++) {
                assertTrue(block.passRate[p] >= block.passRate[p - 1], "rate of pass " + p);
            }
            assertEquals(block.passRate[block.totalPasses - 1], block.data.length);
        }

        @Test
        @DisplayName("deep 9-7 coefficients quantize to the same block as the 31-bit form below its limit")
        void irreversible() {
            var width = 16;
            var height = 12;
            var random = new Random(97);
            var floats = new float[width * height];
            for (var i = 0; i < floats.length; i++) {
                floats[i] = (float) (random.nextGaussian() * 4000);
            }
            var narrow = new EncodedBlock(0, 0, width, height);
            var widened = new EncodedBlock(0, 0, width, height);
            // 20 bit-planes saturate nothing here and fit the 31-bit form; no saturation selects the 64-bit form
            var expected = new Tier1Encoder().encode(narrow, null, null, floats, 0, width, 1, 1, 0.5f, false, 0,
                    1.0, null, 0, 20);
            var actual = new Tier1Encoder().encode(widened, null, null, floats, 0, width, 1, 1, 0.5f, false, 0,
                    1.0, null, 0, 0);
            assertEquals(expected, actual);
            assertSame(narrow, widened, narrow.totalPasses);
        }
    }

    static void assertSame(EncodedBlock expected, EncodedBlock actual, int passes) {
        assertEquals(expected.bitPlanes, actual.bitPlanes, "bit-planes");
        assertEquals(passes, actual.totalPasses, "passes");
        assertArrayEquals(expected.data, actual.data, "bytes");
        assertArrayEquals(Arrays.copyOf(expected.passRate, passes), Arrays.copyOf(actual.passRate, passes), "rates");
        assertArrayEquals(Arrays.copyOf(expected.passDistortion, passes), Arrays.copyOf(actual.passDistortion, passes),
                "distortions");
        for (var p = 0; p < passes; p++) {
            assertEquals(expected.passTerminated[p], actual.passTerminated[p], "termination of pass " + p);
        }
    }

    @Nested
    @DisplayName("wavelet")
    class WaveletTransform {
        static Stream<Arguments> geometries() {
            return Stream.of(Arguments.of(0, 0, 64, 48, 3), Arguments.of(5, 3, 37, 29, 3), Arguments.of(1, 2, 1, 9, 2),
                    Arguments.of(3, 0, 8, 1, 3), Arguments.of(7, 11, 2, 2, 1), Arguments.of(2, 5, 33, 17, 5),
                    Arguments.of(0, 1, 1, 1, 4));
        }

        @ParameterizedTest(name = "origin ({0}, {1}) size {2}x{3} levels {4}")
        @MethodSource("geometries")
        @DisplayName("the 64-bit forward 5-3 transform equals the 32-bit one on samples both hold")
        void sameAsNarrow(int x0, int y0, int width, int height, int levels) {
            var component = component(x0, y0, width, height, levels);
            var random = new Random(x0 * 1000L + y0 * 100L + width * 10L + height + levels);
            var narrow = new int[width * height];
            for (var i = 0; i < narrow.length; i++) {
                narrow[i] = random.nextInt(-(1 << 20), 1 << 20);
            }
            var wide = Arrays.stream(narrow).asLongStream().toArray();
            Wavelet.forward53(narrow, width, component);
            Wavelet.forwardWide(wide, width, component);
            assertArrayEquals(Arrays.stream(narrow).asLongStream().toArray(), wide);
        }

        @ParameterizedTest(name = "origin ({0}, {1}) size {2}x{3} levels {4}")
        @MethodSource("geometries")
        @DisplayName("the decoder's 64-bit inverse restores 40-bit samples the 64-bit forward transform decomposed")
        void roundTrip(int x0, int y0, int width, int height, int levels) {
            var random = new Random(x0 * 7L + y0 * 11L + width * 13L + height * 17L + levels);
            var samples = new long[width * height];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = random.nextLong(-(1L << 40), 1L << 40);
            }
            var coefficients = samples.clone();
            Wavelet.forwardWide(coefficients, width, component(x0, y0, width, height, levels));
            if (width * height > 1) {
                assertTrue(!Arrays.equals(samples, coefficients) || levels == 0, "the transform changed nothing");
            }
            Wavelet.inverseWide(coefficients, width, component(x0, y0, width, height, levels));
            assertArrayEquals(samples, coefficients);
        }

        static TileComponent component(int x0, int y0, int width, int height, int levels) {
            var coding = new ComponentCoding(levels + 1, 4, 4, 0, true, null, null);
            return new TileComponent(x0, y0, x0 + width, y0 + height, coding,
                    new Quantization(Quantization.NONE, 2, new int[Quantization.MAX_BANDS],
                            new int[Quantization.MAX_BANDS]), 40, levels + 1, true, LIMITS);
        }
    }
}
