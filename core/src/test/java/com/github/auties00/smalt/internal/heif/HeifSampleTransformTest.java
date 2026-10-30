package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the sample transform expressions of AVIF 1.2 ({@code sato}): the payload syntax and its
 * validation, every operator at every intermediate width with its saturation, and the application
 * to the planes of input pictures, alpha included, with the output clamping.
 */
@DisplayName("sample transform")
class HeifSampleTransformTest {
    private static HeifSampleTransform parse(int inputs, int bitDepthCode, long... tokens) {
        return HeifSampleTransform.parse(HeifBuilder.sato(bitDepthCode, tokens), inputs);
    }

    private static long evaluate(HeifSampleTransform transform, long... samples) {
        return transform.evaluate(samples, new long[Math.max(1, transform.stackDepth())]);
    }

    /** Evaluates {@code L op R} or {@code op L} on 32-bit intermediates, from constants. */
    private static long operate(int operator, long... operands) {
        var tokens = new long[operands.length * 2 + 1];
        for (var i = 0; i < operands.length; i++) {
            tokens[2 * i + 1] = operands[i];
        }
        tokens[tokens.length - 1] = operator;
        return evaluate(parse(1, 2, tokens));
    }

    @Nested
    @DisplayName("operators on 32-bit intermediates")
    class Operators {
        @ParameterizedTest(name = "op {0} of {1} is {2}")
        @CsvSource({
                "64, 5, -5", "64, -2147483648, 2147483647", "65, -7, 7", "65, -2147483648, 2147483647",
                "66, 5, -6", "66, -1, 0", "67, 0, 0", "67, -5, 0", "67, 1, 0", "67, 1024, 10", "67, 1023, 9",
                "67, 2147483647, 30"
        })
        @DisplayName("unary operators: negation, absolute value, not and the most significant bit")
        void unary(int operator, long operand, long expected) {
            assertEquals(expected, operate(operator, operand));
        }

        @ParameterizedTest(name = "{1} op {0} {2} is {3}")
        @CsvSource({
                "128, 3, 4, 7", "128, 2147483647, 1, 2147483647", "128, -2147483648, -1, -2147483648",
                "129, 3, 4, -1", "129, -2147483648, 1, -2147483648", "129, 2147483647, -1, 2147483647",
                "130, -6, 7, -42", "130, 65536, 65536, 2147483647", "130, -65536, 65536, -2147483648",
                "131, 7, 2, 3", "131, -7, 2, -3", "131, 9, 0, 9", "131, -2147483648, -1, 2147483647",
                "132, 12, 10, 8", "133, 12, 10, 14", "134, 12, 10, 6",
                "135, 0, 5, 0", "135, 0, 0, 0", "135, 1, -3, 1", "135, -1, 3, -1", "135, -1, 4, 1", "135, -1, -3, -1",
                "135, 2, 0, 1", "135, 2, 1, 2", "135, 2, -1, 0", "135, 2, 10, 1024", "135, 2, 31, 2147483647",
                "135, 2, 40, 2147483647", "135, -2, 31, -2147483648", "135, -2, 33, -2147483648",
                "135, -2, 32, 2147483647", "135, -3, 3, -27", "135, 10, 9, 1000000000",
                "136, 3, -4, -4", "137, 3, -4, 3"
        })
        @DisplayName("binary operators, saturating at the 32-bit bounds")
        void binary(int operator, long left, long right, long expected) {
            assertEquals(expected, operate(operator, left, right));
        }

        @Test
        @DisplayName("evaluate libavif's bit depth extension recipes over input samples")
        void recipes() {
            // (base << 8) | hidden, base << 4 + hidden >> 4, base << 4 + hidden - 128
            var eightEight = parse(2, 2, 0, 256, 1, 130, 2, 133);
            var twelveFour = parse(2, 2, 0, 16, 1, 130, 2, 0, 16, 131, 128);
            var overlap = parse(2, 2, 0, 16, 1, 130, 2, 128, 0, 128, 129);
            for (var base = 0; base < 4096; base += 37) {
                for (var hidden = 0; hidden < 256; hidden += 13) {
                    if (base < 256) {
                        assertEquals((base << 8) | hidden, evaluate(eightEight, base, hidden));
                    }
                    assertEquals((base << 4) + (hidden >> 4), evaluate(twelveFour, base, hidden));
                    assertEquals((base << 4) + hidden - 128, evaluate(overlap, base, hidden));
                }
            }
        }
    }

