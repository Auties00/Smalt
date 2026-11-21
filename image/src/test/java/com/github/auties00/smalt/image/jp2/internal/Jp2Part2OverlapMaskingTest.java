package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static com.github.auties00.smalt.image.jp2.internal.Jp2Part2Test.assertChannels;
import static com.github.auties00.smalt.image.jp2.internal.Jp2Part2Test.decode;
import static com.github.auties00.smalt.image.jp2.internal.Jp2Part2Test.image;
import static com.github.auties00.smalt.image.jp2.internal.Jp2Part2Test.indexOf;
import static com.github.auties00.smalt.image.jp2.internal.Jp2Part2Test.samples;
import static com.github.auties00.smalt.image.jp2.internal.Jp2Part2Test.smooth;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.HORIZONTAL;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.JOINT;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.Tree;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.VERTICAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the visual masking (ISO/IEC 15444-2 annex E) and the single sample overlap transformations (annex I) against
 * codestreams written from the equations of the standard: the test applies the forward masking of E.4 (equation E-7)
 * and the forward procedures of I.3.2 with {@link Part2Forward}, codes the result with {@link Part2Writer}, and
 * expects the decoder to give the samples back exactly on reversible paths and within the quantization error on
 * irreversible ones. Hand-computed vectors pin the one-dimensional procedures and the masking sample by sample.
 */
@DisplayName("JPEG 2000 Part 2 visual masking and single sample overlap")
class Jp2Part2OverlapMaskingTest {
    private static final MediaLimits LIMITS = MediaLimits.builder().maxChannels(16384).build();

    /** The {@code Rsiz} bit of the visual masking capability (Table A.2). */
    private static final int RSIZ_VMS = 0x0008;

