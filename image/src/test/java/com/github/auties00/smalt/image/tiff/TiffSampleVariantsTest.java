package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.tiff.internal.SampleConverter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the rarer sample layouts: BitsPerSample differing between samples, complex samples (decoded to their
 * magnitude), 24-bit floating point (against values imagecodecs 2026.10.10's {@code float24_decode} reports and
 * an independent decoding of every bit pattern), and floating-point and other-depth L*a*b* (against the
 * equivalent 16-bit codes, whose rendering the libtiff tests check). Expected values are computed here.
 */
@DisplayName("TIFF sample variants")
class TiffSampleVariantsTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();
    private static final ImageDecoderOptions KEEP = ImageDecoderOptions.builder().keepChannels(true).build();
    private static final int WIDTH = 13;
    private static final int HEIGHT = 7;

    static long[] random(int count, int[] widths, long seed) {
        var random = new Random(seed);
        var values = new long[count];
        for (var i = 0; i < count; i++) {
            values[i] = random.nextLong(1L << widths[i % widths.length]);
        }
        return values;
    }

    static int scale8(long value, int width) {
        var max = (1L << width) - 1;
        return (int) ((value * 255 + (max >> 1)) / max);
    }

    static int scale16(long value, int width) {
        var max = (1L << width) - 1;
        return (int) ((value * 65535 + (max >> 1)) / max);
    }

    @Nested
    @DisplayName("BitsPerSample differing between samples")
    class Mixed {
        @ParameterizedTest(name = "{0}, little-endian {1}")
        @CsvSource({"5 6 5, true", "5 6 5, false", "4 4 4 4, true", "2 3 3, false", "1 7 8, true"})
        @DisplayName("narrow samples pack high-order bit first and scale to 8 bits on their own")
        void narrow(String spec, boolean little) {
            var widths = java.util.Arrays.stream(spec.split(" ")).mapToInt(Integer::parseInt).toArray();
            var samples = widths.length;
            var values = random(WIDTH * HEIGHT * samples, widths, spec.hashCode());
            var file = TiffFiles.of(WIDTH, HEIGHT, samples, 8).bits(widths)
                    .data(TiffFiles.packMixed(values, WIDTH, HEIGHT, widths, little));
            if (samples == 4) {
                file.extra(2);
            }
            if (!little) {
                file.bigEndian();
            }
            var pixels = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(file.bytes())).picture());
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                for (var k = 0; k < samples; k++) {
                    assertEquals(scale8(values[i * samples + k], widths[k]), pixels[i * 4 + k] & 0xFF,
                            "pixel " + i + " sample " + k);
                }
            }
            try (var pages = TiffDecoder.openPages(MediaSource.of(file.bytes()))) {
                assertEquals(widths[1], pages.page(0).bitsPerSample(1));
            }
        }

        @Test
        @DisplayName("wider samples scale to 16 bits, signed ones offset first, in strips, planes and the predictor")
        void wide() {
            int[] widths = {8, 16, 8, 16};
            var values = random(WIDTH * HEIGHT * 4, widths, 3);
            var interleaved = TiffFiles.packMixed(values, WIDTH, HEIGHT, widths, true);
            var file = TiffFiles.of(WIDTH, HEIGHT, 4, 8).bits(widths).extra(2).data(interleaved).bytes();
            var expected = expected16(values, widths, false);
            var wide = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(file), WIDE).picture();
            assertArrayEquals(expected, wide.pixels());
            // separate planes, each of its own width
            var planes = new byte[4][];
            for (var p = 0; p < 4; p++) {
                var plane = new long[WIDTH * HEIGHT];
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = values[i * 4 + p];
                }
                planes[p] = TiffFiles.pack(plane, WIDTH, HEIGHT, 1, widths[p], true);
            }
            var planar = TiffFiles.of(WIDTH, HEIGHT, 4, 8).bits(widths).extra(2).planar().chunks(planes).bytes();
            assertArrayEquals(expected, ((Rgba16Picture) TiffDecoder.decode(MediaSource.of(planar), WIDE).picture())
                    .pixels());
            // the horizontal predictor, each sample predicted at its own width
            var predicted = interleaved.clone();
            var pixelBytes = 6;
            var rowBytes = WIDTH * pixelBytes;
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = WIDTH - 1; x > 0; x--) {
                    var at = y * rowBytes + x * pixelBytes;
                    var previous = at - pixelBytes;
                    predicted[at] -= predicted[previous];
                    put16(predicted, at + 1, get16(predicted, at + 1) - get16(predicted, previous + 1));
                    predicted[at + 3] -= predicted[previous + 3];
                    put16(predicted, at + 4, get16(predicted, at + 4) - get16(predicted, previous + 4));
                }
            }
            var withPredictor = TiffFiles.of(WIDTH, HEIGHT, 4, 8).bits(widths).extra(2).tag(259, 8).tag(317, 2)
                    .chunks(TiffYCbCrTest.deflate(predicted)).bytes();
            assertArrayEquals(expected, ((Rgba16Picture) TiffDecoder.decode(MediaSource.of(withPredictor), WIDE)
                    .picture()).pixels());
            // signed samples
            var signed = TiffFiles.of(WIDTH, HEIGHT, 4, 8).bits(widths).extra(2).format(2).data(interleaved).bytes();
            assertArrayEquals(expected16(values, widths, true),
                    ((Rgba16Picture) TiffDecoder.decode(MediaSource.of(signed), WIDE).picture()).pixels());
            // the 8-bit output is the 16-bit one reduced
            var narrow = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture();
            for (var i = 0; i < expected.length; i++) {
                assertEquals(((expected[i] & 0xFFFF) * 255 + 32767) / 65535, narrow.pixels()[i] & 0xFF);
            }
        }

        @Test
        @DisplayName("channel pictures keep extra samples of other sizes, and floating-point sizes may differ")
        void channelsAndFloats() {
            int[] widths = {12, 12, 12, 16};
            var values = random(WIDTH * HEIGHT * 4, widths, 4);
            var file = TiffFiles.of(WIDTH, HEIGHT, 4, 8).bits(widths).extra(0)
                    .data(TiffFiles.packMixed(values, WIDTH, HEIGHT, widths, false)).bigEndian().bytes();
            var channels = (Channel16Picture) TiffDecoder.decode(MediaSource.of(file),
                    KEEP.toBuilder().highBitDepth(true).build()).picture();
            for (var i = 0; i < values.length; i++) {
                assertEquals(scale16(values[i], widths[i % 4]), channels.pixels()[i] & 0xFFFF);
            }
            // 16-bit floating-point color, 32-bit floating-point alpha
            var buffer = ByteBuffer.allocate(WIDTH * HEIGHT * 10).order(ByteOrder.LITTLE_ENDIAN);
            var random = new Random(5);
            var reals = new float[WIDTH * HEIGHT * 4];
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                for (var k = 0; k < 3; k++) {
                    var half = Float.floatToFloat16(random.nextFloat());
                    reals[i * 4 + k] = Float.float16ToFloat(half);
                    buffer.putShort(half);
                }
                reals[i * 4 + 3] = random.nextFloat();
                buffer.putFloat(reals[i * 4 + 3]);
            }
            var floats = TiffFiles.of(WIDTH, HEIGHT, 4, 8).bits(16, 16, 16, 32).format(3).extra(2)
                    .data(buffer.array()).bytes();
            var kept = (ChannelFloatPicture) TiffDecoder.decode(MediaSource.of(floats), KEEP).picture();
            assertArrayEquals(reals, kept.pixels());
            var wide = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(floats), WIDE).picture();
            for (var i = 0; i < reals.length; i++) {
                assertEquals(Math.round(reals[i] * 65535.0), wide.pixels()[i] & 0xFFFF, 1);
            }
        }

        @Test
        @DisplayName("JPEG and the floating-point predictor reject samples of different sizes")
        void unsupported() {
            var jpeg = TiffFiles.of(4, 4, 3, 8).bits(8, 16, 8).tag(259, 7).data(new byte[64]).bytes();
            assertEquals("tiff sample layout", assertThrows(MediaException.Unsupported.class,
                    () -> TiffDecoder.decode(MediaSource.of(jpeg))).format());
            var floating = TiffFiles.of(4, 4, 2, 8).bits(16, 32).format(3).tag(317, 3).data(new byte[96]).bytes();
            assertEquals("tiff predictor", assertThrows(MediaException.Unsupported.class,
                    () -> TiffDecoder.decode(MediaSource.of(floating))).format());
        }

        private static short[] expected16(long[] values, int[] widths, boolean signed) {
            var expected = new short[values.length];
            for (var i = 0; i < values.length; i++) {
                var width = widths[i % widths.length];
                var value = signed ? values[i] ^ (1L << (width - 1)) : values[i];
                expected[i] = (short) scale16(value, width);
            }
            return expected;
        }
    }

    @Nested
    @DisplayName("complex samples")
    class Complex {
        @ParameterizedTest(name = "{0}-bit parts")
        @ValueSource(ints = {8, 16, 32})
        @DisplayName("complex integers decode to their magnitude, clamped and scaled from the part's signed range")
        void integers(int half) {
            var random = new Random(half);
            var count = WIDTH * HEIGHT;
            var parts = new long[count * 2];
            var limit = (1L << (half - 1)) - 1;
            for (var i = 0; i < parts.length; i++) {
                parts[i] = random.nextLong(-limit - 1, limit + 1);
            }
            parts[0] = -limit - 1;
            parts[1] = -limit - 1;
            var raw = new long[parts.length];
            for (var i = 0; i < parts.length; i++) {
                raw[i] = half == 64 ? parts[i] : parts[i] & ((1L << half) - 1);
            }
            for (var little : new boolean[]{true, false}) {
                var file = TiffFiles.of(WIDTH, HEIGHT, 1, half * 2).format(5)
                        .data(TiffFiles.pack(raw, WIDTH * 2, HEIGHT, 1, half, little));
                if (!little) {
                    file.bigEndian();
                }
                var bytes = file.bytes();
                var wide = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(bytes), WIDE).picture();
                for (var i = 0; i < count; i++) {
                    var magnitude = Math.min(Math.rint(Math.hypot(parts[i * 2], parts[i * 2 + 1])), limit);
                    assertEquals(Math.round(magnitude * 65535 / limit), wide.pixels()[i * 4] & 0xFFFF, "pixel " + i);
                }
                assertEquals(65535, wide.pixels()[0] & 0xFFFF);
                try (var pages = TiffDecoder.openPages(MediaSource.of(bytes))) {
                    assertEquals(TiffSampleFormat.COMPLEX_INTEGER, pages.page(0).sampleFormat());
                }
            }
        }

        @ParameterizedTest(name = "{0}-bit parts")
        @ValueSource(ints = {16, 32, 64})
        @DisplayName("complex floating point decodes to its magnitude, unscaled in channel pictures")
        void floats(int half) {
            var random = new Random(half);
            var count = WIDTH * HEIGHT;
            var buffer = ByteBuffer.allocate(count * half / 4).order(ByteOrder.BIG_ENDIAN);
            var magnitudes = new float[count];
            for (var i = 0; i < count; i++) {
                double re = random.nextFloat() - 0.5;
                double im = random.nextFloat() - 0.5;
                switch (half) {
                    case 16 -> {
                        var r = Float.floatToFloat16((float) re);
                        var m = Float.floatToFloat16((float) im);
                        re = Float.float16ToFloat(r);
                        im = Float.float16ToFloat(m);
                        buffer.putShort(r).putShort(m);
                    }
                    case 32 -> {
                        re = (float) re;
                        im = (float) im;
                        buffer.putFloat((float) re).putFloat((float) im);
                    }
                    default -> buffer.putDouble(re).putDouble(im);
                }
                magnitudes[i] = (float) Math.hypot(re, im);
            }
            var file = TiffFiles.of(WIDTH, HEIGHT, 1, half * 2).format(6).bigEndian().data(buffer.array()).bytes();
            var kept = (ChannelFloatPicture) TiffDecoder.decode(MediaSource.of(file), KEEP).picture();
            assertArrayEquals(magnitudes, kept.pixels());
            var wide = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(file), WIDE).picture();
            for (var i = 0; i < count; i++) {
                assertEquals(Math.round(Math.min(magnitudes[i], 1) * 65535.0), wide.pixels()[i * 4] & 0xFFFF, 1);
            }
        }

        @Test
        @DisplayName("the horizontal predictor works on whole complex samples")
        void predictor() {
            var random = new Random(9);
            var count = WIDTH * HEIGHT;
            var raw = new byte[count * 4];
            random.nextBytes(raw);
            var predicted = raw.clone();
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = WIDTH - 1; x > 0; x--) {
                    var at = (y * WIDTH + x) * 4;
                    var value = ByteBuffer.wrap(predicted, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                            - ByteBuffer.wrap(predicted, at - 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    ByteBuffer.wrap(predicted, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
                }
            }
            var plain = TiffFiles.of(WIDTH, HEIGHT, 1, 32).format(5).data(raw).bytes();
            var withPredictor = TiffFiles.of(WIDTH, HEIGHT, 1, 32).format(5).tag(259, 8).tag(317, 2)
                    .chunks(TiffYCbCrTest.deflate(predicted)).bytes();
            assertArrayEquals(((Rgba16Picture) TiffDecoder.decode(MediaSource.of(plain), WIDE).picture()).pixels(),
                    ((Rgba16Picture) TiffDecoder.decode(MediaSource.of(withPredictor), WIDE).picture()).pixels());
            var floatingPredictor = TiffFiles.of(4, 4, 1, 64).format(6).tag(317, 3).data(new byte[128]).bytes();
            assertEquals("tiff predictor", assertThrows(MediaException.Unsupported.class,
                    () -> TiffDecoder.decode(MediaSource.of(floatingPredictor))).format());
        }
    }

    @Nested
    @DisplayName("24-bit floating point")
    class Float24 {
        @Test
        @DisplayName("matches the values imagecodecs float24_decode reports")
        void imagecodecs() {
            // pattern (big-endian), value from imagecodecs 2026.10.10
            assertEquals(1.0F, SampleConverter.float24ToFloat(0x3F0000));
            assertEquals(2.0F, SampleConverter.float24ToFloat(0x400000));
            assertEquals(0.75F, SampleConverter.float24ToFloat(0x3E8000));
            assertEquals(3.3087225e-24F, SampleConverter.float24ToFloat(0x000001));
            assertEquals(1.0842022e-19F, SampleConverter.float24ToFloat(0x008000));
            assertEquals(2.1684043e-19F, SampleConverter.float24ToFloat(0x010000));
            assertEquals(Float.POSITIVE_INFINITY, SampleConverter.float24ToFloat(0x7F0000));
            assertEquals(Float.NEGATIVE_INFINITY, SampleConverter.float24ToFloat(0xFF0000));
            assertTrue(Float.isNaN(SampleConverter.float24ToFloat(0x7F0001)));
            assertEquals(3.1416016F, SampleConverter.float24ToFloat(0x409220));
            assertEquals(9.9989592e-21F, SampleConverter.float24ToFloat(0x000BCE));
            assertEquals(0.5F, SampleConverter.float24ToFloat(0x3E0000));
            assertEquals(65504.0F, SampleConverter.float24ToFloat(0x4EFFC0));
            assertEquals(-2.5F, SampleConverter.float24ToFloat(0xC04000));
            assertEquals(1.6999885e-19F, SampleConverter.float24ToFloat(0x00C8B3));
        }

        @Test
        @DisplayName("decodes every bit pattern as sign, 7-bit exponent biased by 63 and 16-bit fraction")
        void exhaustive() {
            for (var bits = 0; bits < 1 << 24; bits++) {
                var sign = (bits >>> 23) != 0 ? -1.0 : 1.0;
                var exponent = bits >>> 16 & 0x7F;
                var fraction = bits & 0xFFFF;
                var actual = SampleConverter.float24ToFloat(bits);
                if (exponent == 0x7F) {
                    assertEquals(fraction == 0, Float.isInfinite(actual), Integer.toHexString(bits));
                    assertEquals(fraction != 0, Float.isNaN(actual), Integer.toHexString(bits));
                    continue;
                }
                var expected = exponent == 0 ? sign * Math.scalb((double) fraction, -78)
                        : sign * Math.scalb(1 + fraction / 65536.0, exponent - 63);
                if (actual != (float) expected || (expected == 0 && Math.copySign(1, actual) != sign)) {
                    fail(Integer.toHexString(bits) + ": expected " + expected + " got " + actual);
                }
            }
        }

        @ParameterizedTest(name = "predictor {0}")
        @ValueSource(ints = {1, 3})
        @DisplayName("decodes 24-bit floating-point pages, with the floating-point predictor")
        void pages(int predictor) {
            var random = new Random(predictor);
            var count = WIDTH * HEIGHT * 2;
            var patterns = new int[count];
            var expected = new float[count];
            for (var i = 0; i < count; i++) {
                // exponents 55..63: values from about 0.004 to 2
                patterns[i] = (random.nextInt(9) + 55) << 16 | random.nextInt(1 << 16);
                expected[i] = SampleConverter.float24ToFloat(patterns[i]);
            }
            var rowBytes = WIDTH * 2 * 3;
            var data = new byte[rowBytes * HEIGHT];
            for (var i = 0; i < count; i++) {
                data[i * 3] = (byte) (patterns[i] >>> 16);
                data[i * 3 + 1] = (byte) (patterns[i] >>> 8);
                data[i * 3 + 2] = (byte) patterns[i];
            }
            var file = TiffFiles.of(WIDTH, HEIGHT, 2, 24).format(3).extra(2).bigEndian();
            if (predictor == 3) {
                var coded = new byte[data.length];
                for (var y = 0; y < HEIGHT; y++) {
                    var row = y * rowBytes;
                    var values = WIDTH * 2;
                    // byte planes, most significant first, then differences from the byte one sample before
                    for (var s = 0; s < values; s++) {
                        for (var b = 0; b < 3; b++) {
                            coded[row + b * values + s] = data[row + s * 3 + b];
                        }
                    }
                    for (var i = rowBytes - 1; i >= 2; i--) {
                        coded[row + i] -= coded[row + i - 2];
                    }
                }
                file.tag(259, 8).tag(317, 3).chunks(TiffYCbCrTest.deflate(coded));
            } else {
                file.data(data);
            }
            var bytes = file.bytes();
            var kept = (ChannelFloatPicture) TiffDecoder.decode(MediaSource.of(bytes), KEEP).picture();
            assertArrayEquals(expected, kept.pixels());
            var wide = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(bytes), WIDE).picture();
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                assertEquals(Math.round(Math.min(expected[i * 2], 1) * 65535.0), wide.pixels()[i * 4] & 0xFFFF, 1);
                assertEquals(Math.round(Math.min(expected[i * 2 + 1], 1) * 65535.0), wide.pixels()[i * 4 + 3] & 0xFFFF,
                        1);
            }
        }
    }

    @Nested
    @DisplayName("L*a*b* encodings")
    class Lab {
        static byte[] lab16(int photometric, long[] codes) {
            return TiffFiles.of(WIDTH, HEIGHT, 3, 16).photometric(photometric)
                    .data(TiffFiles.pack(codes, WIDTH, HEIGHT, 3, 16, true)).bytes();
        }

        static byte[] decode(byte[] file) {
            return RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture());
        }

        @ParameterizedTest(name = "photometric {0}")
        @ValueSource(ints = {8, 9, 10})
        @DisplayName("floating-point L*, a* and b* render as their 16-bit codes")
        void floats(int photometric) {
            var codes = TiffFiles.pattern(WIDTH, HEIGHT, 3, 65535, photometric);
            var buffer = ByteBuffer.allocate(codes.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < codes.length; i++) {
                var channel = i % 3;
                var code = codes[i];
                double value = switch (photometric) {
                    case 8 -> channel == 0 ? code * 100.0 / 65535 : (short) code / 256.0;
                    case 9 -> channel == 0 ? Math.min(code, 0xFF00) * 100.0 / 65280 : code / 256.0 - 128;
                    default -> {
                        double[] decode = {0, 100, -85, 85, -75, 125};
                        yield decode[channel * 2] + code * (decode[channel * 2 + 1] - decode[channel * 2]) / 65535;
                    }
                };
                buffer.putFloat((float) value);
            }
            if (photometric == 9) {
                for (var i = 0; i < codes.length; i += 3) {
                    codes[i] = Math.min(codes[i], 0xFF00);
                }
            }
            var floats = TiffFiles.of(WIDTH, HEIGHT, 3, 32).photometric(photometric).format(3).data(buffer.array())
                    .bytes();
            assertArrayEquals(decode(lab16(photometric, codes)), decode(floats));
        }

        @ParameterizedTest(name = "photometric {0}, {1}-bit")
        @CsvSource({"8, 4", "8, 12", "8, 24", "8, 32", "9, 12", "9, 4", "10, 12", "10, 24"})
        @DisplayName("integer L*a*b* of other depths renders as the equivalent 16-bit codes")
        void depths(int photometric, int bits) {
            var max = (1L << bits) - 1;
            var values = TiffFiles.pattern(WIDTH, HEIGHT, 3, max, bits);
            var codes = new long[values.length];
            for (var i = 0; i < values.length; i++) {
                var channel = i % 3;
                var value = values[i];
                codes[i] = switch (photometric) {
                    case 8 -> channel == 0 ? Math.round(value * 65535.0 / max)
                            : Math.clamp(Math.round(signed(value, bits) * Math.scalb(1.0, 16 - bits)), -32768, 32767)
                            & 0xFFFF;
                    case 9 -> channel == 0 ? Math.round(value * 65280.0 / max)
                            : Math.clamp(Math.round(value * Math.scalb(1.0, 16 - bits)), 0, 65535);
                    default -> Math.round(value * 65535.0 / max);
                };
            }
            var file = TiffFiles.of(WIDTH, HEIGHT, 3, bits).photometric(photometric)
                    .data(TiffFiles.pack(values, WIDTH, HEIGHT, 3, bits, true)).bytes();
            assertArrayEquals(decode(lab16(photometric, codes)), decode(file));
        }

        @Test
        @DisplayName("an L*a*b* page keeps a floating-point alpha sample")
        void alpha() {
            var buffer = ByteBuffer.allocate(WIDTH * HEIGHT * 16).order(ByteOrder.LITTLE_ENDIAN);
            var random = new Random(2);
            var alphas = new float[WIDTH * HEIGHT];
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                buffer.putFloat(random.nextFloat() * 100).putFloat(random.nextFloat() * 60 - 30)
                        .putFloat(random.nextFloat() * 60 - 30);
                alphas[i] = random.nextFloat();
                buffer.putFloat(alphas[i]);
            }
            var file = TiffFiles.of(WIDTH, HEIGHT, 4, 32).photometric(8).format(3).extra(2).data(buffer.array())
                    .bytes();
            var picture = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture();
            assertTrue(picture.hasAlpha());
            for (var i = 0; i < alphas.length; i++) {
                var alpha16 = Math.round(alphas[i] * 65535.0);
                assertEquals((alpha16 * 255 + 32767) / 65535, picture.pixels()[i * 4 + 3] & 0xFF, 1);
            }
        }

        private static long signed(long value, int bits) {
            return value << (64 - bits) >> (64 - bits);
        }
    }

    @Test
    @DisplayName("damaged files of these layouts fail only with media exceptions")
    void damaged() {
        var random = new Random(12);
        var seeds = new byte[][]{
                TiffFiles.of(9, 5, 3, 8).bits(5, 6, 5).data(new byte[90]).bytes(),
                TiffFiles.of(9, 5, 1, 32).format(5).data(new byte[180]).bytes(),
                TiffFiles.of(9, 5, 1, 24).format(3).tag(317, 3).data(new byte[135]).bytes(),
                TiffFiles.of(9, 5, 3, 12).photometric(8).data(new byte[203]).bytes()};
        for (var seed : seeds) {
            for (var round = 0; round < 300; round++) {
                var data = seed.clone();
                for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                    data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                }
                try {
                    TiffDecoder.decode(MediaSource.of(data), random.nextBoolean() ? WIDE : KEEP);
                } catch (MediaException expected) {
                    // malformed, unsupported or over a limit
                }
            }
        }
    }

    private static int get16(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
    }

    private static void put16(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >>> 8);
    }
}
