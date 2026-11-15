package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the internals of the tint, pixel-shift merge and lens correction code on synthetic inputs: closed forms,
 * exact reconstructions and fits of known polynomials.
 */
class RenderingControlsTest {
    @Nested
    @DisplayName("tint")
    class Tint {
        /**
         * Krystek's locus, differentiated analytically (the code under test differentiates numerically).
         */
        private static double[] normal(double t) {
            double nu = 0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t;
            double du = 1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t;
            double nv = 0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t;
            double dv = 1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t;
            var duDt = ((1.54118254e-4 + 2 * 1.28641212e-7 * t) * du - nu * (8.42420235e-4 + 2 * 7.08145163e-7 * t))
                    / (du * du);
            var dvDt = ((4.22806245e-5 + 2 * 4.20481691e-8 * t) * dv - nv * (-2.89741816e-5 + 2 * 1.61456053e-7 * t))
                    / (dv * dv);
            var length = Math.hypot(duDt, dvDt);
            var n = new double[]{-dvDt / length, duDt / length};
            return n[1] >= 0 ? n : new double[]{-n[0], -n[1]};
        }

        private static double[] uv(double[] xy) {
            var d = -2 * xy[0] + 12 * xy[1] + 3;
            return new double[]{4 * xy[0] / d, 6 * xy[1] / d};
        }

        @ParameterizedTest
        @CsvSource({"2500, 30", "3200, -75", "4000, 10", "5500, 150", "6500, -150", "12000, 60"})
        @DisplayName("moves the chromaticity along the Planckian normal by tint / 3000 in CIE 1960 uv")
        void closedForm(double kelvin, double tint) {
            var base = uv(ColorMath.chromaticity(kelvin));
            var moved = uv(ColorMath.chromaticity(kelvin, tint));
            var n = normal(kelvin);
            // the code differentiates the locus numerically, which bounds the direction to about 1e-6
            assertEquals(base[0] + tint / 3000 * n[0], moved[0], 1e-7);
            assertEquals(base[1] + tint / 3000 * n[1], moved[1], 1e-7);
            assertEquals(Math.abs(tint) / 3000, Math.hypot(moved[0] - base[0], moved[1] - base[1]), 1e-9);
        }

        @Test
        @DisplayName("Krystek's locus agrees with the Planckian chromaticity of the temperature")
        void locus() {
            for (var kelvin : new double[]{2000, 3000, 3900}) {
                var a = ColorMath.planckianUv(kelvin);
                var b = uv(ColorMath.chromaticity(kelvin));
                // two approximations of the Planckian locus, Krystek's and the cubic splines of the chromaticity
                assertEquals(a[0], b[0], 5e-4);
                assertEquals(a[1], b[1], 5e-4);
            }
        }
    }

    @Nested
    @DisplayName("pixel shift")
    class Shift {
        private static final CfaPattern RGGB = CfaPattern.bayer("RGGB");