    /** A {@code VMS} segment (A.3.2). */
    static byte[] vms(int component, int minimumLevel, boolean respectBlocks, int window, int retained, int alpha,
                      int beta) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, component);
        out.write(minimumLevel | (respectBlocks ? 0x80 : 0));
        out.write(window);
        out.write(retained);
        out.write(alpha);
        out.write(beta);
        return Part2Forward.segment(Codestream.VMS, out.toByteArray());
    }

    /** The SSO field of Table A.11 selecting the single sample overlap transformation with cells of 2^x by 2^y. */
    static int sso(int x, int y) {
        return ComponentCoding.OVERLAP_SSO | (y << 4) | x;
    }

    static long maxError(long[] expected, long[] actual) {
        var max = 0L;
        for (var i = 0; i < expected.length; i++) {
            max = Math.max(max, Math.abs(expected[i] - actual[i]));
        }
        return max;
    }

    /**
     * The decoder-side masking of equations E-4 to E-6 on one sub-band, written directly from the equations: the
     * neighbourhood of every coefficient is summed coefficient by coefficient.
     *
     * @param z the dequantized coefficients of the sub-band, row-major
     * @param bx0 the sub-band's left edge in sub-band coordinates, which places the code-block grid
     * @param by0 the sub-band's top edge
     * @param block the code-block size, or 0 when the neighbourhoods ignore code-blocks
     * @param step the step size {@code Delta_b}
     * @param planes the number of magnitude bit-planes {@code M_b}
     * @param gain the sub-band gain {@code g}
     * @param a the normalization constant
     */
    static double[] unmaskReference(double[] z, int width, int height, long bx0, long by0, int block, double step,
                                    int planes, double gain, int window, int retained, double alpha, double beta,
                                    double a) {
        var x = new double[z.length];
        var reconstructed = new double[z.length];
        for (var v = 0; v < height; v++) {
            for (var u = 0; u < width; u++) {
                var factor = factor(reconstructed, width, height, u, v, bx0, by0, block, gain, window, beta, a);
                var i = v * width + u;
                var q = (long) Math.floor(Math.abs(z[i]) / step);
                var qhat = retained >= planes ? q : q >> (planes - retained) << (planes - retained);
                reconstructed[i] = Math.pow(qhat * step / gain * factor, 1 / alpha) * gain;
                x[i] = Math.signum(z[i]) * Math.pow(Math.abs(z[i]) / gain * factor, 1 / alpha) * gain;
            }
        }
        return x;
    }

    /** The masking factor {@code 1 + a S / |phi|} of a coefficient from the reconstructions of its neighbours. */
    private static double factor(double[] reconstructed, int width, int height, int u, int v, long bx0, long by0,
                                 int block, double gain, int window, double beta, double a) {
        var sum = 0.0;
        var count = 0;
        for (var dv = -window; dv <= 0; dv++) {
            for (var du = -window; du <= window; du++) {
                if (dv == 0 && du >= 0) {
                    break;
                }
                var nu = u + du;
                var nv = v + dv;
                if (nu < 0 || nv < 0 || nu >= width || nv >= height) {
                    continue;
                }
                if (block > 0 && (Math.floorDiv(bx0 + nu, block) != Math.floorDiv(bx0 + u, block)
                        || Math.floorDiv(by0 + nv, block) != Math.floorDiv(by0 + v, block))) {
                    continue;
                }
                sum += Math.pow(reconstructed[nv * width + nu] / gain, beta);
                count++;
            }
        }
        return count == 0 ? 1 : 1 + a * sum / count;
    }

    @Nested
    @DisplayName("visual masking (annex E)")
    class Masking {
        /** A decoder structure of one LL sub-band of 4x4 coefficients with a step size of 1 and 9 bit-planes. */
        private TileComponent band(int size, int levels, int codeBlock, LiftingKernel kernel) {
            var coding = new ComponentCoding(levels + 1, codeBlock, codeBlock, 0, false, null, null);
            var exponents = new int[64];
            Arrays.fill(exponents, 8);
            return new TileComponent(0, 0, size, size, coding,
                    new Quantization(Quantization.SCALAR_EXPOUNDED, 2, exponents, new int[64]), 8, levels + 1,
                    false, kernel, null, null, LIMITS);
        }

        @Test
        @DisplayName("undoes equation E-4 on a 4x4 sub-band, sample by sample")
        void handComputed() {
            var component = band(4, 0, 2, null);
            var ll = component.resolutions[0].bands()[0];
            assertEquals(1f, ll.stepSize());
            assertEquals(9, ll.bitPlanes());
            float[] z = {3.5f, -1.5f, 0, 2.5f, 0.5f, -4.5f, 1.5f, 0, 6.5f, 0, -0.5f, 2.5f, 1.5f, 1.5f, -2.5f, 0};
            var data = z.clone();
            // minlevel 0, w = 1, bits_retained 9, alpha = beta = 1/2
            var masking = new VisualMasking(0, 0, false, 1, 9, 0.5, 0.5);
            masking.apply(data, 4, component, 8);
            var a = Math.sqrt(1000 / 128.0);
            // the first coefficient has no neighbour: x = (3.5)^2
            assertEquals(12.25f, data[0], 1e-5f);
            // the second sees the first, whose index 3 reconstructs as xhat = 3^2 and contributes sqrt(9) = 3
            assertEquals(-(float) Math.pow(1.5 * (1 + a * 3), 2), data[1], 1e-3f);
            // zero stays zero
            assertEquals(0f, data[2]);
            var reference = new double[16];
            for (var i = 0; i < 16; i++) {
                reference[i] = z[i];
            }
            var expected = unmaskReference(reference, 4, 4, 0, 0, 0, 1, 9, 1, 1, 9, 0.5, 0.5, a);
            for (var i = 0; i < 16; i++) {
                assertEquals(expected[i], data[i], Math.abs(expected[i]) * 1e-5 + 1e-5, "coefficient " + i);
            }
            // E-5 with bits_retained 3 of M_b = 9: the first coefficient's index 3 (000000011) truncates to 0, so its
            // neighbour sees xhat = 0 and is only raised to the power 1 / alpha
            var truncated = z.clone();
            new VisualMasking(0, 0, false, 1, 3, 0.5, 0.5).apply(truncated, 4, component, 8);
            assertEquals(12.25f, truncated[0], 1e-5f);
            assertEquals(-2.25f, truncated[1], 1e-5f);
            // bits_retained 8 keeps the top 8 bits: 3 truncates to 2, which reconstructs as xhat = 2^2
            var eight = z.clone();
            new VisualMasking(0, 0, false, 1, 8, 0.5, 0.5).apply(eight, 4, component, 8);
            assertEquals(-(float) Math.pow(1.5 * (1 + a * 2), 2), eight[1], 1e-3f);
        }

        @ParameterizedTest(name = "w={0} respect={1} retained={2} alpha={3} beta={4} minlevel={5} gains={6}")
        @CsvSource({"1,false,12,90,26,0,false", "6,false,9,90,26,1,true", "3,true,4,128,64,0,false",
                "2,true,1,40,200,2,true", "0,false,12,100,26,0,false", "9,true,12,255,0,1,true"})
        @DisplayName("matches the equations, summed coefficient by coefficient, over every masked sub-band")
        void reference(int window, boolean respect, int retained, int alpha, int beta, int minimumLevel,
                       boolean gains) {
            var random = new Random(window * 31L + alpha);
            // the lifting form of the 9-7 wavelet puts the sub-band gains in the step sizes, the built-in one does not
            var component = band(37, 3, 3, gains ? LiftingKernel.IRREVERSIBLE_97 : null);
            if (gains) {
                assertEquals(2, component.resolutions[3].bands()[2].gain());
            }
            var stride = 37;
            var data = new float[37 * 37];
            for (var i = 0; i < data.length; i++) {
                data[i] = (float) (random.nextGaussian() * 20);
            }
            var original = data.clone();
            var masking = new VisualMasking(0, minimumLevel, respect, window, retained, alpha / 128.0, beta / 128.0);
            masking.apply(data, stride, component, 10);
            var a = Math.pow(1000 / 512.0, beta / 128.0);
            var masked = 0;
            for (var r = 0; r < component.resolutions.length; r++) {
                for (var band : component.resolutions[r].bands()) {
                    var width = (int) (band.x1() - band.x0());
                    var height = (int) (band.y1() - band.y0());
                    var z = new double[width * height];
                    for (var v = 0; v < height; v++) {
                        for (var u = 0; u < width; u++) {
                            z[v * width + u] = original[(band.offsetY() + v) * stride + band.offsetX() + u];
                        }
                    }
                    var expected = r < minimumLevel ? z : unmaskReference(z, width, height, band.x0(), band.y0(),
                            respect ? 8 : 0, band.stepSize(), band.bitPlanes(), Math.scalb(1.0, band.gain()), window,
                            retained, alpha / 128.0, beta / 128.0, a);
                    for (var v = 0; v < height; v++) {
                        for (var u = 0; u < width; u++) {
                            var actual = data[(band.offsetY() + v) * stride + band.offsetX() + u];
                            var value = expected[v * width + u];
                            assertEquals(value, actual, Math.abs(value) * 2e-5 + 1e-4,
                                    "resolution " + r + " band " + band.orientation() + " at " + u + "," + v);
                            masked += r >= minimumLevel && value != z[v * width + u] ? 1 : 0;
                        }
                    }
                }
            }
            assertTrue(masked > 0, "no coefficient changed");
        }

        /**
         * Masks the coefficients of every sub-band of resolution at least {@code minimumLevel} with equation E-7 in
         * raster order and quantizes them, returning the indices in the layout of the sub-bands.
         */
        private float[] maskAndQuantize(double[] x, Part2Writer writer, int minimumLevel, boolean respect,
                                        int window, int retained, double alpha, double beta) {
            var structure = writer.encoderStructure(0);
            var stride = writer.width;
            var indices = new float[x.length];
            var a = Math.pow(1000 / Math.scalb(1.0, writer.precisions[0] - 1), beta);
            for (var r = 0; r < structure.resolutions.length; r++) {
                for (var band : structure.resolutions[r].bands()) {
                    var width = (int) (band.x1() - band.x0());
                    var height = (int) (band.y1() - band.y0());
                    var step = band.stepSize();
                    var gain = Math.scalb(1.0, band.gain());
                    var reconstructed = new double[width * height];
                    for (var v = 0; v < height; v++) {
                        for (var u = 0; u < width; u++) {
                            var at = (band.offsetY() + v) * stride + band.offsetX() + u;
                            var value = x[at];
                            if (r < minimumLevel) {
                                indices[at] = (float) (Math.signum(value) * Math.floor(Math.abs(value) / step));
                                continue;
                            }
                            var factor = factor(reconstructed, width, height, u, v, band.x0(), band.y0(),
                                    respect ? 1 << writer.codeBlock : 0, gain, window, beta, a);
                            var z = Math.pow(Math.abs(value) / gain, alpha) * gain / factor;
                            var q = (long) Math.floor(z / step);
                            var planes = band.bitPlanes();
                            var qhat = retained >= planes ? q : q >> (planes - retained) << (planes - retained);
                            reconstructed[v * width + u] = Math.pow(qhat * step / gain * factor, 1 / alpha) * gain;
                            indices[at] = (float) (Math.signum(value) * q);
                        }
                    }
                }
            }
            return indices;
        }

        private Part2Writer irreversible(int width, int height) {
            var writer = new Part2Writer(width, height, 1, 8, false);
            writer.rsiz |= RSIZ_VMS;
            writer.wavelet = 0;
            writer.levels = 3;
            writer.quantizationStyle = Quantization.SCALAR_EXPOUNDED;
            writer.guardBits = 2;
            writer.exponent = 13;
            writer.codeBlock = 3;
            return writer;
        }

        private double[] analyze(long[] plane, Part2Writer writer) {
            var coefficients = new double[plane.length];
            for (var i = 0; i < plane.length; i++) {
                coefficients[i] = plane[i] - 128;
            }
            Part2Forward.decompose(coefficients, writer.width, 0, 0, 0, 0, writer.width, writer.height,
                    Tree.dyadic(writer.levels), Part2Forward.DAUBECHIES);
            return coefficients;
        }

        private byte[] write(Part2Writer writer, float[] indices) {
            return writer.write(new float[][]{indices},
                    (values, out, w, h, stepSize, resolution, orientation, precinct) -> {
                        for (var i = 0; i < values.length; i++) {
                            out[i] = (int) values[i];
                        }
                    });
        }

        @ParameterizedTest(name = "w={0} respect={1} retained={2} minlevel={3}")
        @CsvSource({"6,false,9,1", "2,true,4,0", "4,false,2,2", "1,true,12,0"})
        @DisplayName("a masked 9-7 codestream decodes to the image within the quantization error, unlike unmasked")
        void roundTrip(int window, boolean respect, int retained, int minimumLevel) {
            var random = new Random(91 + window);
            var writer = irreversible(48, 40);
            var plane = smooth(random, 48, 40, 0, 255);
            var indices = maskAndQuantize(analyze(plane, writer), writer, minimumLevel, respect, window, retained,
                    90 / 128.0, 26 / 128.0);
            writer.mainSegments.add(vms(65535, minimumLevel, respect, window, retained, 90, 26));
            var data = write(writer, indices);
            var decoded = samples(decode(data).channels().getFirst());
            var error = maxError(plane, decoded);
            assertTrue(error <= 3, "round trip error " + error);
            var unmasked = data.clone();
            unmasked[indexOf(unmasked, Codestream.VMS) + 1] = (byte) 0x64;
            var ignored = samples(decode(unmasked).channels().getFirst());
            assertTrue(maxError(plane, ignored) > 20, "a decoder ignoring the masking would decode the image too");
        }

        @Test
        @DisplayName("a component VMS of the tile overrides every other, as A.3.2 orders them")
        void precedence() {
            var random = new Random(95);
            var writer = irreversible(32, 32);
            var plane = smooth(random, 32, 32, 0, 255);
            var indices = maskAndQuantize(analyze(plane, writer), writer, 1, false, 3, 9, 90 / 128.0, 26 / 128.0);
            writer.mainSegments.add(vms(65535, 0, false, 1, 12, 128, 0));
            writer.mainSegments.add(vms(0, 2, true, 5, 3, 60, 100));
            writer.tileSegments.add(vms(65535, 0, true, 2, 8, 100, 50));
            writer.tileSegments.add(vms(0, 1, false, 3, 9, 90, 26));
            var decoded = samples(decode(write(writer, indices)).channels().getFirst());
            assertTrue(maxError(plane, decoded) <= 3, "the tile's component VMS did not apply");
            var alone = irreversible(32, 32);
            alone.mainSegments.add(vms(0, 1, false, 3, 9, 90, 26));
            assertArrayEquals(decoded, samples(decode(write(alone, indices)).channels().getFirst()));
            // the tile's VMS for every component overrides the main header's for component 0
            var tileDefault = irreversible(32, 32);
            tileDefault.mainSegments.add(vms(0, 1, false, 3, 9, 90, 26));
            tileDefault.tileSegments.add(vms(65535, 0, true, 2, 8, 100, 50));
            assertTrue(maxError(decoded, samples(decode(write(tileDefault, indices)).channels().getFirst())) > 3);
            // the main header's VMS for component 0 overrides its VMS for every component
            var mainDefault = irreversible(32, 32);
            mainDefault.mainSegments.add(vms(65535, 0, false, 1, 12, 128, 0));
            mainDefault.mainSegments.add(vms(0, 1, false, 3, 9, 90, 26));
            assertArrayEquals(decoded, samples(decode(write(mainDefault, indices)).channels().getFirst()));
        }

        @Test
        @DisplayName("a reversible component decodes losslessly, as if no VMS segment applied")
        void reversible() {
            var random = new Random(96);
            var writer = new Part2Writer(30, 26, 1, 8, false);
            writer.rsiz |= RSIZ_VMS;
            writer.levels = 2;
            writer.mainSegments.add(vms(65535, 0, false, 2, 9, 90, 26));
            var plane = smooth(random, 30, 26, 0, 255);
            var data = writer.write(Jp2Part2Test.forward(writer, new long[][]{plane}, new long[]{128},
                    Tree.dyadic(2), null));
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("a reduced decode masks only the decoded resolutions and keeps their samples")
        void reduced() {
            var random = new Random(97);
            var writer = irreversible(48, 40);
            var plane = smooth(random, 48, 40, 0, 255);
            var indices = maskAndQuantize(analyze(plane, writer), writer, 0, false, 2, 12, 90 / 128.0, 26 / 128.0);
            writer.mainSegments.add(vms(65535, 0, false, 2, 12, 90, 26));
            var data = write(writer, indices);
            var reduced = decode(data, 1);
            assertEquals(24, reduced.width());
            assertEquals(20, reduced.height());
            var unmasked = data.clone();
            unmasked[indexOf(unmasked, Codestream.VMS) + 1] = (byte) 0x64;
            var a = samples(reduced.channels().getFirst());
            var b = samples(decode(unmasked, 1).channels().getFirst());
            assertTrue(maxError(a, b) > 10, "the reduced decode ignored the masking");
        }

        @Test
        @DisplayName("malformed VMS segments are rejected")
        void malformed() {
            var cases = new byte[][]{
                    Part2Forward.segment(Codestream.VMS, new byte[]{0, 0, 0, 1, 1, 1}),
                    vms(0, 0, false, 1, 1, 0, 1),
                    vms(0, 33, false, 1, 1, 1, 1),
                    vms(20000, 0, false, 1, 1, 1, 1),
                    vms(3, 0, false, 1, 1, 1, 1)
            };
            for (var segment : cases) {
                var writer = irreversible(16, 16);
                writer.mainSegments.add(segment);
                assertThrows(MediaException.Malformed.class, () -> decode(writer.write(new float[][]{new float[256]})));
            }
            var duplicate = irreversible(16, 16);
            duplicate.mainSegments.add(vms(0, 0, false, 1, 1, 1, 1));
            duplicate.mainSegments.add(vms(0, 1, false, 1, 1, 1, 1));
            assertThrows(MediaException.Malformed.class, () -> decode(duplicate.write(new float[][]{new float[256]})));
        }
    }

    @Nested
    @DisplayName("single sample overlap transformation (I.3)")
    class SampleOverlap {
        @Test
        @DisplayName("the 5-3 forward and inverse procedures match equations I-18 to I-24 on a hand-computed signal")
        void handComputed() {
            long[] signal = {3, 7, 1, 8, 2, 9, 4, 6, 5};
            // I-18 for the odd samples: 7 - (3 + 1) / 2 = 5, 8 - 3 / 2 = 7, 9 - 6 / 2 = 6, 6 - 9 / 2 = 2; I-21 for
            // the even ones off the cell boundaries 0, 4 and 8 of dC = 4: 1 + (5 + 7 + 2) / 4 = 4, 4 + (6 + 2 + 2) / 4
            // = 6; I-24 keeps 3, 2 and 5
            long[] coefficients = {3, 5, 4, 7, 2, 6, 6, 2, 5};
            var forward = signal.clone();
            Part2Forward.forwardOverlapped(forward, 0, Part2Forward.LE_GALL, 4);
            assertArrayEquals(coefficients, forward);
            var inverse = coefficients.clone();
            LiftingKernel.REVERSIBLE_53.inverseOverlapped(inverse, 9, 0, 1, 4);
            assertArrayEquals(signal, inverse);
            // without the overlap the boundary sample 0 is updated: 3 + (5 + 5 + 2) / 4 = 6
            var plain = signal.clone();
            Part2Forward.forward53(plain, 0);
            assertEquals(6, plain[0]);
        }

        @Test
        @DisplayName("the factors B_s of equation G-1 of the 9-7 wavelet")
        void boundaryFactors() {
            var factors = Part2Forward.boundaryFactors(Part2Forward.DAUBECHIES);
            assertEquals(1 + 2 * -1.586134342059924, factors[0], 1e-12);
            assertEquals(1 + 2 * -0.052980118572961 * factors[0], factors[1], 1e-12);
            // the 9-7 wavelet is normalized so that B_3 = K (G.2.1.2 with m_init = 0)
            assertEquals(1.230174104914001, factors[3], 1e-9);
            var inverse = new float[]{0, 0, 0, 0, 1, 0, 0, 0, 0};
            LiftingKernel.IRREVERSIBLE_97.inverseOverlapped(inverse, 9, 0, 1, 4);
            // an isolated boundary low-pass coefficient is scaled by K, then by 1 / B_3 and 1 / B_1, and spreads to
            // its odd neighbours only through the lifting of samples inside the cells
            assertEquals(1.230174104914001 / (factors[3] * factors[1]), inverse[4], 1e-5);
        }

        @ParameterizedTest(name = "period {0}")
        @ValueSource(longs = {1, 2, 4, 8, 16})
        @DisplayName("the reversible inverse procedures undo the forward ones of I.3.2.6.1 for every length and offset")
        void reversibleSignals(long period) {
            var random = new Random(period);
            var wide = Part2Forward.WIDE_SYMMETRIC.atk(3, 1, true);
            var kernels = new LiftingKernel[]{LiftingKernel.REVERSIBLE_53, LiftingKernel.parse(wide, 4,
                    wide.length).kernel()};
            var forms = new Part2Forward.Kernel[]{Part2Forward.LE_GALL, Part2Forward.WIDE_SYMMETRIC};
            for (var k = 0; k < kernels.length; k++) {
                for (var n = 1; n < 40; n++) {
                    for (var i0 = -5; i0 < 6; i0++) {
                        var signal = image(random, n, -500, 500);
                        var coefficients = signal.clone();
                        Part2Forward.forwardOverlapped(coefficients, i0, forms[k], period);
                        kernels[k].inverseOverlapped(coefficients, n, i0, 1, period);
                        assertArrayEquals(signal, coefficients, "kernel " + k + " n=" + n + " i0=" + i0);
                    }
                }
            }
        }

        @ParameterizedTest(name = "period {0}")
        @ValueSource(longs = {2, 4, 8, 32})
        @DisplayName("the irreversible inverse procedure undoes the forward one of I.3.2.6.2")
        void irreversibleSignals(long period) {
            var random = new Random(period + 100);
            for (var n = 2; n < 40; n++) {
                for (var i0 = -4; i0 < 5; i0++) {
                    var signal = new double[n];
                    for (var i = 0; i < n; i++) {
                        signal[i] = random.nextGaussian() * 100;
                    }
                    var coefficients = signal.clone();
                    Part2Forward.forwardOverlapped(coefficients, i0, Part2Forward.DAUBECHIES, period);
                    var floats = new float[n];
                    for (var i = 0; i < n; i++) {
                        floats[i] = (float) coefficients[i];
                    }
                    LiftingKernel.IRREVERSIBLE_97.inverseOverlapped(floats, n, i0, 1, period);
                    for (var i = 0; i < n; i++) {
                        assertEquals(signal[i], floats[i], 2e-3, "n=" + n + " i0=" + i0 + " sample " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("the samples of one cell depend on that cell only")
        void cellIndependence() {
            var random = new Random(5);
            var signal = image(random, 33, -300, 300);
            var coefficients = signal.clone();
            Part2Forward.forwardOverlapped(coefficients, 0, Part2Forward.WIDE_SYMMETRIC, 8);
            var changed = coefficients.clone();
            // change every coefficient outside the cell [8, 16]
            for (var i = 0; i < 33; i++) {
                if (i < 8 || i > 16) {
                    changed[i] += 37;
                }
            }
            var wide = Part2Forward.WIDE_SYMMETRIC.atk(3, 1, true);
            var kernel = LiftingKernel.parse(wide, 4, wide.length).kernel();
            kernel.inverseOverlapped(coefficients, 33, 0, 1, 8);
            kernel.inverseOverlapped(changed, 33, 0, 1, 8);
            for (var i = 9; i < 16; i++) {
                assertEquals(coefficients[i], changed[i], "sample " + i);
            }
        }

        private Part2Writer reversible(int width, int height, int cellX, int cellY) {
            var writer = new Part2Writer(width, height, 1, 8, false);
            writer.rsiz |= Codestream.RSIZ_SSO;
            writer.overlap = sso(cellX, cellY);
            writer.x0 = 3;
            writer.y0 = 2;
            writer.levels = 3;
            return writer;
        }

        private long[] analyze(Part2Writer writer, long[] plane, Tree tree, Part2Forward.Kernel kernel, int cellX,
                               int cellY) {
            var data = plane.clone();
            for (var i = 0; i < data.length; i++) {
                data[i] -= 128;
            }
            Part2Forward.decomposeOverlapped(data, writer.width, 0, 0, writer.x0, writer.y0, writer.x0 + writer.width,
                    writer.y0 + writer.height, tree, kernel, cellX, cellY, 0, 0);
            return data;
        }

        @ParameterizedTest(name = "cells 2^{0} x 2^{1}")
        @CsvSource({"4,3", "5,5", "2,4", "1,1", "0,6"})
        @DisplayName("a 5-3 codestream with the overlap decodes losslessly")
        void leGall(int cellX, int cellY) {
            var random = new Random(cellX * 7L + cellY);
            var writer = reversible(45, 37, cellX, cellY);
            var plane = smooth(random, 45, 37, 0, 255);
            var data = writer.write(new long[][]{analyze(writer, plane, Tree.dyadic(3), Part2Forward.LE_GALL, cellX,
                    cellY)});
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("the overlap is not ignored: the same coefficients without it decode to another image")
        void notIgnored() {
            var random = new Random(70);
            var writer = reversible(45, 37, 4, 4);
            var plane = smooth(random, 45, 37, 0, 255);
            var data = writer.write(new long[][]{analyze(writer, plane, Tree.dyadic(3), Part2Forward.LE_GALL, 4, 4)});
            var cod = indexOf(data, Codestream.COD);
            // clear the SSO flag of the SSO field that follows the transformation byte
            data[cod + 14] = 0;
            var decoded = samples(decode(data).channels().getFirst());
            assertTrue(maxError(plane, decoded) > 0, "the overlap made no difference");
        }

        @Test
        @DisplayName("a whole-sample symmetric ATK kernel of four taps with the overlap decodes losslessly")
        void wholeSampleKernel() {
            var random = new Random(71);
            var writer = reversible(50, 33, 4, 3);
            writer.rsiz |= Part2Segments.RSIZ_ATK | Part2Segments.RSIZ_WS;
            var atk = Part2Forward.WIDE_SYMMETRIC.atk(4, 1, true);
            writer.wavelet = 4;
            writer.kernel = LiftingKernel.parse(atk, 4, atk.length).kernel();
            writer.mainSegments.add(atk);
            var plane = smooth(random, 50, 33, 0, 255);
            var data = writer.write(new long[][]{analyze(writer, plane, Tree.dyadic(3), Part2Forward.WIDE_SYMMETRIC,
                    4, 3)});
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("an ATK kernel outside the whole-sample symmetric category is rejected with the overlap")
        void arbitraryKernel() {
            var writer = reversible(16, 16, 3, 3);
            writer.rsiz |= Part2Segments.RSIZ_ATK;
            var atk = Part2Forward.LE_GALL.atk(4, 1);
            writer.wavelet = 4;
            writer.kernel = LiftingKernel.parse(atk, 4, atk.length).kernel();
            writer.mainSegments.add(atk);
            var data = writer.write(new long[][]{new long[256]});
            assertThrows(MediaException.Malformed.class, () -> decode(data));
        }

        @Test
        @DisplayName("a DFS of one-direction splits with the overlap decodes losslessly")
        void resolutionSplits() {
            var random = new Random(72);
            var writer = reversible(44, 30, 5, 4);
            writer.rsiz |= Part2Segments.RSIZ_ADS;
            writer.decompositionByte = 0x82;
            writer.decomposition = new Decomposition(new int[]{2, 3, 1}, null, null);
            writer.mainSegments.add(Part2Segments.dfs(2, 2, 3, 1));
            var tree = Tree.of(HORIZONTAL, Tree.of(VERTICAL, Tree.of(JOINT), Tree.LEAF_NODE), Tree.LEAF_NODE);
            var plane = smooth(random, 44, 30, 0, 255);
            var data = writer.write(new long[][]{analyze(writer, plane, tree, Part2Forward.LE_GALL, 5, 4)});
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("a reduced decode gives the low-pass band of the forward transform's first level")
        void reduced() {
            var random = new Random(73);
            var writer = reversible(45, 37, 4, 4);
            var plane = smooth(random, 45, 37, 0, 255);
            var data = writer.write(new long[][]{analyze(writer, plane, Tree.dyadic(3), Part2Forward.LE_GALL, 4, 4)});
            var firstLevel = analyze(writer, plane, Tree.dyadic(1), Part2Forward.LE_GALL, 4, 4);
            var lowWidth = (int) (Part2Forward.ceilDiv2(48, 1) - Part2Forward.ceilDiv2(3, 1));
            var lowHeight = (int) (Part2Forward.ceilDiv2(39, 1) - Part2Forward.ceilDiv2(2, 1));
            var expected = new long[lowWidth * lowHeight];
            for (var y = 0; y < lowHeight; y++) {
                for (var x = 0; x < lowWidth; x++) {
                    expected[y * lowWidth + x] = Math.clamp(firstLevel[y * 45 + x] + 128, 0, 255);
                }
            }
            var reduced = decode(data, 1);
            assertEquals(lowWidth, reduced.width());
            assertArrayEquals(expected, samples(reduced.channels().getFirst()));
        }

        @Test
        @DisplayName("a 9-7 codestream with the overlap decodes within the quantization error")
        void daubechies() {
            var random = new Random(74);
            var writer = new Part2Writer(40, 36, 1, 8, false);
            writer.rsiz |= Codestream.RSIZ_SSO;
            writer.overlap = sso(4, 4);
            writer.wavelet = 0;
            writer.levels = 3;
            writer.quantizationStyle = Quantization.SCALAR_EXPOUNDED;
            writer.guardBits = 3;
            writer.exponent = 11;
            var plane = smooth(random, 40, 36, 0, 255);
            var coefficients = new double[plane.length];
            for (var i = 0; i < plane.length; i++) {
                coefficients[i] = plane[i] - 128;
            }
            Part2Forward.decomposeOverlapped(coefficients, 40, 0, 0, 0, 0, 40, 36, Tree.dyadic(3),
                    Part2Forward.DAUBECHIES, 4, 4, 0, 0);
            var floats = new float[plane.length];
            for (var i = 0; i < plane.length; i++) {
                floats[i] = (float) coefficients[i];
            }
            var data = writer.write(new float[][]{floats});
            var decoded = samples(decode(data).channels().getFirst());
            assertTrue(maxError(plane, decoded) <= 2, "round trip error " + maxError(plane, decoded));
            data[indexOf(data, Codestream.COD) + 14] = 0;
            assertTrue(maxError(plane, samples(decode(data).channels().getFirst())) > 2,
                    "the overlap made no difference");
        }

        /** The 5-3 wavelet as an irreversible whole-sample symmetric kernel, whose {@code B_0} of G-1 is zero. */
        private static final Part2Forward.Kernel IRREVERSIBLE_LE_GALL = new Part2Forward.Kernel(false, 1, true,
                new Part2Forward.Step(1, 0, new double[]{-0.5, -0.5}, 0, 0),
                new Part2Forward.Step(0, -1, new double[]{0.25, 0.25}, 0, 0));

        private static LiftingKernel irreversibleLeGallKernel() {
            var atk = IRREVERSIBLE_LE_GALL.atk(2, 2, true);
            return LiftingKernel.parse(atk, 4, atk.length).kernel();
        }

        @Test
        @DisplayName("the irreversible 5-3 kernel of I.3.4.2 keeps its cell boundaries, though its B_0 is zero")
        void irreversibleLeGall() {
            assertEquals(0, Part2Forward.boundaryFactors(IRREVERSIBLE_LE_GALL)[0]);
            var kernel = irreversibleLeGallKernel();
            var random = new Random(75);
            for (var n = 2; n < 30; n++) {
                for (var i0 = -3; i0 < 4; i0++) {
                    var signal = new double[n];
                    for (var i = 0; i < n; i++) {
                        signal[i] = random.nextGaussian() * 100;
                    }
                    var coefficients = signal.clone();
                    Part2Forward.forwardOverlapped(coefficients, i0, IRREVERSIBLE_LE_GALL, 4);
                    var floats = new float[n];
                    for (var i = 0; i < n; i++) {
                        floats[i] = (float) coefficients[i];
                    }
                    kernel.inverseOverlapped(floats, n, i0, 1, 4);
                    for (var i = 0; i < n; i++) {
                        assertEquals(signal[i], floats[i], 1e-3, "n=" + n + " i0=" + i0 + " sample " + i);
                    }
                }
            }
            // I-31: the forward procedure leaves an even sample on a cell boundary as it is (B_1 = 1)
            double[] ramp = {0, 10, 20, 30, 40};
            Part2Forward.forwardOverlapped(ramp, 0, IRREVERSIBLE_LE_GALL, 4);
            assertEquals(40, ramp[4], 1e-12);
            assertEquals(0, ramp[0], 1e-12);
            // cells of one sample put the odd samples on boundaries too, where 1 / B_0 is undefined
            assertThrows(MediaException.Malformed.class,
                    () -> kernel.inverseOverlapped(new float[]{1, 2, 3, 4}, 4, 0, 1, 1));
        }

        @Test
        @DisplayName("an irreversible whole-sample symmetric ATK kernel with the overlap decodes within the error")
        void irreversibleKernel() {
            var random = new Random(76);
            var writer = new Part2Writer(40, 36, 1, 8, false);
            writer.rsiz |= Codestream.RSIZ_SSO | Part2Segments.RSIZ_ATK | Part2Segments.RSIZ_WS;
            writer.overlap = sso(5, 4);
            writer.wavelet = 2;
            writer.levels = 3;
            writer.quantizationStyle = Quantization.SCALAR_EXPOUNDED;
            writer.guardBits = 3;
            writer.exponent = 11;
            var atk = IRREVERSIBLE_LE_GALL.atk(2, 2, true);
            writer.kernel = irreversibleLeGallKernel();
            writer.mainSegments.add(atk);
            var plane = smooth(random, 40, 36, 0, 255);
            var coefficients = new double[plane.length];
            for (var i = 0; i < plane.length; i++) {
                coefficients[i] = plane[i] - 128;
            }
            Part2Forward.decomposeOverlapped(coefficients, 40, 0, 0, 0, 0, 40, 36, Tree.dyadic(3),
                    IRREVERSIBLE_LE_GALL, 5, 4, 0, 0);
            var floats = new float[plane.length];
            for (var i = 0; i < plane.length; i++) {
                floats[i] = (float) coefficients[i];
            }
            var data = writer.write(new float[][]{floats});
            var decoded = samples(decode(data).channels().getFirst());
            assertTrue(maxError(plane, decoded) <= 2, "round trip error " + maxError(plane, decoded));
            data[indexOf(data, Codestream.COD) + 14] = 0;
            assertTrue(maxError(plane, samples(decode(data).channels().getFirst())) > 2,
                    "the overlap made no difference");
            // cells of 2^1 by 2^1 give cells of one sample at every level, whose odd samples meet B_0 = 0
            var single = writer.write(new float[][]{floats});
            single[indexOf(single, Codestream.COD) + 15] = (byte) sso(1, 1);
            assertThrows(MediaException.Malformed.class, () -> decode(single));
        }

        @Test
        @DisplayName("reserved bits and the undefined TBDWT flag of the SSO field are rejected")
        void reservedBits() {
            var reserved = reversible(16, 16, 3, 3);
            reserved.overlap |= 0x0100;
            var data = reserved.write(new long[][]{new long[256]});
            assertThrows(MediaException.Malformed.class, () -> decode(data));
            var tileBased = reversible(16, 16, 3, 3);
            tileBased.overlap |= ComponentCoding.OVERLAP_TILE_BASED | ComponentCoding.OVERLAP_TILES;
            var tbdwt = tileBased.write(new long[][]{new long[256]});
            assertThrows(MediaException.Unsupported.class, () -> decode(tbdwt));
            var truncated = new Part2Writer(16, 16, 1, 8, false);
            var plain = truncated.write(new long[][]{new long[256]});
            // declare the capability on a codestream whose COD segment has no SSO field
            plain[indexOf(plain, Codestream.SIZ) + 5] |= (byte) Codestream.RSIZ_SSO;
            assertThrows(MediaException.Malformed.class, () -> decode(plain));
        }
    }

    @Nested
    @DisplayName("tile single sample overlap (I.4)")
    class TileOverlap {
        private Part2Writer tiled(int width, int height, long tileWidth, long tileHeight, int horizontal,
                                  int vertical) {
            var writer = new Part2Writer(width, height, 1, 8, false);
            writer.rsiz |= Codestream.RSIZ_SSO;
            writer.overlap = ComponentCoding.OVERLAP_TILES | (horizontal == 1 ? ComponentCoding.OVERLAP_HORIZONTAL : 0)
                    | (vertical == 1 ? ComponentCoding.OVERLAP_VERTICAL : 0);
            writer.tileWidth = tileWidth;
            writer.tileHeight = tileHeight;
            return writer;
        }

        @ParameterizedTest(name = "Hovlp={0}")
        @ValueSource(ints = {0, 1})
        @DisplayName("tiles cover the areas of equations I-46 to I-49 and of the example of O.2.3")
        void geometry(int horizontal) {
            var writer = tiled(512, 512, 128, 128, horizontal, horizontal);
            var data = writer.writeTiles((tile, bounds) -> new long[][]{
                    new long[(int) ((bounds[2] - bounds[0]) * (bounds[3] - bounds[1]))]}, 9);
            var codestream = Codestream.parse(data, 0, data.length, LIMITS);
            long[] starts = horizontal == 1 ? new long[]{0, 128, 256, 384} : new long[]{0, 127, 255, 383};
            long[] lasts = horizontal == 1 ? new long[]{128, 256, 384, 511} : new long[]{127, 255, 383, 511};
            for (var p = 0; p < 4; p++) {
                var bounds = codestream.codingBounds(p);
                assertEquals(starts[p], bounds[0], "tx0 of tile " + p);
                assertEquals(lasts[p], bounds[2] - 1, "tx1 - 1 of tile " + p);
                var column = codestream.codingBounds(4 * p);
                assertEquals(starts[p], column[1], "ty0 of tile row " + p);
                assertEquals(lasts[p], column[3] - 1, "ty1 - 1 of tile row " + p);
                assertArrayEquals(new long[]{128L * p, 0, 128L * p + 128, 128}, codestream.tileBounds(p));
            }
            var decoded = samples(decode(data).channels().getFirst());
            assertEquals(512 * 512, decoded.length);
            assertTrue(Arrays.stream(decoded).allMatch(value -> value == 128));
        }

        /** Returns the nominal area of a tile of a writer with tiles anchored at the origin, clipped to the image. */
        private long[] own(Part2Writer writer, int tile) {
            var tilesWide = (int) Math.ceilDiv(writer.width, writer.tileWidth);
            var p = tile % tilesWide;
            var q = tile / tilesWide;
            return new long[]{p * writer.tileWidth, q * writer.tileHeight,
                    Math.min((p + 1) * writer.tileWidth, writer.width),
                    Math.min((q + 1) * writer.tileHeight, writer.height)};
        }

        /**
         * Returns the level-shifted samples a tile codes for one component: the image's inside the tile's own area,
         * others in the column and row it shares with a neighbour.
         */
        private long[] marked(Part2Writer writer, long[] plane, int c, int tile, long[] bounds) {
            var own = own(writer, tile);
            var sx = writer.subsamplingX[c];
            var sy = writer.subsamplingY[c];
            var fullWidth = (int) Math.ceilDiv(writer.width, sx);
            var cx0 = Math.ceilDiv(bounds[0], sx);
            var cy0 = Math.ceilDiv(bounds[1], sy);
            var width = (int) (Math.ceilDiv(bounds[2], sx) - cx0);
            var height = (int) (Math.ceilDiv(bounds[3], sy) - cy0);
            var data = new long[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var u = cx0 + x;
                    var v = cy0 + y;
                    var owned = u >= Math.ceilDiv(own[0], sx) && u < Math.ceilDiv(own[2], sx)
                            && v >= Math.ceilDiv(own[1], sy) && v < Math.ceilDiv(own[3], sy);
                    var value = plane[(int) (v * fullWidth + u)];
                    data[y * width + x] = (owned ? value : 255 - value) - 128;
                }
            }
            return data;
        }

        /**
         * Codes the tiles of an image with the 5-3 wavelet, each tile's samples outside its own area replaced by
         * others, so that a decoder taking a sample two tiles reconstruct from the wrong one gives another image.
         */
        private byte[] code(Part2Writer writer, long[][] planes, int levels, boolean overlapTransform) {
            writer.levels = levels;
            return writer.writeTiles((tile, bounds) -> {
                var result = new long[planes.length][];
                for (var c = 0; c < planes.length; c++) {
                    var sx = writer.subsamplingX[c];
                    var sy = writer.subsamplingY[c];
                    var cx0 = Math.ceilDiv(bounds[0], sx);
                    var cy0 = Math.ceilDiv(bounds[1], sy);
                    var cx1 = Math.ceilDiv(bounds[2], sx);
                    var cy1 = Math.ceilDiv(bounds[3], sy);
                    var width = (int) (cx1 - cx0);
                    var data = marked(writer, planes[c], c, tile, bounds);
                    if (overlapTransform) {
                        Part2Forward.decomposeOverlapped(data, width, 0, 0, cx0, cy0, cx1, cy1, Tree.dyadic(levels),
                                Part2Forward.LE_GALL, writer.overlap & 0xF, writer.overlap >> 4 & 0xF, 0, 0);
                    } else {
                        Part2Forward.decompose(data, width, 0, 0, cx0, cy0, cx1, cy1, Tree.dyadic(levels), null);
                    }
                    result[c] = data;
                }
                return result;
            }, 10);
        }

        @ParameterizedTest(name = "Hovlp={0} Vovlp={1}")
        @CsvSource({"0,0", "1,1", "0,1", "1,0"})
        @DisplayName("each sample comes from the tile of its own area, as I.4.3 rules")
        void ownership(int horizontal, int vertical) {
            var random = new Random(80 + 2L * horizontal + vertical);
            var writer = tiled(40, 36, 16, 12, horizontal, vertical);
            var plane = smooth(random, 40, 36, 0, 255);
            var data = code(writer, new long[][]{plane}, 2, false);
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("a subsampled component takes the samples of the tile whose own area covers them")
        void subsampled() {
            var random = new Random(84);
            var writer = new Part2Writer(41, 35, 2, 8, false);
            writer.rsiz |= Codestream.RSIZ_SSO;
            writer.overlap = ComponentCoding.OVERLAP_TILES | ComponentCoding.OVERLAP_HORIZONTAL;
            writer.tileWidth = 16;
            writer.tileHeight = 16;
            writer.subsamplingX[1] = 2;
            writer.subsamplingY[1] = 2;
            var planes = new long[][]{smooth(random, 41, 35, 0, 255), smooth(random, 21, 18, 0, 255)};
            var data = code(writer, planes, 1, false);
            assertChannels(planes, decode(data));
        }

        @Test
        @DisplayName("combined with the overlap transformation, each tile is transformed on its own (I.5)")
        void withSampleOverlap() {
            var random = new Random(85);
            var writer = tiled(48, 40, 16, 16, 1, 1);
            writer.overlap |= sso(3, 3);
            var plane = smooth(random, 48, 40, 0, 255);
            var data = code(writer, new long[][]{plane}, 2, true);
            assertChannels(new long[][]{plane}, decode(data));
        }

        @Test
        @DisplayName("a reduced decode keeps the reduced area of each tile")
        void reduced() {
            var random = new Random(86);
            var writer = tiled(40, 36, 16, 12, 0, 0);
            var plane = smooth(random, 40, 36, 0, 255);
            var data = code(writer, new long[][]{plane}, 2, false);
            var full = samples(decode(data).channels().getFirst());
            assertArrayEquals(plane, full);
            var reduced = decode(data, 1);
            assertEquals(20, reduced.width());
            assertEquals(18, reduced.height());
            // the reduced decode of a tile is the low-pass band of the first level of its transform, of which the
            // image keeps the samples of the tile's own reduced area
            var expected = new long[20 * 18];
            for (var tile = 0; tile < 9; tile++) {
                var bounds = writer.codingBounds(tile);
                var data1 = marked(writer, plane, 0, tile, bounds);
                var width = (int) (bounds[2] - bounds[0]);
                Part2Forward.decompose(data1, width, 0, 0, bounds[0], bounds[1], bounds[2], bounds[3],
                        Tree.dyadic(1), null);
                var lowX0 = Part2Forward.ceilDiv2(bounds[0], 1);
                var lowY0 = Part2Forward.ceilDiv2(bounds[1], 1);
                var own = own(writer, tile);
                for (var v = Part2Forward.ceilDiv2(own[1], 1); v < Part2Forward.ceilDiv2(own[3], 1); v++) {
                    for (var u = Part2Forward.ceilDiv2(own[0], 1); u < Part2Forward.ceilDiv2(own[2], 1); u++) {
                        var value = data1[(int) ((v - lowY0) * width + u - lowX0)];
                        expected[(int) (v * 20 + u)] = Math.clamp(value + 128, 0, 255);
                    }
                }
            }
            assertArrayEquals(expected, samples(reduced.channels().getFirst()));
            var region = Jp2Decoder.decodeChannels(MediaSource.of(data), Jp2DecoderOptions.builder()
                    .region(new Region(5, 7, 22, 19)).limits(LIMITS).build());
            var window = samples(region.channels().getFirst());
            for (var y = 0; y < 19; y++) {
                for (var x = 0; x < 22; x++) {
                    assertEquals(plane[(7 + y) * 40 + 5 + x], window[y * 22 + x], "sample " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("coding style segments that disagree on the tile overlap are rejected")
        void inconsistent() {
            var writer = tiled(32, 32, 16, 16, 0, 0);
            var coc = new ByteArrayOutputStream();
            coc.write(0);
            coc.write(0);
            coc.write(0);
            coc.write(2);
            coc.write(2);
            coc.write(0);
            coc.write(1);
            Part2Forward.u16(coc, 0);
            writer.mainSegments.add(Part2Forward.segment(Codestream.COC, coc.toByteArray()));
            var data = code(writer, new long[][]{new long[1024]}, 0, false);
            assertThrows(MediaException.Malformed.class, () -> decode(data));
        }
    }

    @Nested
    @DisplayName("damaged codestreams")
    class Robustness {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"masking.j2k", "overlap.j2k", "tile-overlap.j2k"})
        @DisplayName("every truncation and single-byte corruption decodes or fails with a media exception")
        void damaged(String name) {
            var data = Fixtures.read(Path.of("src", "test",
                    "resources", "com", "github", "auties00", "smalt", "image", "jp2", "part2", name));
            assertTrue(decodable(data), "the sample itself decodes");
            var random = new Random(name.hashCode());
            for (var length = 0; length < data.length; length += 1 + length / 16) {
                decodable(Arrays.copyOf(data, length));
            }
            for (var i = 0; i < 400; i++) {
                var damaged = data.clone();
                damaged[random.nextInt(damaged.length)] = (byte) random.nextInt(256);
                decodable(damaged);
            }
        }

        private boolean decodable(byte[] data) {
            try {
                Jp2Decoder.decodeChannels(MediaSource.of(data), Jp2DecoderOptions.builder().limits(LIMITS).build());
                return true;
            } catch (MediaException expected) {
                return false;
            }
        }
    }
}
