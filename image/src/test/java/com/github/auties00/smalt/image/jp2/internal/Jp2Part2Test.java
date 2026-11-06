package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.HORIZONTAL;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.JOINT;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.Tree;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.VERTICAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the ISO/IEC 15444-2 codestream extensions against codestreams written from the equations of the standard:
 * the test transforms the samples forward with {@link Part2Forward}, codes the coefficients with
 * {@link Part2Writer}, and expects the decoder to give the samples back exactly (reversible paths) or within the
 * rounding of single-precision arithmetic (irreversible paths).
 */
@DisplayName("JPEG 2000 Part 2 codestream extensions")
class Jp2Part2Test {
    private static final MediaLimits LIMITS = MediaLimits.builder().maxChannels(16384).build();

    static long[] image(Random random, int count, long min, long max) {
        var samples = new long[count];
        for (var i = 0; i < count; i++) {
            samples[i] = min + (long) (random.nextDouble() * (max - min + 1));
        }
        return samples;
    }

    /** A smooth image with noise, closer to what wavelets see than white noise. */
    static long[] smooth(Random random, int width, int height, long min, long max) {
        var samples = new long[width * height];
        var span = max - min;
        var phase = random.nextDouble() * 6;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = 0.5 + 0.35 * Math.sin(x * 0.21 + phase) * Math.cos(y * 0.17 - phase)
                        + 0.1 * (random.nextDouble() - 0.5);
                samples[y * width + x] = min + Math.clamp(Math.round(value * span), 0, span);
            }
        }
        return samples;
    }

    static Jp2Image decode(byte[] codestream) {
        return decode(codestream, 0);
    }

    static Jp2Image decode(byte[] codestream, int reduction) {
        return Jp2Decoder.decodeChannels(MediaSource.of(codestream),
                Jp2DecoderOptions.builder().reduction(reduction).limits(LIMITS).build());
    }

    static long[] samples(Jp2Channel channel) {
        if (channel.wide()) {
            return channel.wideSamples();
        }
        return Arrays.stream(channel.samples()).asLongStream().toArray();
    }

    /** Transforms planes forward with a tree and kernel, after subtracting an offset from each sample. */
    static long[][] forward(Part2Writer writer, long[][] planes, long[] offsets, Tree tree, Part2Forward.Kernel kernel) {
        var result = new long[planes.length][];
        for (var c = 0; c < planes.length; c++) {
            var data = planes[c].clone();
            for (var i = 0; i < data.length; i++) {
                data[i] -= offsets[c];
            }
            var cx0 = writer.componentX0(c);
            var cy0 = writer.componentY0(c);
            Part2Forward.decompose(data, writer.componentWidth(c), 0, 0, cx0, cy0, cx0 + writer.componentWidth(c),
                    cy0 + writer.componentHeight(c), tree, kernel);
            result[c] = data;
        }
        return result;
    }

    static long[] levelShifts(Part2Writer writer) {
        var shifts = new long[writer.components()];
        for (var c = 0; c < shifts.length; c++) {
            shifts[c] = writer.signed[c] ? 0 : 1L << (writer.precisions[c] - 1);
        }
        return shifts;
    }

    static void assertChannels(long[][] expected, Jp2Image image) {
        assertEquals(expected.length, image.channels().size(), "channel count");
        for (var c = 0; c < expected.length; c++) {
            assertArrayEquals(expected[c], samples(image.channels().get(c)), "channel " + c);
        }
    }

    @Nested
    @DisplayName("test encoder")
    class Harness {
        @Test
        @DisplayName("the 5-3 lifting form decomposes like ISO/IEC 15444-1 equations F-9 and F-10")
        void lifting() {
            var random = new Random(1);
            for (var n = 1; n < 40; n++) {
                for (var i0 = -3; i0 < 3; i0++) {
                    var signal = image(random, n, -500, 500);
                    var a = signal.clone();
                    var b = signal.clone();
                    Part2Forward.forward53(a, i0);
                    Part2Forward.forward(b, i0, Part2Forward.LE_GALL);
                    assertArrayEquals(a, b, "n=" + n + " i0=" + i0);
                }
            }
        }

        @Test
        @DisplayName("the dyadic decomposition equals the encoder's forward 5-3 transform")
        void dyadic() {
            var random = new Random(2);
            var writer = new Part2Writer(37, 29, 1, 8, false);
            writer.x0 = 5;
            writer.y0 = 3;
            writer.levels = 3;
            var plane = image(random, 37 * 29, -128, 127);
            var expected = plane.clone();
            Part2Forward.decompose(expected, 37, 0, 0, 5, 3, 42, 32, Tree.dyadic(3), null);
            var coding = new ComponentCoding(4, 4, 4, 0, true, null, null);
            var component = new TileComponent(5, 3, 42, 32, coding,
                    new Quantization(Quantization.NONE, 2, new int[Quantization.MAX_BANDS],
                            new int[Quantization.MAX_BANDS]), 8, 4, true, LIMITS);
            var actual = Arrays.stream(plane).mapToInt(v -> (int) v).toArray();
            Wavelet.forward53(actual, 37, component);
            assertArrayEquals(expected, Arrays.stream(actual).asLongStream().toArray());
        }

        @Test
        @DisplayName("a Part 1 codestream it writes decodes losslessly")
        void lossless() {
            var random = new Random(3);
            var writer = new Part2Writer(41, 23, 2, 9, false);
            writer.rsiz = 0;
            writer.x0 = 3;
            writer.y0 = 6;
            writer.levels = 3;
            var planes = new long[][]{smooth(random, 41, 23, 0, 511), image(random, 41 * 23, 0, 511)};
            var codestream = writer.write(forward(writer, planes, levelShifts(writer), Tree.dyadic(3), null));
            assertChannels(planes, decode(codestream));
        }
    }

    @Nested
    @DisplayName("variable DC offset (annex B)")
    class DcOffset {
        @ParameterizedTest(name = "offset type {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("replaces the level shift with the signalled offsets")
        void offsets(int type) {
            var random = new Random(10 + type);
            var writer = new Part2Writer(30, 22, 2, 10, false);
            writer.rsiz |= Part2Segments.RSIZ_DCO;
            writer.levels = 2;
            var offsets = switch (type) {
                case 0 -> new double[]{200, 17};
                case 1 -> new double[]{-300, 700};
                default -> new double[]{100.6, -35.25};
            };
            writer.mainSegments.add(Part2Segments.dco(type, offsets));
            var planes = new long[][]{smooth(random, 30, 22, 0, 1023), smooth(random, 30, 22, 0, 1023)};
            var shifts = new long[2];
            for (var c = 0; c < 2; c++) {
                // the decoder rounds the sum of an integer coefficient and a fractional offset to nearest
                shifts[c] = Math.round(offsets[c]);
            }
            var codestream = writer.write(forward(writer, planes, shifts, Tree.dyadic(2), null));
            assertChannels(planes, decode(codestream));
        }

        @Test
        @DisplayName("a tile-part DCO overrides the main header's and results clamp to the component range")
        void override() {
            var random = new Random(14);
            var writer = new Part2Writer(16, 16, 1, 8, true);
            writer.rsiz |= Part2Segments.RSIZ_DCO;
            writer.levels = 1;
            writer.mainSegments.add(Part2Segments.dco(1, 1000));
            writer.tileSegments.add(Part2Segments.dco(1, 50));
            var plane = image(random, 256, -128, 127);
            var codestream = writer.write(forward(writer, new long[][]{plane}, new long[]{0}, Tree.dyadic(1), null));
            var expected = Arrays.stream(plane).map(v -> Math.clamp(v + 50, -128, 127)).toArray();
            assertChannels(new long[][]{expected}, decode(codestream));
        }

        @Test
        @DisplayName("a DCO segment is not ignored: without it the same codestream decodes differently")
        void notIgnored() {
            var random = new Random(15);
            var writer = new Part2Writer(16, 16, 1, 8, false);
            writer.rsiz |= Part2Segments.RSIZ_DCO;
            writer.mainSegments.add(Part2Segments.dco(0, 3));
            var plane = image(random, 256, 3, 255);
            var codestream = writer.write(forward(writer, new long[][]{plane}, new long[]{3}, Tree.dyadic(0), null));
            assertChannels(new long[][]{plane}, decode(codestream));
        }
    }

    @Nested
    @DisplayName("non-linear point transformations (annex K)")
    class NonLinear {
        private byte[] codestream(long[] plane, int precision, boolean signed, byte[]... segments) {
            var writer = new Part2Writer(24, 20, 1, precision, signed);
            writer.rsiz |= Part2Segments.RSIZ_NLT;
            writer.levels = 2;
            writer.mainSegments.addAll(Arrays.asList(segments));
            var shift = signed ? 0 : 1L << (precision - 1);
            return writer.write(forward(writer, new long[][]{plane}, new long[]{shift}, Tree.dyadic(2), null));
        }

        private static long round(double value) {
            return value < 0 ? -(long) Math.floor(-value + 0.5) : (long) Math.floor(value + 0.5);
        }

        @Test
        @DisplayName("gamma style follows equations K-3 to K-5, changing the bit depth")
        void gamma() {
            var random = new Random(20);
            var plane = smooth(random, 24, 20, 0, 4095);
            var e = 1 / 0.45;
            var s = 4.5;
            var t = 0.081;
            var a = 1.099;
            var b = 0.099;
            var data = codestream(plane, 12, false, Part2Segments.nltGamma(65535, 10, false, e, s, t, a, b));
            var image = decode(data);
            var channel = image.channels().getFirst();
            assertEquals(10, channel.precision());
            // the parameters as the segment stores them, 8.16 fixed point
            double[] stored = new double[5];
            var values = new double[]{e, s, t, a, b};
            for (var i = 0; i < 5; i++) {
                var integer = Math.floor(values[i]);
                stored[i] = integer + Math.round((values[i] - integer) * 65535) / 65535.0;
            }
            var fy = 4095.0;
            var fz = 1023.0;
            var ts = fy * stored[2];
            var ss = fy / fz * stored[1];
            var as = fy / StrictMath.pow(fz, stored[0]) * stored[3];
            var bs = fy * stored[4];
            var expected = new long[plane.length];
            for (var i = 0; i < plane.length; i++) {
                var y = (double) plane[i];
                double z;
                if (y < -ts) {
                    z = -StrictMath.pow((Math.abs(y) + bs) / as, 1 / stored[0]);
                } else if (y <= ts) {
                    z = y / ss;
                } else {
                    z = StrictMath.pow((y + bs) / as, 1 / stored[0]);
                }
                expected[i] = Math.clamp(round(z), 0, 1023);
            }
            assertArrayEquals(expected, samples(channel));
        }

        @Test
        @DisplayName("LUT style interpolates its table as equations K-6 to K-9 state, for signed input and output")
        void lookup() {
            var random = new Random(21);
            var plane = smooth(random, 24, 20, -512, 511);
            var table = new long[]{0, 900, 2000, 4095, 3000, 100};
            var dmin = 0x20000000L;
            var dmax = 0xE0000000L;
            var data = codestream(plane, 10, true, Part2Segments.nltLookup(0, 12, true, dmin, dmax, 12, table));
            var channel = decode(data).channels().getFirst();
            assertEquals(12, channel.precision());
            assertTrue(channel.signed());
            var min = dmin / 4294967295.0;
            var max = dmax / 4294967295.0;
            var delta = (max - min) / (table.length - 1);
            var expected = new long[plane.length];
            for (var i = 0; i < plane.length; i++) {
                var y = Math.clamp((plane[i] + 512) / 1023.0, min, max);
                var k = Math.min((int) Math.floor((y - min) / delta), table.length - 2);
                var tk = table[k] / 4095.0;
                var tk1 = table[k + 1] / 4095.0;
                var z = tk + (y - (min + k * delta)) / delta * (tk1 - tk);
                expected[i] = Math.clamp(round(z * 4095 - 2048), -2048, 2047);
            }
            assertArrayEquals(expected, samples(channel));
        }

        @Test
        @DisplayName("two's complement to sign-magnitude maps -1 to -2^(b-1) and keeps non-negative samples")
        void signMagnitude() {
            var random = new Random(22);
            var plane = image(random, 480, -128, 127);
            plane[0] = -1;
            plane[1] = -128;
            plane[2] = 0;
            var channel = decode(codestream(plane, 8, true, Part2Segments.nlt(0, 8, true, 3))).channels().getFirst();
            var expected = Arrays.stream(plane).map(v -> v >= 0 ? v : Math.min(-128 - v - 1, -1)).toArray();
            assertEquals(-128, expected[0]);
            assertEquals(-1, expected[1]);
            assertArrayEquals(expected, samples(channel));
        }

        @Test
        @DisplayName("a component's own NLT overrides the default, and type 0 disables a default")
        void precedence() {
            var random = new Random(23);
            var writer = new Part2Writer(16, 16, 3, 8, true);
            writer.rsiz |= Part2Segments.RSIZ_NLT;
            writer.mainSegments.add(Part2Segments.nlt(65535, 8, true, 3));
            writer.mainSegments.add(Part2Segments.nlt(1, 8, true, 0));
            writer.tileSegments.add(Part2Segments.nlt(2, 8, true, 0));
            var planes = new long[][]{image(random, 256, -128, 127), image(random, 256, -128, 127),
                    image(random, 256, -128, 127)};
            var data = writer.write(forward(writer, planes, new long[3], Tree.dyadic(0), null));
            var image = decode(data);
            var converted = Arrays.stream(planes[0]).map(v -> v >= 0 ? v : Math.min(-128 - v - 1, -1)).toArray();
            assertChannels(new long[][]{converted, planes[1], planes[2]}, image);
        }
    }

    @Nested
    @DisplayName("multiple component transformations (annex J)")
    class ComponentTransformations {
        /** Writes a reversible codestream whose codestream components are {@code components}. */
        private byte[] codestream(long[][] components, int precision, byte[]... segments) {
            var writer = new Part2Writer(20, 18, components.length, precision, true);
            writer.rsiz |= Part2Segments.RSIZ_MCT;
            writer.mct = 2;
            writer.levels = 2;
            writer.mainSegments.addAll(Arrays.asList(segments));
            return writer.write(forward(writer, components, new long[components.length], Tree.dyadic(2), null));
        }

        /**
         * Inverts the decoder's reversible decorrelation steps (equations J-4 to J-6) in reverse order: the forward
         * transform of J.3.1.1.4 with the sign the inverse requires.
         */
        private long[][] forwardDecorrelation(long[][] w, long[] t, long[] offsets) {
            var n = w.length;
            var length = w[0].length;
            var p = new long[n][length];
            for (var i = 0; i < n; i++) {
                for (var x = 0; x < length; x++) {
                    p[i][x] = w[i][x] - offsets[i];
                }
            }
            for (var x = 0; x < length; x++) {
                var last = t[n * n + n - 1];
                var magnitude = Math.abs(last);
                var sum = 0L;
                for (var i = 0; i < n - 1; i++) {
                    sum += t[n * n + i] * p[i][x];
                }
                // decoder: P' = sgn(t) * (P - floor((sum + |t| / 2) / |t|))
                p[n - 1][x] = Long.signum(last) * p[n - 1][x] + Math.floorDiv(2 * sum + magnitude, 2 * magnitude);
                for (var l = n - 1; l >= 0; l--) {
                    var target = n - 1 - l;
                    var scale = t[l * n + target];
                    var s = 0L;
                    for (var i = 0; i < n; i++) {
                        if (i != target) {
                            s += t[l * n + i] * p[i][x];
                        }
                    }
                    p[target][x] += Math.floorDiv(2 * s + scale, 2 * scale);
                }
            }
            return p;
        }

        @Test
        @DisplayName("a reversible decorrelation (SERM) round trips exactly, with offsets")
        void reversibleDecorrelation() {
            var random = new Random(30);
            var w = new long[][]{smooth(random, 20, 18, 0, 255), smooth(random, 20, 18, 0, 255),
                    smooth(random, 20, 18, 0, 255)};
            // rows l = 0..N: t_l0 .. t_l(N-1); scaling terms t_l(N-1-l) and t_N(N-1) are powers of two
            var t = new long[]{
                    3, -5, 4,
                    -1, 2, 7,
                    2, -6, 1,
                    1, 3, -2};
            var offsets = new long[]{128, 100, -20};
            var c = forwardDecorrelation(w, t, offsets);
            var data = codestream(c, 12,
                    Part2Segments.cbd(new int[]{8, 8, 8}, new boolean[3]),
                    Part2Segments.mct(1, 1, 0, Arrays.stream(t).asDoubleStream().toArray()),
                    Part2Segments.mct(2, 2, 1, Arrays.stream(offsets).asDoubleStream().toArray()),
                    Part2Segments.mcc(1, Part2Segments.Collection.array(1, true, 1, 2, new int[]{0, 1, 2},
                            new int[]{0, 1, 2})),
                    Part2Segments.mco(1));
            var image = decode(data);
            assertChannels(w, image);
            assertEquals(8, image.channels().getFirst().precision());
        }

        @Test
        @DisplayName("a reversible dependency transformation (DPCM) round trips exactly")
        void reversibleDependency() {
            var random = new Random(31);
            var w = new long[][]{smooth(random, 20, 18, 0, 1023), smooth(random, 20, 18, 0, 1023),
                    smooth(random, 20, 18, 0, 1023), smooth(random, 20, 18, 0, 1023)};
            // t10 t11 t20 t21 t22 t30 t31 t32 t33
            var t = new long[]{3, 4, -1, 2, 2, 1, 1, -2, 8};
            var offsets = new long[]{512, 0, -7, 30};
            var c = new long[4][w[0].length];
            for (var x = 0; x < w[0].length; x++) {
                c[0][x] = w[0][x] - offsets[0];
                var row = 0;
                for (var i = 1; i < 4; i++) {
                    var scale = t[row + i];
                    var s = scale >> 1;
                    for (var j = 0; j < i; j++) {
                        s += t[row + j] * w[j][x];
                    }
                    c[i][x] = w[i][x] - offsets[i] - Math.floorDiv(s, scale);
                    row += i + 1;
                }
            }
            var data = codestream(c, 14,
                    Part2Segments.cbdShared(4, 10, false),
                    Part2Segments.mct(3, 0, 1, Arrays.stream(t).asDoubleStream().toArray()),
                    Part2Segments.mct(4, 2, 0, Arrays.stream(offsets).asDoubleStream().toArray()),
                    Part2Segments.mcc(5, Part2Segments.Collection.array(0, true, 3, 4, new int[]{0, 1, 2, 3},
                            new int[]{0, 1, 2, 3})),
                    Part2Segments.mco(5));
            assertChannels(w, decode(data));
        }

        @Test
        @DisplayName("an irreversible decorrelation computes each output in single precision, offsets after rounding")
        void irreversibleDecorrelation() {
            var random = new Random(32);
            var c = new long[][]{image(random, 360, -300, 300), image(random, 360, -300, 300),
                    image(random, 360, -300, 300)};
            var matrix = new float[]{1.0f, 0.0f, 1.402f, 1.0f, -0.344136f, -0.714136f, 1.0f, 1.772f, 0.0f,
                    0.25f, 0.5f, 0.25f};
            var offsets = new double[]{128, 128, 128, 7};
            var data = codestream(c, 11,
                    Part2Segments.cbdShared(4, 8, false),
                    Part2Segments.mct(1, 1, 2, toDoubles(matrix)),
                    Part2Segments.mct(1, 2, 2, offsets),
                    Part2Segments.mcc(2, Part2Segments.Collection.array(1, false, 1, 1, new int[]{0, 1, 2},
                            new int[]{0, 1, 2, 3})),
                    Part2Segments.mco(2));
            var expected = new long[4][360];
            for (var i = 0; i < 4; i++) {
                for (var x = 0; x < 360; x++) {
                    var sum = 0f;
                    for (var j = 0; j < 3; j++) {
                        sum += matrix[i * 3 + j] * (float) c[j][x];
                    }
                    expected[i][x] = Math.clamp((long) Math.rint(sum) + (long) offsets[i], 0, 255);
                }
            }
            assertChannels(expected, decode(data));
        }

        @Test
        @DisplayName("an irreversible dependency transformation predicts each output from the previous ones")
        void irreversibleDependency() {
            var random = new Random(33);
            var c = new long[][]{image(random, 360, 0, 200), image(random, 360, -50, 50), image(random, 360, -50, 50)};
            var t = new double[]{0.5, 0.25, 0.75};
            var data = codestream(c, 10,
                    Part2Segments.cbdShared(3, 9, false),
                    Part2Segments.mct(9, 0, 3, t),
                    Part2Segments.mcc(3, Part2Segments.Collection.array(0, false, 9, 0, new int[]{0, 1, 2},
                            new int[]{0, 1, 2})),
                    Part2Segments.mco(3));
            var image = decode(data);
            for (var x = 0; x < 360; x++) {
                var w0 = (double) c[0][x];
                var w1 = t[0] * w0 + c[1][x];
                var w2 = t[1] * w0 + t[2] * w1 + c[2][x];
                var expected = new double[]{w0, w1, w2};
                for (var i = 0; i < 3; i++) {
                    var actual = image.channels().get(i).sample(x % 20, x / 20);
                    assertEquals(Math.clamp(Math.rint(expected[i]), 0, 511), actual, 1, "component " + i + " at " + x);
                }
            }
        }

        private long[] waveletForward(long[] vector, long origin, int levels) {
            if (levels == 0) {
                return vector;
            }
            var lifted = vector.clone();
            Part2Forward.forward53(lifted, origin);
            var split = Part2Forward.deinterleave(lifted, origin);
            var low = (int) (Part2Forward.ceilDiv2(origin + vector.length, 1) - Part2Forward.ceilDiv2(origin, 1));
            var head = waveletForward(Arrays.copyOf(split, low), Part2Forward.ceilDiv2(origin, 1), levels - 1);
            System.arraycopy(head, 0, split, 0, low);
            return split;
        }

        @ParameterizedTest(name = "origin {0}")
        @ValueSource(ints = {0, 3})
        @DisplayName("a reversible wavelet-based transformation across components round trips exactly")
        void wavelet(int origin) {
            var random = new Random(34 + origin);
            var m = 7;
            var w = new long[m][];
            for (var j = 0; j < m; j++) {
                w[j] = smooth(random, 20, 18, 0, 4095);
            }
            var offsets = new long[]{1, 2, 3, 4, 5, 6, 7};
            var c = new long[m][w[0].length];
            for (var x = 0; x < w[0].length; x++) {
                var vector = new long[m];
                for (var j = 0; j < m; j++) {
                    vector[j] = w[j][x] - offsets[j];
                }
                var transformed = waveletForward(vector, origin, 2);
                for (var j = 0; j < m; j++) {
                    c[j][x] = transformed[j];
                }
            }
            var indices = new int[]{0, 1, 2, 3, 4, 5, 6};
            var data = codestream(c, 16,
                    Part2Segments.cbdShared(m, 12, false),
                    Part2Segments.mct(6, 2, 0, Arrays.stream(offsets).asDoubleStream().toArray()),
                    Part2Segments.mcc(1, Part2Segments.Collection.wavelet(1, 6, 2, origin, indices, indices)),
                    Part2Segments.mco(1));
            assertChannels(w, decode(data));
        }

        @Test
        @DisplayName("stages chain, collections permute components, and a missing input is a null component")
        void stages() {
            var random = new Random(35);
            var a = image(random, 360, -100, 100);
            var b = image(random, 360, -100, 100);
            // stage 0 (MCC 7): swap the two codestream components and create component 2 from nothing
            // stage 1 (MCC 8): component 0 passes through, 1 and 2 (null, zeros) go through a decorrelation
            var data = codestream(new long[][]{a, b}, 9,
                    Part2Segments.cbd(new int[]{8, 8, 8}, new boolean[]{true, true, true}),
                    Part2Segments.mct(1, 1, 0, 1, 0, 2, 1, 0, 1),
                    Part2Segments.mcc(7, Part2Segments.Collection.array(1, false, 0, 0, new int[]{0, 1},
                            new int[]{1, 0})),
                    Part2Segments.mcc(8,
                            Part2Segments.Collection.array(1, false, 0, 0, new int[]{0}, new int[]{0}),
                            Part2Segments.Collection.array(1, false, 1, 0, new int[]{1, 2}, new int[]{2, 1})),
                    Part2Segments.mco(7, 8));
            var image = decode(data);
            var expected0 = b;
            // decorrelation of (a, 0): row 0 gives 1*a + 0*0 -> output index 2, row 1 gives 2*a + 1*0 -> index 1
            var expected2 = Arrays.stream(a).map(v -> Math.clamp(v, -128, 127)).toArray();
            var expected1 = Arrays.stream(a).map(v -> Math.clamp(2 * v, -128, 127)).toArray();
            assertChannels(new long[][]{expected0, expected1, expected2}, image);
        }

        @Test
        @DisplayName("MCT arrays split over a series of segments join in Zmct order")
        void series() {
            var random = new Random(36);
            var c = new long[][]{image(random, 360, 0, 100), image(random, 360, 0, 100)};
            var data = codestream(c, 9,
                    Part2Segments.cbdShared(2, 9, false),
                    Part2Segments.mctPart(1, 0, 4, 1, 0, 0, 1),
                    Part2Segments.mctPart(0, 1, 4, 1, 0, 2, 3),
                    Part2Segments.mcc(1, Part2Segments.Collection.array(1, false, 4, 0, new int[]{0, 1},
                            new int[]{0, 1})),
                    Part2Segments.mco(1));
            var image = decode(data);
            var expected0 = new long[360];
            var expected1 = new long[360];
            for (var x = 0; x < 360; x++) {
                expected0[x] = Math.clamp(2 * c[0][x] + 3 * c[1][x], 0, 511);
                expected1[x] = Math.clamp(c[1][x], 0, 511);
            }
            assertChannels(new long[][]{expected0, expected1}, image);
        }

        @Test
        @DisplayName("an MCC segment in the tile-part header overrides the main header's")
        void tileOverride() {
            var random = new Random(37);
            var c = new long[][]{image(random, 360, 0, 100), image(random, 360, 0, 100)};
            var writer = new Part2Writer(20, 18, 2, 9, true);
            writer.rsiz |= Part2Segments.RSIZ_MCT;
            writer.mct = 2;
            writer.mainSegments.add(Part2Segments.cbdShared(2, 9, false));
            writer.mainSegments.add(Part2Segments.mcc(1, Part2Segments.Collection.array(1, false, 0, 0,
                    new int[]{0, 1}, new int[]{0, 1})));
            writer.mainSegments.add(Part2Segments.mco(1));
            writer.tileSegments.add(Part2Segments.mcc(1, Part2Segments.Collection.array(1, false, 0, 0,
                    new int[]{0, 1}, new int[]{1, 0})));
            var data = writer.write(forward(writer, c, new long[2], Tree.dyadic(0), null));
            assertChannels(new long[][]{c[1], c[0]}, decode(data));
        }

        @Test
        @DisplayName("an MCC segment naming a missing MCT array is malformed")
        void missingArray() {
            var c = new long[][]{new long[360], new long[360]};
            var data = codestream(c, 9,
                    Part2Segments.cbdShared(2, 9, false),
                    Part2Segments.mcc(1, Part2Segments.Collection.array(1, false, 9, 0, new int[]{0, 1},
                            new int[]{0, 1})),
                    Part2Segments.mco(1));
            assertThrows(MediaException.Malformed.class, () -> decode(data));
        }

        private static double[] toDoubles(float[] values) {
            var result = new double[values.length];
            for (var i = 0; i < values.length; i++) {
                result[i] = values[i];
            }
            return result;
        }
    }

    @Nested
    @DisplayName("arbitrary transformation kernels (annexes G and H)")
    class Kernels {
        private byte[] codestream(Part2Writer writer, long[] plane, Part2Forward.Kernel kernel, byte[] atk) {
            writer.rsiz |= Part2Segments.RSIZ_ATK;
            writer.wavelet = atk[5] & 0xFF;
            writer.levels = 3;
            writer.kernel = LiftingKernel.parse(atk, 4, atk.length).kernel();
            writer.mainSegments.add(atk);
            var shift = 1L << (writer.precisions[0] - 1);
            return writer.write(forward(writer, new long[][]{plane}, new long[]{shift}, Tree.dyadic(3), kernel));
        }

        @ParameterizedTest(name = "coefficient type {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("the 5-3 wavelet signalled as an arbitrary kernel decodes losslessly")
        void leGall(int type) {
            var random = new Random(40 + type);
            var writer = new Part2Writer(45, 31, 1, 8, false);
            writer.x0 = 7;
            writer.y0 = 2;
            var plane = smooth(random, 45, 31, 0, 255);
            var data = codestream(writer, plane, Part2Forward.LE_GALL, Part2Forward.LE_GALL.atk(2, type));
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("the 5-3 wavelet signalled as a whole-sample symmetric kernel decodes losslessly")
        void wholeSample() {
            var random = new Random(44);
            var writer = new Part2Writer(33, 40, 1, 8, false);
            writer.rsiz |= Part2Segments.RSIZ_WS;
            var plane = smooth(random, 33, 40, 0, 255);
            var data = codestream(writer, plane, Part2Forward.LE_GALL, Part2Forward.LE_GALL.atk(9, 1, true));
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("a four-tap reversible kernel with constant extension round trips exactly")
        void wide() {
            var random = new Random(45);
            var writer = new Part2Writer(50, 27, 1, 10, false);
            writer.x0 = 1;
            writer.y0 = 4;
            var plane = smooth(random, 50, 27, 0, 1023);
            var data = codestream(writer, plane, Part2Forward.WIDE_REVERSIBLE,
                    Part2Forward.WIDE_REVERSIBLE.atk(3, 0));
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("a three-step kernel starting on the even samples round trips exactly")
        void threeSteps() {
            var random = new Random(46);
            var writer = new Part2Writer(29, 35, 1, 9, false);
            writer.x0 = 2;
            writer.y0 = 5;
            var plane = smooth(random, 29, 35, 0, 511);
            var data = codestream(writer, plane, Part2Forward.THREE_STEP, Part2Forward.THREE_STEP.atk(200, 1));
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("a reversible kernel with fractional coefficients floors each update")
        void fractional() {
            var kernel = new Part2Forward.Kernel(true, 1, true,
                    new Part2Forward.Step(1, 0, new double[]{-0.5, -0.5}, 0, 0.5),
                    new Part2Forward.Step(0, -1, new double[]{0.25, 0.25}, 0, 0.5));
            var random = new Random(47);
            var writer = new Part2Writer(26, 26, 1, 8, false);
            var plane = smooth(random, 26, 26, 0, 255);
            var data = codestream(writer, plane, kernel, kernel.atk(5, 3));
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("a kernel the decoder ignored would not reconstruct the input")
        void notIgnored() {
            var random = new Random(48);
            var writer = new Part2Writer(26, 26, 1, 8, false);
            var plane = smooth(random, 26, 26, 0, 255);
            var data = codestream(writer, plane, Part2Forward.WIDE_REVERSIBLE, Part2Forward.WIDE_REVERSIBLE.atk(3, 1));
            // rewrite the COD wavelet byte to the 5-3 wavelet
            var cod = indexOf(data, Codestream.COD);
            data[cod + 13] = 1;
            assertFalse(Arrays.equals(plane, samples(decode(data).channels().getFirst())));
        }

        @Test
        @DisplayName("the 9-7 wavelet as an irreversible arbitrary kernel decodes like the built-in 9-7 wavelet")
        void daubechies() {
            var random = new Random(49);
            var writer = new Part2Writer(40, 36, 1, 8, false);
            writer.rsiz |= Part2Segments.RSIZ_ATK | Part2Segments.RSIZ_WS;
            writer.wavelet = 2;
            writer.levels = 3;
            writer.quantizationStyle = Quantization.SCALAR_EXPOUNDED;
            writer.guardBits = 3;
            writer.exponent = 11;
            writer.mantissa = 0;
            var atk = Part2Forward.DAUBECHIES.atk(2, 3, true);
            writer.kernel = LiftingKernel.parse(atk, 4, atk.length).kernel();
            writer.mainSegments.add(atk);
            var plane = smooth(random, 40, 36, 0, 255);
            var coefficients = new double[plane.length];
            for (var i = 0; i < plane.length; i++) {
                coefficients[i] = plane[i] - 128;
            }
            Part2Forward.decompose(coefficients, 40, 0, 0, 0, 0, 40, 36, Tree.dyadic(3), Part2Forward.DAUBECHIES);
            var floats = new float[plane.length];
            for (var i = 0; i < plane.length; i++) {
                floats[i] = (float) coefficients[i];
            }
            var data = writer.write(new float[][]{floats});
            var viaKernel = samples(decode(data).channels().getFirst());
            var maxError = 0L;
            for (var i = 0; i < plane.length; i++) {
                maxError = Math.max(maxError, Math.abs(viaKernel[i] - plane[i]));
            }
            assertTrue(maxError <= 2, "round trip error " + maxError);
            var builtIn = data.clone();
            builtIn[indexOf(builtIn, Codestream.COD) + 13] = 0;
            var viaWavelet = samples(decode(builtIn).channels().getFirst());
            for (var i = 0; i < plane.length; i++) {
                assertEquals(viaWavelet[i], viaKernel[i], 1, "sample " + i);
            }
        }

        @Test
        @DisplayName("an ATK segment in the tile-part header overrides the main header's of the same index")
        void tileOverride() {
            var random = new Random(50);
            var writer = new Part2Writer(30, 30, 1, 8, false);
            writer.rsiz |= Part2Segments.RSIZ_ATK;
            writer.wavelet = 2;
            writer.levels = 2;
            var atk = Part2Forward.THREE_STEP.atk(2, 1);
            writer.kernel = LiftingKernel.parse(atk, 4, atk.length).kernel();
            writer.mainSegments.add(Part2Forward.WIDE_REVERSIBLE.atk(2, 1));
            writer.tileSegments.add(atk);
            var plane = smooth(random, 30, 30, 0, 255);
            var data = writer.write(forward(writer, new long[][]{plane}, new long[]{128}, Tree.dyadic(2),
                    Part2Forward.THREE_STEP));
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("an ATK segment of index 0 or 1, or a COD naming a missing kernel, is malformed")
        void malformed() {
            var atk = Part2Forward.LE_GALL.atk(2, 1);
            atk[5] = 1;
            assertThrows(MediaException.Malformed.class, () -> LiftingKernel.parse(atk, 4, atk.length));
            var writer = new Part2Writer(16, 16, 1, 8, false);
            writer.rsiz |= Part2Segments.RSIZ_ATK;
            writer.levels = 1;
            var data = writer.write(new long[][]{new long[256]});
            data[indexOf(data, Codestream.COD) + 13] = 7;
            assertThrows(MediaException.Malformed.class, () -> decode(data));
        }
    }

    @Nested
    @DisplayName("arbitrary decompositions (annex F)")
    class Decompositions {
        private byte[] codestream(Part2Writer writer, long[] plane, Tree tree) {
            writer.rsiz |= Part2Segments.RSIZ_ADS;
            return writer.write(forward(writer, new long[][]{plane}, new long[]{1L << (writer.precisions[0] - 1)},
                    tree, null));
        }

        @Test
        @DisplayName("a DFS of joint splits only is the dyadic decomposition")
        void jointSplits() {
            var random = new Random(60);
            var writer = new Part2Writer(35, 21, 1, 8, false);
            writer.levels = 3;
            writer.decompositionByte = 0x81;
            writer.decomposition = new Decomposition(new int[]{1, 1, 1}, null, null);
            writer.mainSegments.add(Part2Segments.dfs(1, 1, 1, 1));
            var plane = smooth(random, 35, 21, 0, 255);
            assertChannels(new long[][]{plane}, decode(codestream(writer, plane, Tree.dyadic(3))));
        }

        @Test
        @DisplayName("a DFS of horizontal and vertical resolution splits round trips, and reduces per direction")
        void resolutionSplits() {
            var random = new Random(61);
            var writer = new Part2Writer(44, 30, 1, 8, false);
            writer.x0 = 3;
            writer.y0 = 1;
            writer.levels = 3;
            writer.decompositionByte = 0x82;
            writer.decomposition = new Decomposition(new int[]{2, 3, 1}, null, null);
            writer.mainSegments.add(Part2Segments.dfs(2, 2, 3, 1));
            var level3 = Tree.of(JOINT);
            var level2 = Tree.of(VERTICAL, level3, Tree.LEAF_NODE);
            var tree = Tree.of(HORIZONTAL, level2, Tree.LEAF_NODE);
            var plane = smooth(random, 44, 30, 0, 255);
            var data = codestream(writer, plane, tree);
            assertChannels(new long[][]{plane}, decode(data));
            // one resolution less: only the horizontal split of level 1 is undone... removed, so the width halves
            var reduced = decode(data, 1);
            assertEquals(Part2Forward.ceilDiv2(47, 1) - Part2Forward.ceilDiv2(3, 1), reduced.width());
            assertEquals(30, reduced.height());
            var reduced2 = decode(data, 2);
            assertEquals(Part2Forward.ceilDiv2(47, 1) - Part2Forward.ceilDiv2(3, 1), reduced2.width());
            assertEquals(Part2Forward.ceilDiv2(31, 1) - Part2Forward.ceilDiv2(1, 1), reduced2.height());
        }

        @Test
        @DisplayName("a DFS segment with the 16-bit index of Table A.25 reads like the 8-bit one of equation A-2")
        void wideDfsIndex() {
            var random = new Random(64);
            var writer = new Part2Writer(44, 30, 1, 8, false);
            writer.levels = 3;
            writer.decompositionByte = 0x82;
            writer.decomposition = new Decomposition(new int[]{2, 3, 1}, null, null);
            writer.mainSegments.add(Part2Segments.dfsWideIndex(2, 2, 3, 1));
            var tree = Tree.of(HORIZONTAL, Tree.of(VERTICAL, Tree.of(JOINT), Tree.LEAF_NODE), Tree.LEAF_NODE);
            var plane = smooth(random, 44, 30, 0, 255);
            var data = codestream(writer, plane, tree);
            assertChannels(new long[][]{plane}, decode(data));
            assertEquals(22, decode(data, 1).width());
            // a 16-bit index of 0 is still invalid
            var dfs = indexOf(data, Codestream.DFS);
            data[dfs + 5] = 0;
            assertThrows(MediaException.Malformed.class, () -> decode(data));
        }

        @Test
        @DisplayName("the decomposition of Figure F.14 round trips")
        void figureF14() {
            var random = new Random(62);
            var writer = new Part2Writer(48, 40, 1, 8, false);
            writer.levels = 3;
            writer.decompositionByte = 0x81;
            writer.decomposition = new Decomposition(new int[]{1, 2, 3}, new int[]{3, 1},
                    new int[]{3, 2, 0, 3, 0, 0, 2, 0, 3});
            writer.mainSegments.add(Part2Segments.dfs(1, 1, 2, 3));
            writer.tileSegments.add(Part2Segments.ads(1, new int[]{3, 1}, new int[]{3, 2, 0, 3, 0, 0, 2, 0, 3}));
            writer.tileSegments.add(Part2Segments.cod(0, 0x81, 4, 1));
            var hl = Tree.of(HORIZONTAL, Tree.of(VERTICAL), Tree.LEAF_NODE);
            var lh = Tree.of(VERTICAL);
            var hh = Tree.of(VERTICAL, Tree.LEAF_NODE, Tree.of(HORIZONTAL));
            var r2 = Tree.of(VERTICAL);
            var r1 = Tree.of(HORIZONTAL, r2, Tree.LEAF_NODE);
            var tree = Tree.of(JOINT, r1, hl, lh, hh);
            var plane = smooth(random, 48, 40, 0, 255);
            assertChannels(new long[][]{plane}, decode(codestream(writer, plane, tree)));
        }

        @Test
        @DisplayName("an ADS with sub-levels in every sub-band and a repeated pattern round trips")
        void subLevels() {
            var random = new Random(63);
            var writer = new Part2Writer(52, 46, 1, 8, false);
            writer.x0 = 5;
            writer.y0 = 9;
            writer.levels = 2;
            writer.decomposition = new Decomposition(null, new int[]{2}, new int[]{1, 2, 3});
            writer.tileSegments.add(Part2Segments.ads(1, new int[]{2}, new int[]{1, 2, 3}));
            writer.tileSegments.add(Part2Segments.cod(0, 0x81, 4, 1));
            // per level, in the order LEV_S visits them: HH splits jointly, LH horizontally and HL vertically; the
            // second level reads the three elements again
            var level2 = Tree.of(JOINT, Tree.LEAF_NODE, Tree.of(VERTICAL), Tree.of(HORIZONTAL), Tree.of(JOINT));
            var tree = Tree.of(JOINT, level2, Tree.of(VERTICAL), Tree.of(HORIZONTAL), Tree.of(JOINT));
            var plane = smooth(random, 52, 46, 0, 255);
            assertChannels(new long[][]{plane}, decode(codestream(writer, plane, tree)));
        }

        @Test
        @DisplayName("a COD naming a missing DFS or ADS segment is malformed")
        void missing() {
            var writer = new Part2Writer(16, 16, 1, 8, false);
            writer.rsiz |= Part2Segments.RSIZ_ADS;
            writer.levels = 1;
            var data = writer.write(new long[][]{new long[256]});
            data[indexOf(data, Codestream.COD) + 9] = (byte) 0x85;
            assertThrows(MediaException.Malformed.class, () -> decode(data));
        }
    }

    @Nested
    @DisplayName("arbitrarily shaped regions of interest (annex L)")
    class Regions {
        private int[] scales(Part2Writer writer, Tree tree, long[][] regions, boolean[] ellipse, int[] shifts) {
            var width = writer.componentWidth(0);
            var height = writer.componentHeight(0);
            var cx0 = writer.componentX0(0);
            var cy0 = writer.componentY0(0);
            var scales = new int[width * height];
            for (var r = 0; r < regions.length; r++) {
                var region = regions[r];
                var mask = new boolean[width * height];
                for (var v = 0; v < height; v++) {
                    for (var u = 0; u < width; u++) {
                        var x = (cx0 + u) * writer.subsamplingX[0];
                        var y = (cy0 + v) * writer.subsamplingY[0];
                        boolean inside;
                        if (ellipse[r]) {
                            var dx = (double) (x - region[0]);
                            var dy = (double) (y - region[1]);
                            inside = region[3] * region[3] * dx * dx + region[2] * region[2] * dy * dy
                                    <= (double) region[2] * region[2] * region[3] * region[3];
                        } else {
                            inside = x >= region[0] && x < region[0] + region[2] && y >= region[1]
                                    && y < region[1] + region[3];
                        }
                        mask[v * width + u] = inside;
                    }
                }
                Part2Forward.decomposeMask(mask, width, 0, 0, cx0, cy0, cx0 + width, cy0 + height, tree,
                        Part2Forward.LE_GALL);
                for (var i = 0; i < mask.length; i++) {
                    if (mask[i]) {
                        scales[i] = Math.max(scales[i], shifts[r]);
                    }
                }
            }
            return scales;
        }

        @Test
        @DisplayName("rectangle and ellipse regions of different shifts decode losslessly")
        void regions() {
            var random = new Random(70);
            var writer = new Part2Writer(64, 48, 1, 8, false);
            writer.rsiz |= Part2Segments.RSIZ_ROI;
            writer.x0 = 2;
            writer.y0 = 3;
            writer.levels = 3;
            var tree = Tree.dyadic(3);
            var plane = smooth(random, 64, 48, 0, 255);
            var coefficients = forward(writer, new long[][]{plane}, new long[]{128}, tree, null);
            var regions = new long[][]{{10, 12, 20, 15}, {45, 30, 12, 8}};
            var ellipse = new boolean[]{false, true};
            var shifts = new int[]{5, 3};
            writer.regionScales = new int[][]{scales(writer, tree, regions, ellipse, shifts)};
            writer.mainSegments.add(Part2Segments.rgn(0, 1, 5, 10, 12, 20, 15));
            writer.mainSegments.add(Part2Segments.rgn(0, 2, 3, 45, 30, 12, 8));
            assertChannels(new long[][]{plane}, decode(writer.write(coefficients)));
        }

        @Test
        @DisplayName("a region for every component (Crgn 65535) applies to each")
        void everyComponent() {
            var random = new Random(71);
            var writer = new Part2Writer(40, 40, 2, 8, false);
            writer.rsiz |= Part2Segments.RSIZ_ROI;
            writer.levels = 2;
            var tree = Tree.dyadic(2);
            var planes = new long[][]{smooth(random, 40, 40, 0, 255), smooth(random, 40, 40, 0, 255)};
            var coefficients = forward(writer, planes, new long[]{128, 128}, tree, null);
            var region = new long[][]{{5, 5, 17, 9}};
            var scales = scales(writer, tree, region, new boolean[]{false}, new int[]{4});
            writer.regionScales = new int[][]{scales, scales};
            writer.mainSegments.add(Part2Segments.rgn(65535, 1, 4, 5, 5, 17, 9));
            assertChannels(planes, decode(writer.write(coefficients)));
        }
    }

    @Nested
    @DisplayName("precision and reference grid")
    class Ranges {
        @Test
        @DisplayName("38-bit components decode into wide samples")
        void wideSamples() {
            var random = new Random(80);
            var writer = new Part2Writer(25, 19, 1, 38, false);
            writer.levels = 2;
            // a magnitude bound above 30 bit-planes takes the 64-bit path
            writer.guardBits = 7;
            writer.exponent = 26;
            var base = 1L << 37;
            var plane = image(random, 25 * 19, base - 2000, base + 2000);
            var data = writer.write(forward(writer, new long[][]{plane}, new long[]{base}, Tree.dyadic(2), null));
            var channel = decode(data).channels().getFirst();
            assertTrue(channel.wide());
            assertEquals(38, channel.precision());
            assertArrayEquals(plane, channel.wideSamples());
        }

        @ParameterizedTest(name = "{0} truncated bit-planes")
        @ValueSource(ints = {5, 20})
        @DisplayName("code-blocks of more than 30 bit-planes reconstruct their truncated planes at the midpoint")
        void wideCodeBlocks(int delta) {
            var random = new Random(81);
            var writer = new Part2Writer(20, 20, 1, 38, true);
            var values = image(random, 400, -60000, 60000);
            values[0] = 0;
            values[1] = 65535;
            var data = writer.write(new long[][]{values});
            // raise the magnitude bound (guard bits and exponent) so each block's coded planes become its top ones
            var qcd = indexOf(data, Codestream.QCD);
            var bound = ((data[qcd + 4] & 0xFF) >> 5) + ((data[qcd + 5] & 0xFF) >> 3) - 1 + delta;
            data[qcd + 4] = (byte) (7 << 5);
            data[qcd + 5] = (byte) ((bound - 6) << 3);
            var channel = decode(data).channels().getFirst();
            var expected = Arrays.stream(values)
                    .map(v -> v == 0 ? 0 : Long.signum(v) * ((Math.abs(v) << delta) + (1L << (delta - 1)))).toArray();
            assertArrayEquals(expected, samples(channel));
        }

        @Test
        @DisplayName("a reference grid offset beyond 2^31 decodes losslessly with its parities")
        void largeGrid() {
            var random = new Random(82);
            var writer = new Part2Writer(37, 29, 2, 8, false);
            writer.rsiz = 0;
            writer.x0 = 3_000_000_001L;
            writer.y0 = (1L << 31) + 5;
            writer.subsamplingX[1] = 2;
            writer.subsamplingY[1] = 3;
            writer.levels = 3;
            var planes = new long[][]{smooth(random, 37, 29, 0, 255),
                    smooth(random, writer.componentWidth(1), writer.componentHeight(1), 0, 255)};
            var data = writer.write(forward(writer, planes, new long[]{128, 128}, Tree.dyadic(3), null));
            var image = decode(data);
            assertEquals(writer.x0, image.x0());
            assertEquals(writer.y0, image.y0());
            assertEquals(Math.ceilDiv(writer.x0, 2), image.channels().get(1).x0());
            assertChannels(planes, image);
            var reduced = decode(data, 2);
            assertEquals(Part2Forward.ceilDiv2(writer.x0, 2), reduced.x0());
        }
    }

    @Nested
    @DisplayName("extensions this decoder does not implement")
    class Unsupported {
        private byte[] base(int rsiz) {
            var writer = new Part2Writer(16, 16, 1, 8, false);
            writer.rsiz = Codestream.RSIZ_PART2 | rsiz;
            return writer.write(new long[][]{new long[256]});
        }

        private byte[] withSegment(byte[] data, byte[] segment) {
            var at = indexOf(data, Codestream.QCD);
            var length = ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
            var end = at + 2 + length;
            var result = new byte[data.length + segment.length];
            System.arraycopy(data, 0, result, 0, end);
            System.arraycopy(segment, 0, result, end, segment.length);
            System.arraycopy(data, end, result, end + segment.length, data.length - end);
            return result;
        }

        @Test
        @DisplayName("single sample overlap, visual masking and precinct quantization are rejected, not ignored")
        void rejected() {
            assertThrows(MediaException.Unsupported.class, () -> decode(base(0x0010)));
            assertThrows(MediaException.Unsupported.class,
                    () -> decode(withSegment(base(0x0008), Part2Forward.segment(Codestream.VMS, new byte[]{0, 0}))));
            assertThrows(MediaException.Unsupported.class,
                    () -> decode(withSegment(base(0x0800), Part2Forward.segment(Codestream.QPD, new byte[]{0, 0}))));
        }

        @Test
        @DisplayName("trellis coded and variable deadzone quantization are rejected")
        void quantization() {
            for (var style : new int[]{3, 9}) {
                var data = base(0x0006);
                var at = indexOf(data, Codestream.QCD);
                data[at + 4] = (byte) ((data[at + 4] & 0xE0) | style);
                assertThrows(MediaException.Unsupported.class, () -> decode(data), "style " + style);
            }
        }
    }

    static int indexOf(byte[] data, int marker) {
        for (var i = 0; i + 1 < data.length; i++) {
            if ((data[i] & 0xFF) == marker >> 8 && (data[i + 1] & 0xFF) == (marker & 0xFF)) {
                return i;
            }
        }
        throw new AssertionError("no marker " + Integer.toHexString(marker));
    }
}