        /**
         * Four frames of a random scene of red, green and blue at every pixel, taken with the Pentax offsets: frame
         * {@code i} holds at photosite (x, y) the scene at (x - dx, y - dy) in the photosite's color.
         */
        private static List<Sensor> frames(int[][] scene, int width, int height, int[] dx, int[] dy) {
            var result = new ArrayList<Sensor>();
            for (var frame = 0; frame < 4; frame++) {
                var shiftX = dx[frame];
                var shiftY = dy[frame];
                var sensor = new Sensor();
                sensor.width = width;
                sensor.height = height;
                sensor.cfa = RGGB;
                sensor.blackRows = 2;
                sensor.blackColumns = 2;
                sensor.black = new double[]{64, 64, 64, 64};
                sensor.reader = (plane, limits) -> {
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < width; x++) {
                            var color = RGGB.color(y % 2, x % 2).code();
                            var sx = Math.clamp(x - shiftX, 0, width - 1);
                            var sy = Math.clamp(y - shiftY, 0, height - 1);
                            plane.integers[y * width + x] = (short) scene[color][sy * width + sx];
                        }
                    }
                };
                result.add(sensor);
            }
            return result;
        }

        @Test
        @DisplayName("reconstructs the scene exactly away from the edges, greens averaged")
        void reconstruct() {
            var width = 24;
            var height = 18;
            var random = new Random(7);
            var scene = new int[3][width * height];
            for (var color = 0; color < 3; color++) {
                for (var index = 0; index < scene[color].length; index++) {
                    scene[color][index] = 64 + random.nextInt(16000);
                }
            }
            var merged = PixelShift.merge(frames(scene, width, height, PixelShift.PENTAX_DX, PixelShift.PENTAX_DY),
                    PixelShift.PENTAX_DX, PixelShift.PENTAX_DY);
            assertNotNull(merged);
            assertEquals(3, merged.samplesPerPixel);
            assertArrayEquals(new double[]{64, 64, 64}, merged.black);
            var plane = new SensorPlane(width, height, 3, false, MediaLimits.DEFAULT);
            merged.reader.read(plane, MediaLimits.DEFAULT);
            for (var y = 0; y < height - 1; y++) {
                for (var x = 0; x < width - 1; x++) {
                    for (var color = 0; color < 3; color++) {
                        assertEquals(scene[color][y * width + x], plane.integers[(y * width + x) * 3 + color] & 0xFFFF,
                                "pixel " + x + "," + y + " color " + color);
                    }
                }
            }
        }

        @Test
        @DisplayName("frames given in another order do not reconstruct the scene")
        void orderMatters() {
            var width = 8;
            var height = 8;
            var scene = new int[3][width * height];
            for (var index = 0; index < width * height; index++) {
                scene[0][index] = 100 + index;
                scene[1][index] = 3000 + index;
                scene[2][index] = 9000 + index;
            }
            int[] wrongX = {1, 0, 0, 1};
            int[] wrongY = {0, 1, 1, 0};
            var merged = PixelShift.merge(frames(scene, width, height, wrongX, wrongY), PixelShift.PENTAX_DX,
                    PixelShift.PENTAX_DY);
            var plane = new SensorPlane(width, height, 3, false, MediaLimits.DEFAULT);
            merged.reader.read(plane, MediaLimits.DEFAULT);
            var mismatches = 0;
            for (var index = 0; index < width * height; index++) {
                if ((plane.integers[index * 3] & 0xFFFF) != scene[0][index]) {
                    mismatches++;
                }
            }
            assertTrue(mismatches > 10);
        }

        @Test
        @DisplayName("a truncated frame contributes 0, not the previous frame's photosites, where its data ends")
        void truncatedFrame() {
            var width = 8;
            var height = 8;
            var scene = new int[3][width * height];
            for (var color = 0; color < 3; color++) {
                Arrays.fill(scene[color], 1000 * (color + 1));
            }
            var frames = frames(scene, width, height, PixelShift.PENTAX_DX, PixelShift.PENTAX_DY);
            var whole = frames.get(2).reader;
            // a reader that stops early leaves the rest of the plane as it found it
            frames.get(2).reader = (plane, limits) -> {
                var full = new SensorPlane(width, height, 1, false, limits);
                whole.read(full, limits);
                System.arraycopy(full.integers, 0, plane.integers, 0, width * height / 2);
            };
            var merged = PixelShift.merge(frames, PixelShift.PENTAX_DX, PixelShift.PENTAX_DY);
            var plane = new SensorPlane(width, height, 3, false, MediaLimits.DEFAULT);
            merged.reader.read(plane, MediaLimits.DEFAULT);
            // frame 2 (offset 1, 1) holds the blue photosite of the pixels of even row and column
            for (var y = 0; y < height; y += 2) {
                for (var x = 0; x < width; x += 2) {
                    var blue = plane.integers[(y * width + x) * 3 + 2] & 0xFFFF;
                    assertEquals(y + 1 < height / 2 ? 3000 : 0, blue, "pixel " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("frames of different levels are not merged")
        void levels() {
            var scene = new int[3][16];
            var frames = frames(scene, 4, 4, PixelShift.PENTAX_DX, PixelShift.PENTAX_DY);
            frames.get(2).black = new double[]{60, 64, 64, 64};
            assertNull(PixelShift.merge(frames, PixelShift.PENTAX_DX, PixelShift.PENTAX_DY));
        }
    }

    @Nested
    @DisplayName("lens corrections")
    class Lens {
        /**
         * Reads the WarpRectilinear coefficients and the FixVignetteRadial coefficients of a list built by
         * {@link LensCorrection#opcodes}.
         */
        private static double[][] read(byte[] list) {
            var buffer = ByteBuffer.wrap(list);
            var count = buffer.getInt();
            double[][] result = new double[4][];
            for (var op = 0; op < count; op++) {
                var id = buffer.getInt();
                assertEquals(0x01030000, buffer.getInt());
                buffer.getInt();
                var length = buffer.getInt();
                var start = buffer.position();
                if (id == 1) {
                    assertEquals(3, buffer.getInt());
                    for (var plane = 0; plane < 3; plane++) {
                        result[plane] = new double[6];
                        for (var k = 0; k < 6; k++) {
                            result[plane][k] = buffer.getDouble();
                        }
                    }
                } else {
                    assertEquals(3, id);
                    result[3] = new double[7];
                    for (var k = 0; k < 7; k++) {
                        result[3][k] = buffer.getDouble();
                    }
                }
                buffer.position(start + length);
            }
            return result;
        }

        @Test
        @DisplayName("fits a distortion that is a polynomial of the WarpRectilinear form exactly")
        void exactFit() {
            double[] k = {1.02, -0.08, 0.011, -0.0012};
            var width = 600;
            var height = 400;
            var corner = Math.hypot(300, 200);
            var radius = 250.0;
            var knots = new double[200];
            var values = new double[3][200];
            for (var index = 0; index < knots.length; index++) {
                knots[index] = index * 0.01;
                // the factor at radius u * radius, in the image's normalization r = u * radius / corner
                var r = knots[index] * radius / corner;
                var f = k[0] + r * r * (k[1] + r * r * (k[2] + r * r * k[3]));
                values[0][index] = f * 1.001;
                values[1][index] = f;
                values[2][index] = f * 0.999;
            }
            var lens = new LensCorrection(300, 200, radius, knots, values, null, null);
            var parsed = read(lens.opcodes(width, height));
            // linear interpolation between knots 0.01 apart leaves errors near 1e-6, which the fit amplifies
            assertArrayEquals(k, java.util.Arrays.copyOf(parsed[1], 4), 1e-4);
            assertEquals(k[0] * 1.001, parsed[0][0], 1e-4);
            assertEquals(k[0] * 0.999, parsed[2][0], 1e-4);
            assertEquals(0, parsed[1][4]);
            assertNull(parsed[3]);
        }

        @Test
        @DisplayName("fits a vignetting gain and puts it before the warp")
        void vignette() {
            var knots = new double[]{0, 0.5, 1, 1.5};
            var gains = new double[]{1, 1.1, 1.5, 2};
            var flat = new double[][]{{1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}};
            var lens = new LensCorrection(50, 50, Math.hypot(50, 50), knots, flat, knots, gains);
            var list = lens.opcodes(100, 100);
            assertEquals(1, ByteBuffer.wrap(list).getInt());
            var parsed = read(list);
            var k = parsed[3];
            var gain = 1 + k[0] + k[1] + k[2] + k[3] + k[4];
            assertEquals(1.5, gain, 0.02);
            assertEquals(0.5, k[5], 1e-12);
        }

        @Test
        @DisplayName("corrections of pixels half as wide as high give the opcodes of the square image")
        void aspect() {
            var knots = new double[]{0, 0.5, 1, 1.5};
            var factors = new double[][]{{1, 0.98, 0.93, 0.85}, {1, 0.97, 0.9, 0.8}, {1, 0.96, 0.88, 0.78}};
            var gains = new double[]{1, 1.1, 1.5, 2};
            var square = new LensCorrection(140, 90, 150, knots, factors, knots, gains);
            var stretched = square.withAspect(0.5);
            assertEquals(280, stretched.centerX, 1e-12);
            assertArrayEquals(square.opcodes(300, 200), stretched.opcodes(600, 200));
            var list = square.opcodes(300, 200);
            var flat = new float[3][300 * 200];
            var wide = new float[3][600 * 200];
            var random = new Random(7);
            for (var row = 0; row < 200; row++) {
                for (var column = 0; column < 300; column++) {
                    // a smooth pattern, so that resampling either image samples the same function
                    var value = (float) (0.3 + 0.1 * Math.sin(column / 23.0) * Math.cos(row / 17.0));
                    for (var plane = 0; plane < 3; plane++) {
                        flat[plane][row * 300 + column] = value;
                    }
                }
                for (var column = 0; column < 600; column++) {
                    var value = (float) (0.3 + 0.1 * Math.sin(column / 46.0) * Math.cos(row / 17.0));
                    for (var plane = 0; plane < 3; plane++) {
                        wide[plane][row * 600 + column] = value;
                    }
                }
            }
            Opcodes.applyToRgb(flat, 300, 200, list, null, 1);
            Opcodes.applyToRgb(wide, 600, 200, list, null, 0.5);
            var worst = 0.0;
            for (var sample = 0; sample < 2000; sample++) {
                var row = 4 + random.nextInt(192);
                var column = 4 + random.nextInt(292);
                for (var plane = 0; plane < 3; plane++) {
                    worst = Math.max(worst, Math.abs(flat[plane][row * 300 + column]
                            - wide[plane][row * 600 + 2 * column]));
                }
            }
            // the pattern's slope times the half-pixel difference of the two centers, plus resampling
            assertTrue(worst < 0.004, "largest difference " + worst);
            // the same list applied as if the pixels were square differs, so the test sees the aspect
            var unscaled = new float[3][600 * 200];
            for (var row = 0; row < 200; row++) {
                for (var column = 0; column < 600; column++) {
                    var value = (float) (0.3 + 0.1 * Math.sin(column / 46.0) * Math.cos(row / 17.0));
                    for (var plane = 0; plane < 3; plane++) {
                        unscaled[plane][row * 600 + column] = value;
                    }
                }
            }
            Opcodes.applyToRgb(unscaled, 600, 200, list, null, 1);
            var ignored = 0.0;
            for (var row = 4; row < 196; row += 8) {
                for (var column = 4; column < 296; column += 8) {
                    ignored = Math.max(ignored, Math.abs(flat[1][row * 300 + column]
                            - unscaled[1][row * 600 + 2 * column]));
                }
            }
            assertTrue(ignored > 10 * worst, "difference without the aspect " + ignored);
        }

        @Test
        @DisplayName("the rendering applies the vendor corrections only when asked and when the file has no list 3")
        void thirdList() {
            var sensor = new Sensor();
            sensor.lens = VendorLens.olympus(new double[]{-0.08, 0.009, -0.001, 1.02}, null, null, 40, 30);
            var on = RawDecoderOptions.defaults();
            var off = on.toBuilder().lensCorrections(false).build();
            assertArrayEquals(sensor.lens.opcodes(40, 30), Renderer.thirdList(sensor, on, 40, 30));
            assertNull(Renderer.thirdList(sensor, off, 40, 30));
            sensor.opcodes3 = new byte[]{0, 0, 0, 0};
            // the file's own list replaces the vendor data, and the opcodes option alone switches it off
            assertArrayEquals(sensor.opcodes3, Renderer.thirdList(sensor, off, 40, 30));
            assertNull(Renderer.thirdList(sensor, on.toBuilder().opcodes(false).build(), 40, 30));
            assertTrue(off.toBuilder().build().equals(off) && !off.equals(on));
        }

        @Test
        @DisplayName("random vendor data gives no list or a finite one that applies")
        void garbage() {
            var random = new Random(11);
            var applied = 0;
            for (var round = 0; round < 300; round++) {
                var panasonic = new byte[32];
                var aberration = new byte[64];
                random.nextBytes(panasonic);
                random.nextBytes(aberration);
                var floats = new double[24];
                for (var index = 0; index < floats.length; index++) {
                    floats[index] = switch (random.nextInt(6)) {
                        case 0 -> 0;
                        case 1 -> -random.nextDouble() * 200;
                        case 2 -> random.nextDouble() * 1e6;
                        case 3 -> random.nextDouble() * 1e-6;
                        default -> random.nextGaussian() * 2;
                    };
                }
                var crop = random.nextBoolean() ? new double[]{2, 2, 12, 8} : null;
                var lenses = new LensCorrection[]{
                        VendorLens.panasonic(panasonic, aberration, crop, 16, 12),
                        VendorLens.panasonic(panasonic, null, crop, 16, 12),
                        VendorLens.olympus(Arrays.copyOf(floats, 4), Arrays.copyOfRange(floats, 4, 10), crop, 16, 12),
                        VendorLens.fujifilm(Arrays.copyOf(floats, 7 + random.nextInt(4)),
                                Arrays.copyOfRange(floats, 3, 10 + random.nextInt(6)),
                                Arrays.copyOfRange(floats, 9, 14 + random.nextInt(6)), crop, 16, 12)};
                for (var lens : lenses) {
                    if (lens == null) {
                        continue;
                    }
                    var list = lens.opcodes(16, 12);
                    if (list == null) {
                        continue;
                    }
                    var fitted = read(list);
                    for (var function : fitted) {
                        if (function != null) {
                            for (var value : function) {
                                assertTrue(Double.isFinite(value), "coefficient " + value);
                            }
                        }
                    }
                    var planes = new float[3][16 * 12];
                    for (var plane : planes) {
                        Arrays.fill(plane, 0.5f);
                    }
                    Opcodes.applyToRgb(planes, 16, 12, list, null, random.nextBoolean() ? 1 : 0.5);
                    applied++;
                }
            }
            assertTrue(applied > 100, "lists applied " + applied);
        }

        @Test
        @DisplayName("corrections that change nothing give no list")
        void identity() {
            var knots = new double[]{0, 1};
            var flat = new double[][]{{1, 1}, {1, 1}, {1, 1}};
            assertNull(new LensCorrection(1, 1, 1, knots, flat, null, null).opcodes(2, 2));
        }

        @Test
        @DisplayName("the Panasonic model inverts the vendor's forward polynomial")
        void panasonic() {
            var tag = new byte[32];
            short[] fields = new short[16];
            fields[7] = 1;
            fields[12] = 1000;
            fields[8] = 3277;
            fields[5] = -32;
            for (var index = 0; index < 16; index++) {
                tag[2 * index] = (byte) fields[index];
                tag[2 * index + 1] = (byte) (fields[index] >> 8);
            }
            var lens = VendorLens.panasonic(tag, null, new double[]{0, 0, 1600, 1200}, 1600, 1200);
            assertNotNull(lens);
            var scale = 1 / (1 + 32 / 32768.0);
            var a2 = 3277 / 32768.0;
            for (var u = 0.1; u < 1.2; u += 0.1) {
                var f = LensCorrection.evaluate(lens.distortionKnots, lens.distortion[1], u);
                // the source radius u * f / scale maps forward onto u
                var source = u * f / scale;
                assertEquals(u, source * (1 + a2 * source * source), 2e-4, "u " + u);
            }
            fields[7] = 0;
            tag[14] = 0;
            assertNull(VendorLens.panasonic(tag, null, null, 1600, 1200));
        }

        @Test
        @DisplayName("the Panasonic chromatic aberration displaces red and blue at the green source radius")
        void panasonicAberration() {
            var distortion = new short[16];
            distortion[7] = 1;
            distortion[12] = 1000;
            distortion[8] = -3277;
            var aberration = new short[32];
            aberration[17] = 300;
            aberration[16] = 600;
            aberration[4] = 750;
            aberration[11] = 900;
            short[] red = {400, 900, 1500, 2000};
            short[] blue = {-200, -500, -800, -1200};
            int[] redFields = {12, 23, 8, 26};
            int[] blueFields = {27, 10, 20, 29};
            for (var index = 0; index < 4; index++) {
                aberration[redFields[index]] = red[index];
                aberration[blueFields[index]] = blue[index];
            }
            // garbage in the fields the layout does not use must not matter
            aberration[0] = 12345;
            aberration[13] = -4321;
            aberration[31] = 777;
            var lens = VendorLens.panasonic(bytes(distortion), bytes(aberration), new double[]{0, 0, 1600, 1200},
                    1600, 1200);
            assertNotNull(lens);
            double[] radii = {0, 300, 600, 750, 900};
            for (var u = 0.05; u < 0.9; u += 0.05) {
                var green = u * 1000 * LensCorrection.evaluate(lens.distortionKnots, lens.distortion[1], u);
                var redSource = u * 1000 * LensCorrection.evaluate(lens.distortionKnots, lens.distortion[0], u);
                var blueSource = u * 1000 * LensCorrection.evaluate(lens.distortionKnots, lens.distortion[2], u);
                var expectedRed = LensCorrection.evaluate(radii, new double[]{0, 0.4, 0.9, 1.5, 2.0}, green);
                var expectedBlue = LensCorrection.evaluate(radii, new double[]{0, -0.2, -0.5, -0.8, -1.2}, green);
                // the knots of the sampled model are 1/64 apart, so the interpolation of the factor adds a little
                assertEquals(expectedRed, green - redSource, 0.02, "red at u " + u);
                assertEquals(expectedBlue, green - blueSource, 0.02, "blue at u " + u);
            }
            var alone = VendorLens.panasonic(null, bytes(aberration), null, 1600, 1200);
            assertNotNull(alone);
            assertEquals(900, alone.radius, 1e-12);
            assertEquals(1, LensCorrection.evaluate(alone.distortionKnots, alone.distortion[1], 0.5), 1e-12);
            assertNull(VendorLens.panasonic(null, new byte[64], null, 1600, 1200));
            aberration[16] = 200;
            assertNull(VendorLens.panasonic(null, bytes(aberration), null, 1600, 1200));
        }

        private static byte[] bytes(short[] fields) {
            var tag = new byte[2 * fields.length];
            for (var index = 0; index < fields.length; index++) {
                tag[2 * index] = (byte) fields[index];
                tag[2 * index + 1] = (byte) (fields[index] >> 8);
            }
            return tag;
        }

        @Test
        @DisplayName("the Olympus model scales its radius by the crop's half diagonal and k0^1.5")
        void olympus() {
            var lens = VendorLens.olympus(new double[]{-0.08, 0.009, -0.001, 1.02}, null, new double[]{8, 8, 400, 300},
                    416, 316);
            assertNotNull(lens);
            assertEquals(Math.hypot(200, 150) / Math.pow(1.02, 1.5), lens.radius, 1e-9);
            assertEquals(208, lens.centerX, 1e-12);
            assertEquals(158, lens.centerY, 1e-12);
            var u = 0.7;
            assertEquals(1.02 - 0.08 * u * u + 0.009 * Math.pow(u, 4) - 0.001 * Math.pow(u, 6),
                    LensCorrection.evaluate(lens.distortionKnots, lens.distortion[1], u), 1e-4);
            assertNull(VendorLens.olympus(new double[]{Double.NaN, 0, 0, 1}, null, null, 10, 10));
        }

        @Test
        @DisplayName("the Fujifilm model fills the image and inverts the brightness")
        void fujifilm() {
            var radius = 500.0;
            double[] distortion = {radius / 3, 0.5, 0.8, 1.0, -1, -2, -4};
            double[] vignetting = {radius / 3, 0.5, 0.8, 1.0, 90, 80, 50};
            var lens = VendorLens.fujifilm(distortion, null, vignetting, new double[]{0, 0, 800, 600}, 800, 600);
            assertNotNull(lens);
            assertEquals(radius, lens.radius, 1e-9);
            // the nearest border is 300 px (u = 0.6), where the displacement is -1.333%: the fill scale undoes it
            var scale = 1 / (1 - (1 + (0.6 - 0.5) / 0.3 * 1) / 100);
            assertEquals(scale, LensCorrection.evaluate(lens.distortionKnots, lens.distortion[1], 0), 1e-9);
            assertEquals(2, LensCorrection.evaluate(lens.vignetteKnots, lens.vignette, 1.0), 1e-9);
            assertEquals(1, LensCorrection.evaluate(lens.vignetteKnots, lens.vignette, 0), 1e-9);
        }
    }
}