    @Nested
    @DisplayName("intermediate widths")
    class Widths {
        @Test
        @DisplayName("8-bit intermediates saturate operands, inputs and results at -128 and 127")
        void eight() {
            assertEquals(127, evaluate(parse(1, 0, 0, 100, 0, 100, 128)));
            assertEquals(-128, evaluate(parse(1, 0, 0, -100, 0, 100, 129)));
            assertEquals(127, evaluate(parse(1, 0, 1), 200));
            assertEquals(127, evaluate(parse(1, 0, 0, -128, 64)));
            assertEquals(-1, evaluate(parse(1, 0, 0, 255)));
        }

        @Test
        @DisplayName("16-bit intermediates saturate at -32768 and 32767 and read 16-bit constants")
        void sixteen() {
            assertEquals(32767, evaluate(parse(1, 1, 0, 30000, 0, 10000, 128)));
            assertEquals(-32768, evaluate(parse(1, 1, 0, -300, 0, 300, 130)));
            assertEquals(-2, evaluate(parse(1, 1, 0, 0xFFFE)));
            assertEquals(32767, evaluate(parse(1, 1, 1), 65535));
        }

        @Test
        @DisplayName("64-bit intermediates saturate at the long bounds without overflowing")
        void sixtyFour() {
            assertEquals(Long.MAX_VALUE, evaluate(parse(1, 3, 0, Long.MAX_VALUE, 0, 1, 128)));
            assertEquals(Long.MIN_VALUE, evaluate(parse(1, 3, 0, Long.MIN_VALUE, 0, 1, 129)));
            assertEquals(Long.MAX_VALUE, evaluate(parse(1, 3, 0, 1L << 40, 0, 1L << 40, 130)));
            assertEquals(Long.MIN_VALUE, evaluate(parse(1, 3, 0, -(1L << 40), 0, 1L << 40, 130)));
            assertEquals(Long.MAX_VALUE, evaluate(parse(1, 3, 0, 3, 0, 40, 135)));
            assertEquals(4052555153018976267L, evaluate(parse(1, 3, 0, 3, 0, 39, 135)));
            assertEquals(Long.MAX_VALUE, evaluate(parse(1, 3, 0, Long.MIN_VALUE, 64)));
            assertEquals(Long.MAX_VALUE, evaluate(parse(1, 3, 0, Long.MIN_VALUE, 0, -1, 131)));
            assertEquals(62, evaluate(parse(1, 3, 0, Long.MAX_VALUE, 67)));
            assertEquals(1L << 33, evaluate(parse(1, 3, 0, 1L << 32, 0, 2, 130)));
        }
    }

    @Nested
    @DisplayName("payload validation")
    class Validation {
        @Test
        @DisplayName("an unknown version or a reserved token is unsupported")
        void unsupported() {
            var payload = HeifBuilder.sato(2, 1);
            payload[0] |= 0x40;
            assertThrows(MediaException.Unsupported.class, () -> HeifSampleTransform.parse(payload, 1));
            for (var token : new int[]{33, 63, 68, 127, 138, 255}) {
                assertThrows(MediaException.Unsupported.class, () -> parse(1, 2, 1, token), "token " + token);
            }
        }

        @Test
        @DisplayName("a reserved token is unsupported whatever follows it, as its operands and payload are unknown")
        void reservedEndsParse() {
            // a future nullary operator, then a binary operator the stack check alone would reject
            assertThrows(MediaException.Unsupported.class, () -> parse(1, 2, 1, 200, 128));
            assertThrows(MediaException.Unsupported.class, () -> parse(1, 2, 40));
            // a reserved token followed by bytes of its own payload, read as trailing data otherwise
            var payload = HeifBuilder.sato(2, 1, 250);
            var extended = Arrays.copyOf(payload, payload.length + 3);
            assertThrows(MediaException.Unsupported.class, () -> HeifSampleTransform.parse(extended, 1));
            // a malformed token before the reserved one is still malformed
            assertThrows(MediaException.Malformed.class, () -> parse(1, 2, 2, 200));
        }

        @Test
        @DisplayName("malformed expressions and payloads are rejected")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> HeifSampleTransform.parse(new byte[]{2, 0}, 1));
            assertThrows(MediaException.Malformed.class, () -> parse(2, 2, 3));
            assertThrows(MediaException.Malformed.class, () -> parse(2, 2, 1, 2));
            assertThrows(MediaException.Malformed.class, () -> parse(2, 2, 1, 128));
            assertThrows(MediaException.Malformed.class, () -> parse(1, 2, 64));
            assertThrows(MediaException.Malformed.class, () -> parse(0, 2, 0, 1));
            assertThrows(MediaException.Malformed.class, () -> parse(33, 2, 1));
            var trailing = HeifBuilder.sato(2, 1);
            var longer = Arrays.copyOf(trailing, trailing.length + 1);
            assertThrows(MediaException.Malformed.class, () -> HeifSampleTransform.parse(longer, 1));
            var constant = HeifBuilder.sato(2, 0, 7);
            var truncated = Arrays.copyOf(constant, constant.length - 1);
            assertThrows(MediaException.Malformed.class, () -> HeifSampleTransform.parse(truncated, 1));
            assertThrows(MediaException.Malformed.class, () -> HeifSampleTransform.parse(new byte[]{2}, 1));
        }

        @Test
        @DisplayName("an expression reads the inputs it names, numbered from 1")
        void inputs() {
            var transform = parse(3, 2, 3, 1, 129);
            assertEquals(3, transform.inputs());
            assertEquals(30 - 10, evaluate(transform, 10, 20, 30));
            assertEquals(32, transform.intermediateBits());
        }
    }

    @Nested
    @DisplayName("application to pictures")
    class Application {
        private static Yuv16Picture random16(Random random, int width, int height, ChromaFormat format, int depth,
                                             boolean alpha) {
            var picture = Yuv16Picture.allocate(width, height, format, depth, alpha, ColorInfo.JPEG);
            fill(random, picture.y(), depth);
            if (format.hasChroma()) {
                fill(random, picture.u(), depth);
                fill(random, picture.v(), depth);
            }
            if (alpha) {
                fill(random, picture.alpha(), depth);
            }
            return picture;
        }

        private static YuvPicture random8(Random random, int width, int height, ChromaFormat format, boolean alpha) {
            var picture = YuvPicture.allocate(width, height, format, alpha, ColorInfo.JPEG);
            for (var plane : alpha ? List.of(picture.y(), picture.u(), picture.v(), picture.alpha())
                    : List.of(picture.y(), picture.u(), picture.v())) {
                random.nextBytes(plane);
            }
            return picture;
        }

        private static void fill(Random random, short[] plane, int depth) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = (short) random.nextInt(1 << depth);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"YUV420", "YUV422", "YUV444"})
        @DisplayName("compute every plane, alpha included, from the samples at the same position")
        void planes(ChromaFormat format) {
            var random = new Random(7);
            var base = random16(random, 37, 21, format, 12, true);
            var hidden = random8(random, 37, 21, format, true);
            var transform = parse(2, 2, 0, 16, 1, 130, 2, 128, 0, 128, 129);
            var output = assertInstanceOf(Yuv16Picture.class,
                    transform.apply(List.of(base, hidden), 16, ColorInfo.JPEG, false));
            assertEquals(16, output.bitDepth());
            assertTrue(output.hasAlpha());
            assertEquals(format, output.chromaFormat());
            check(base.y(), hidden.y(), output.y(), base.y().length);
            check(base.u(), hidden.u(), output.u(), base.u().length);
            check(base.v(), hidden.v(), output.v(), base.v().length);
            check(base.alpha(), hidden.alpha(), output.alpha(), base.alpha().length);
        }

        private static void check(short[] base, byte[] hidden, short[] output, int length) {
            for (var i = 0; i < length; i++) {
                var expected = Math.clamp(((base[i] & 0xFFFF) << 4) + (hidden[i] & 0xFF) - 128, 0, 65535);
                assertEquals(expected, output[i] & 0xFFFF, "sample " + i);
            }
        }

        @Test
        @DisplayName("clamp to the output depth, and to the limited range of the luma and chroma planes on request")
        void clamping() {
            var random = new Random(11);
            var input = random8(random, 16, 16, ChromaFormat.YUV420, true);
            var transform = parse(1, 2, 0, 256, 1, 130);
            var tenBits = assertInstanceOf(Yuv16Picture.class,
                    transform.apply(List.of(input), 10, ColorInfo.JPEG, false));
            for (var i = 0; i < input.y().length; i++) {
                assertEquals(Math.min(1023, (input.y()[i] & 0xFF) * 256), tenBits.y()[i]);
            }
            var limited = assertInstanceOf(Yuv16Picture.class,
                    transform.apply(List.of(input), 16, ColorInfo.JPEG, true));
            for (var i = 0; i < input.y().length; i++) {
                assertEquals(Math.clamp((input.y()[i] & 0xFF) * 256, 4096, 60160), limited.y()[i] & 0xFFFF);
                assertEquals((input.alpha()[i] & 0xFF) * 256, limited.alpha()[i] & 0xFFFF);
            }
            for (var i = 0; i < input.u().length; i++) {
                assertEquals(Math.clamp((input.u()[i] & 0xFF) * 256, 4096, 61440), limited.u()[i] & 0xFFFF);
            }
            var eight = assertInstanceOf(YuvPicture.class, parse(1, 2, 1, 0, 3, 128).apply(List.of(input), 8,
                    ColorInfo.JPEG, false));
            assertEquals(Math.min(255, (input.y()[5] & 0xFF) + 3), eight.y()[5] & 0xFF);
        }

        @Test
        @DisplayName("produce the same planes on any number of helper bands")
        void deterministic() {
            var random = new Random(3);
            var a = random16(random, 640, 480, ChromaFormat.MONOCHROME, 12, false);
            var b = random16(random, 640, 480, ChromaFormat.MONOCHROME, 12, false);
            var transform = parse(2, 2, 1, 2, 136, 0, 3, 130);
            var output = assertInstanceOf(Yuv16Picture.class, transform.apply(List.of(a, b), 14, ColorInfo.JPEG, false));
            assertFalse(output.hasAlpha());
            for (var i = 0; i < a.y().length; i++) {
                assertEquals(Math.min(a.y()[i], b.y()[i]) * 3, output.y()[i]);
            }
        }

        @Test
        @DisplayName("reject inputs that differ in size, layout or alpha, or that are not planar")
        void mismatched() {
            var random = new Random(5);
            var transform = parse(2, 2, 1, 2, 128);
            var base = random8(random, 16, 16, ChromaFormat.YUV420, false);
            assertThrows(MediaException.Malformed.class, () -> transform.apply(List.of(base,
                    random8(random, 16, 18, ChromaFormat.YUV420, false)), 8, ColorInfo.JPEG, false));
            assertThrows(MediaException.Malformed.class, () -> transform.apply(List.of(base,
                    random8(random, 16, 16, ChromaFormat.YUV444, false)), 8, ColorInfo.JPEG, false));
            assertThrows(MediaException.Unsupported.class, () -> transform.apply(List.of(base,
                    random8(random, 16, 16, ChromaFormat.YUV420, true)), 8, ColorInfo.JPEG, false));
            Picture packed = RgbaPicture.allocate(16, 16, false);
            assertThrows(MediaException.Unsupported.class, () -> transform.apply(List.of(base, packed), 8,
                    ColorInfo.JPEG, false));
            assertThrows(MediaException.Malformed.class, () -> transform.apply(List.of(base), 8, ColorInfo.JPEG,
                    false));
        }
    }
}
