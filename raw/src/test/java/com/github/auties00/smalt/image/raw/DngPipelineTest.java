package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the DNG pipeline on synthetic files written by {@link TinyDng}: each layout, compression,
 * predictor, level and opcode against values computed by hand.
 */
class DngPipelineTest {
    static RawImage raw(TinyDng dng) {
        return RawDecoder.decodeRaw(MediaSource.of(dng.write()), RawDecoderOptions.defaults());
    }

    static int[] unsigned(short[] samples) {
        var values = new int[samples.length];
        for (var i = 0; i < samples.length; i++) {
            values[i] = samples[i] & 0xFFFF;
        }
        return values;
    }

    @Nested
    @DisplayName("sensor data layouts")
    class Layouts {
        @ParameterizedTest(name = "compression {0}")
        @ValueSource(ints = {1, 7, 8})
        @DisplayName("every compression of 16-bit CFA data returns the stored samples")
        void compressions(int compression) {
            var dng = TinyDng.ramp(64, 48);
            dng.compression = compression;
            dng.white = 65535;
            assertArrayEquals(dng.samples, unsigned(raw(dng).samples()));
        }

        @ParameterizedTest(name = "predictor {0}")
        @ValueSource(ints = {2, 34892, 34893})
        @DisplayName("Deflate with a horizontal predictor returns the stored samples")
        void deflatePredictor(int predictor) {
            var dng = TinyDng.ramp(64, 48);
            dng.compression = 8;
            dng.predictor = predictor;
            dng.white = 65535;
            assertArrayEquals(dng.samples, unsigned(raw(dng).samples()));
        }

        @ParameterizedTest(name = "{0}-bit samples, predictor {1}")
        @CsvSource({"16, 3", "16, 34894", "16, 34895", "32, 2", "32, 34892", "32, 34893"})
        @DisplayName("a predictor of the other sample kind is rejected as unsupported")
        void mismatchedPredictor(int bits, int predictor) {
            var dng = TinyDng.ramp(16, 8);
            if (bits == 32) {
                dng.floats = new float[16 * 8];
            }
            dng.predictor = predictor;
            dng.compression = 8;
            var bytes = dng.write();
            assertFalse(RawDecoder.info(MediaSource.of(bytes)).isDecodable());
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
        }

        @Test
        @DisplayName("lossless JPEG with two interleaved components fills each row in order")
        void twoComponents() {
            var dng = TinyDng.ramp(64, 48);
            dng.compression = 7;
            dng.jpegComponents = 2;
            dng.white = 65535;
            assertArrayEquals(dng.samples, unsigned(raw(dng).samples()));
        }

        @ParameterizedTest(name = "NEAR {0}")
        @ValueSource(ints = {0, 2})
        @DisplayName("a JPEG-LS codestream in place of the lossless JPEG decodes the same way")
        void jpegLs(int near) {
            var dng = TinyDng.ramp(64, 48);
            dng.compression = 7;
            dng.white = 65535;
            var picture = com.github.auties00.smalt.image.Yuv16Picture.allocate(64, 48,
                    com.github.auties00.smalt.image.ChromaFormat.MONOCHROME, 16, false,
                    com.github.auties00.smalt.image.ColorInfo.JPEG);
            for (var i = 0; i < dng.samples.length; i++) {
                picture.y()[(i / 64) * picture.yStride() + i % 64] = (short) dng.samples[i];
            }
            dng.stripData = com.github.auties00.smalt.image.jpeg.JpegLsEncoder.encode(picture,
                    com.github.auties00.smalt.image.jpeg.JpegLsEncoderOptions.ofNearLossless(near));
            var decoded = unsigned(raw(dng).samples());
            for (var i = 0; i < decoded.length; i++) {
                assertEquals(dng.samples[i], decoded[i], near, "sample " + i);
            }
            if (near == 0) {
                assertArrayEquals(dng.samples, decoded);
            }
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {10, 12, 14})
        @DisplayName("bit-packed samples unpack most significant bit first and scale to the white level")
        void packed(int bits) {
            var dng = TinyDng.ramp(32, 16);
            var max = (1 << bits) - 1;
            for (var i = 0; i < dng.samples.length; i++) {
                dng.samples[i] &= max;
            }
            dng.bits = bits;
            var samples = unsigned(raw(dng).samples());
            for (var i = 0; i < samples.length; i++) {
                assertEquals(Math.round(dng.samples[i] / (float) max * 65535f), samples[i], 1, "sample " + i);
            }
        }

        @ParameterizedTest(name = "{0}-byte floats, predictor {1}")
        @ValueSource(strings = {"4,1", "4,3", "4,34894", "4,34895", "2,1", "2,3", "2,34894", "2,34895"})
        @DisplayName("floating-point data with and without the floating-point predictor")
        void floats(String setting) {
            var parts = setting.split(",");
            var dng = TinyDng.ramp(16, 8);
            dng.floats = new float[16 * 8];
            for (var i = 0; i < dng.floats.length; i++) {
                dng.floats[i] = (i % 97) / 96f;
            }
            dng.floatBytes = Integer.parseInt(parts[0]);
            dng.predictor = Integer.parseInt(parts[1]);
            dng.compression = 8;
            var samples = unsigned(raw(dng).samples());
            for (var i = 0; i < samples.length; i++) {
                assertEquals(dng.floats[i] * 65535f, samples[i], dng.floatBytes == 2 ? 40 : 1, "sample " + i);
            }
        }

        @Test
        @DisplayName("linear raw data has three samples per pixel and no CFA pattern")
        void linearRaw() {
            var dng = TinyDng.ramp(32, 16);
            dng.samplesPerPixel = 3;
            dng.samples = new int[32 * 16 * 3];
            for (var i = 0; i < dng.samples.length; i++) {
                dng.samples[i] = (i * 131) & 0xFFFF;
            }
            dng.white = 65535;
            var image = raw(dng);
            assertEquals(3, image.channels());
            assertTrue(image.cfaPattern().isEmpty());
            assertArrayEquals(dng.samples, unsigned(image.samples()));
        }
    }

    @Nested
    @DisplayName("levels")
    class Levels {
        @Test
        @DisplayName("the linearization table maps stored values before the levels")
        void linearization() {
            var dng = TinyDng.ramp(16, 8);
            dng.bits = 8;
            for (var i = 0; i < dng.samples.length; i++) {
                dng.samples[i] &= 0xFF;
            }
            dng.linearization = new int[256];
            for (var i = 0; i < 256; i++) {
                dng.linearization[i] = i * i;
            }
            dng.white = 65025;
            var samples = unsigned(raw(dng).samples());
            for (var i = 0; i < samples.length; i++) {
                var v = dng.samples[i];
                assertEquals(Math.round(v * v / 65025f * 65535f), samples[i], 1);
            }
        }

        @Test
        @DisplayName("a 2x2 black pattern with column and row deltas is subtracted per photosite")
        void blackPattern() {
            var dng = TinyDng.ramp(8, 6);
            java.util.Arrays.fill(dng.samples, 1000);
            dng.blackRepeat = new int[]{2, 2};
            dng.black = new double[]{100, 200, 300, 400};
            dng.blackDeltaH = new double[]{0, 10, 0, 10, 0, 10, 0, 10};
            dng.blackDeltaV = new double[]{0, 0, 5, 5, 0, 0};
            dng.white = 2000;
            var samples = unsigned(raw(dng).samples());
            for (var y = 0; y < 6; y++) {
                for (var x = 0; x < 8; x++) {
                    var black = dng.black[(y & 1) * 2 + (x & 1)] + dng.blackDeltaH[x] + dng.blackDeltaV[y];
                    var expected = Math.round((float) ((1000 - black) / (2000 - black)) * 65535f);
                    assertEquals(expected, samples[y * 8 + x], 1, "photosite " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("values below black and above white clip")
        void clipping() {
            var dng = TinyDng.ramp(4, 2);
            dng.samples = new int[]{0, 50, 100, 150, 200, 250, 300, 350};
            dng.black = new double[]{100};
            dng.white = 300;
            assertArrayEquals(new int[]{0, 0, 0, 16384, 32768, 49151, 65535, 65535}, unsigned(raw(dng).samples()));
        }
    }

    @Nested
    @DisplayName("geometry")
    class Geometry {
        @Test
        @DisplayName("the active area and default crop set the output size and shift the CFA pattern")
        void crop() {
            var dng = TinyDng.ramp(64, 48);
            dng.activeArea = new int[]{2, 4, 46, 62};
            dng.crop = new int[]{1, 3, 50, 40};
            var info = RawDecoder.info(MediaSource.of(dng.write()));
            assertEquals(50, info.width());
            assertEquals(40, info.height());
            assertEquals(CfaPattern.bayer("BGGR"), info.cfaPattern().orElseThrow());
            var image = RawDecoder.decode(MediaSource.of(dng.write()));
            assertEquals(50, image.picture().width());
            assertEquals(40, image.picture().height());
        }

        @Test
        @DisplayName("the CFA pattern starts at the active area's corner, even at an odd offset")
        void oddActiveArea() {
            var dng = TinyDng.ramp(64, 48);
            dng.activeArea = new int[]{1, 3, 47, 63};
            assertEquals(CfaPattern.bayer("RGGB"), RawDecoder.info(MediaSource.of(dng.write())).cfaPattern().orElseThrow());
            assertEquals(CfaPattern.bayer("RGGB"), raw(dng).cfaPattern().orElseThrow());
        }

        @Test
        @DisplayName("a default scale of 2 by 1 doubles the width")
        void defaultScale() {
            var dng = TinyDng.ramp(32, 32);
            dng.scale = new double[]{2, 1};
            var info = RawDecoder.info(MediaSource.of(dng.write()));
            assertEquals(64, info.width());
            assertEquals(32, info.height());
            assertEquals(64, RawDecoder.decode(MediaSource.of(dng.write())).picture().width());
        }

        @Test
        @DisplayName("the orientation is reported, not applied")
        void orientation() {
            var dng = TinyDng.ramp(32, 16);
            dng.orientation = 6;
            var image = RawDecoder.decode(MediaSource.of(dng.write()));
            assertEquals(Orientation.ofExif(6), image.orientation());
            assertEquals(32, image.picture().width());
        }
    }

    @Nested
    @DisplayName("opcodes")
    class Opcodes {
        static TinyDng flat(int value) {
            var dng = TinyDng.ramp(32, 24);
            java.util.Arrays.fill(dng.samples, value);
            dng.white = 65535;
            return dng;
        }

        @Test
        @DisplayName("GainMap multiplies by bilinearly interpolated gains")
        void gainMap() {
            var dng = flat(20000);
            var params = TinyDng.Opcodes.area(0, 0, 24, 32, 0, 1, 1, 1, 64);
            params.putInt(2).putInt(2).putDouble(1.0).putDouble(1.0).putDouble(0).putDouble(0).putInt(1);
            params.putFloat(1f).putFloat(2f).putFloat(1f).putFloat(2f);
            dng.opcodes2 = new TinyDng.Opcodes().add(9, 0, params).build();
            var samples = unsigned(raw(dng).samples());
            assertEquals(20000, samples[0], 1);
            var x = 16;
            assertEquals(Math.round(20000 * (1 + x / 32f)), samples[5 * 32 + x], 2);
        }

        @Test
        @DisplayName("ScalePerColumn and DeltaPerRow apply per line, clipped to the logical range")
        void perLine() {
            var dng = flat(32768);
            var scales = TinyDng.Opcodes.area(0, 0, 24, 32, 0, 1, 1, 1, 4 + 4 * 32);
            scales.putInt(32);
            for (var i = 0; i < 32; i++) {
                scales.putFloat(i < 16 ? 0.5f : 1.5f);
            }
            var deltas = TinyDng.Opcodes.area(0, 0, 24, 32, 0, 1, 1, 1, 4 + 4 * 24);
            deltas.putInt(24);
            for (var i = 0; i < 24; i++) {
                deltas.putFloat(i == 3 ? 0.25f : 0f);
            }
            dng.opcodes2 = new TinyDng.Opcodes().add(13, 0, scales).add(10, 0, deltas).build();
            var samples = unsigned(raw(dng).samples());
            assertEquals(16384, samples[0], 1);
            assertEquals(49152, samples[20], 1);
            assertEquals(Math.round((0.25f + 0.25f) * 65535), samples[3 * 32], 2);
            assertEquals(65535, samples[3 * 32 + 20]);
        }

        @Test
        @DisplayName("MapPolynomial maps normalized values")
        void polynomial() {
            var dng = flat(32768);
            var params = TinyDng.Opcodes.area(0, 0, 24, 32, 0, 1, 1, 1, 4 + 16);
            params.putInt(1).putDouble(0.1).putDouble(0.5);
            dng.opcodes2 = new TinyDng.Opcodes().add(8, 0, params).build();
            assertEquals(Math.round((0.1f + 0.5f * 32768 / 65535f) * 65535f), unsigned(raw(dng).samples())[7], 2);
        }

        @Test
        @DisplayName("FixBadPixelsConstant replaces marked photosites with same-color neighbors")
        void badPixels() {
            var dng = flat(1000);
            dng.samples[10 * 32 + 10] = 0;
            var params = ByteBuffer.allocate(8).putInt(0).putInt(0);
            dng.opcodes1 = new TinyDng.Opcodes().add(4, 0, params).build();
            dng.white = 2000;
            var samples = unsigned(raw(dng).samples());
            assertEquals(samples[10 * 32 + 12], samples[10 * 32 + 10]);
        }

        @Test
        @DisplayName("FixVignetteRadial leaves the center and brightens the corners")
        void vignette() {
            var dng = flat(16384);
            var params = ByteBuffer.allocate(56).putDouble(1).putDouble(0).putDouble(0).putDouble(0).putDouble(0)
                    .putDouble(0.5).putDouble(0.5);
            dng.opcodes3 = new TinyDng.Opcodes().add(3, 0, params).build();
            var options = RawDecoderOptions.linear();
            var picture = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), options).picture();
            var plain = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), options.toBuilder().opcodes(false).build())
                    .picture();
            var center = (12 * 32 + 16) * 4 + 1;
            assertEquals(plain.pixels()[center] & 0xFFFF, picture.pixels()[center] & 0xFFFF, 600);
            assertTrue((picture.pixels()[1] & 0xFFFF) > (plain.pixels()[1] & 0xFFFF) * 1.6);
        }

        @Test
        @DisplayName("an identity WarpRectilinear leaves the image unchanged")
        void identityWarp() {
            var dng = TinyDng.ramp(32, 24);
            var params = ByteBuffer.allocate(4 + 48 + 16).putInt(1).putDouble(1).putDouble(0).putDouble(0).putDouble(0)
                    .putDouble(0).putDouble(0).putDouble(0.5).putDouble(0.5);
            dng.opcodes3 = new TinyDng.Opcodes().add(1, 0, params).build();
            var options = RawDecoderOptions.linear().toBuilder().demosaic(RawDemosaic.BILINEAR).build();
            var warped = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), options).picture();
            var plain = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), options.toBuilder().opcodes(false).build())
                    .picture();
            for (var i = 0; i < warped.pixels().length; i++) {
                assertEquals(plain.pixels()[i] & 0xFFFF, warped.pixels()[i] & 0xFFFF, 2, "sample " + i);
            }
        }

        static ByteBuffer rectilinear2(double[] radial, double tangential0, double tangential1, boolean reciprocal) {
            var params = ByteBuffer.allocate(4 + 19 * 8 + 16 + 4).putInt(1);
            for (var k = 0; k < 15; k++) {
                params.putDouble(k < radial.length ? radial[k] : 0);
            }
            return params.putDouble(tangential0).putDouble(tangential1).putDouble(0).putDouble(1).putDouble(0.5)
                    .putDouble(0.5).putInt(reciprocal ? 1 : 0);
        }

        static void assertSameRendering(TinyDng dng, byte[] list, byte[] reference, int tolerance) {
            var options = RawDecoderOptions.linear().toBuilder().demosaic(RawDemosaic.BILINEAR).build();
            dng.opcodes3 = list;
            var actual = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), options).picture();
            dng.opcodes3 = reference;
            var expected = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), reference == null
                    ? options.toBuilder().opcodes(false).build() : options).picture();
            for (var i = 0; i < actual.pixels().length; i++) {
                assertEquals(expected.pixels()[i] & 0xFFFF, actual.pixels()[i] & 0xFFFF, tolerance, "sample " + i);
            }
        }

        @Test
        @DisplayName("a WarpFisheye whose polynomial is the series of tan leaves the image unchanged")
        void identityFisheye() {
            // t + t^3/3 + 2t^5/15 + 17t^7/315 is tan(t) to within 0.4% up to the corners (t = atan 1)
            var params = ByteBuffer.allocate(4 + 32 + 16).putInt(1).putDouble(1).putDouble(1 / 3.0).putDouble(2 / 15.0)
                    .putDouble(17 / 315.0).putDouble(0.5).putDouble(0.5);
            assertSameRendering(TinyDng.ramp(32, 24), new TinyDng.Opcodes().add(2, 0, params).build(), null, 400);
        }

        @Test
        @DisplayName("WarpRectilinear2 with even radial terms matches WarpRectilinear, and an identity leaves the image")
        void rectilinear2() {
            var dng = TinyDng.ramp(32, 24);
            assertSameRendering(dng, new TinyDng.Opcodes().add(14, 0, rectilinear2(new double[]{1}, 0, 0, false)).build(), null, 2);
            assertSameRendering(dng, new TinyDng.Opcodes().add(14, 0, rectilinear2(new double[]{1}, 0, 0, true)).build(), null, 2);
            var legacy = ByteBuffer.allocate(4 + 48 + 16).putInt(1).putDouble(1).putDouble(0.05).putDouble(-0.02)
                    .putDouble(0.01).putDouble(0.003).putDouble(-0.002).putDouble(0.5).putDouble(0.5);
            var extended = rectilinear2(new double[]{1, 0, 0.05, 0, -0.02, 0, 0.01}, 0.003, -0.002, false);
            assertSameRendering(dng, new TinyDng.Opcodes().add(14, 0, extended).build(),
                    new TinyDng.Opcodes().add(1, 0, legacy).build(), 2);
        }

        @Test
        @DisplayName("an optional WarpRectilinear2 makes the reader skip the warp right after it")
        void skipRule() {
            var dng = TinyDng.ramp(32, 24);
            var strong = ByteBuffer.allocate(4 + 48 + 16).putInt(1).putDouble(0.5).putDouble(0).putDouble(0)
                    .putDouble(0).putDouble(0).putDouble(0).putDouble(0.5).putDouble(0.5);
            var identity = rectilinear2(new double[]{1}, 0, 0, false);
            assertSameRendering(dng, new TinyDng.Opcodes().add(14, 1, identity).add(1, 0, strong).build(), null, 2);
        }

        @Test
        @DisplayName("TrimBounds in opcode list 3 shrinks the output to the trimmed rectangle")
        void trimBounds() {
            var dng = TinyDng.ramp(32, 24);
            dng.opcodes3 = new TinyDng.Opcodes().add(6, 0, ByteBuffer.allocate(16).putInt(2).putInt(4).putInt(20).putInt(30)).build();
            var options = RawDecoderOptions.linear().toBuilder().demosaic(RawDemosaic.BILINEAR).build();
            var trimmed = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), options).picture();
            assertEquals(26, trimmed.width());
            assertEquals(18, trimmed.height());
            assertEquals(26, RawDecoder.info(MediaSource.of(dng.write())).width());
            var plain = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), options.toBuilder().opcodes(false).build())
                    .picture();
            assertEquals(32, plain.width());
            for (var row = 0; row < 18; row++) {
                for (var column = 0; column < 26; column++) {
                    var expected = plain.pixels()[((row + 2) * 32 + column + 4) * 4 + 1] & 0xFFFF;
                    assertEquals(expected, trimmed.pixels()[(row * 26 + column) * 4 + 1] & 0xFFFF, 2);
                }
            }
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {
        @Test
        @DisplayName("Images recognizes and decodes a DNG to 8-bit sRGB by default and 16-bit on request")
        void registry() {
            var bytes = TinyDng.ramp(32, 24).write();
            assertEquals(ImageFormat.DNG, Images.detect(MediaSource.of(bytes)).orElseThrow());
            var image = Images.decode(MediaSource.of(bytes));
            assertEquals(ImageFormat.DNG, image.format());
            assertInstanceOf(RgbaPicture.class, image.picture());
            assertTrue(image.colorProfile().orElseThrow().isSrgb());
            var deep = Images.decode(MediaSource.of(bytes), com.github.auties00.smalt.image.ImageDecoderOptions.builder()
                    .highBitDepth(true).build());
            assertInstanceOf(Rgba16Picture.class, deep.picture());
        }

        @Test
        @DisplayName("a neutral flat field renders gray in every color space and with both demosaics")
        void neutral() {
            var dng = TinyDng.ramp(32, 24);
            java.util.Arrays.fill(dng.samples, 20000);
            dng.white = 65535;
            for (var space : RawColorSpace.values()) {
                for (var demosaic : RawDemosaic.values()) {
                    var options = RawDecoderOptions.builder().colorSpace(space).demosaic(demosaic).build();
                    var picture = (RgbaPicture) RawDecoder.decode(MediaSource.of(dng.write()), options).picture();
                    var p = picture.pixels();
                    var at = (12 * 32 + 16) * 4;
                    assertEquals(p[at] & 0xFF, p[at + 1] & 0xFF, 1, space + " " + demosaic);
                    assertEquals(p[at + 1] & 0xFF, p[at + 2] & 0xFF, 1, space + " " + demosaic);
                }
            }
        }

        @Test
        @DisplayName("an exposure of +1 stop doubles linear output")
        void exposure() {
            var dng = TinyDng.ramp(16, 16);
            java.util.Arrays.fill(dng.samples, 10000);
            dng.white = 65535;
            var options = RawDecoderOptions.linear();
            var base = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), options).picture();
            var bright = (Rgba16Picture) RawDecoder.decode(MediaSource.of(dng.write()), options.toBuilder().exposure(1).build())
                    .picture();
            assertEquals(2 * (base.pixels()[100] & 0xFFFF), bright.pixels()[100] & 0xFFFF, 4);
        }

        @Test
        @DisplayName("a size hint at most half the size renders at half size")
        void halfSize() {
            var bytes = TinyDng.ramp(64, 48).write();
            var image = RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().minSize(30, 20).build());
            assertEquals(32, image.picture().width());
            assertEquals(64, image.sourceWidth());
        }

        @Test
        @DisplayName("a color matrix override must be a non-singular 3x3 matrix")
        void matrixOverride() {
            assertThrows(IllegalArgumentException.class, () -> RawDecoderOptions.builder().colorMatrix(new double[8]).build());
            assertThrows(IllegalArgumentException.class, () -> RawDecoderOptions.builder().colorMatrix(new double[9]).build());
            var options = RawDecoderOptions.builder().colorMatrix(new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1}).build();
            assertFalse(options.equals(RawDecoderOptions.defaults()));
            assertEquals(32, RawDecoder.decode(MediaSource.of(TinyDng.ramp(32, 24).write()), options).picture().width());
        }
    }

    @Nested
    @DisplayName("four-color sensors")
    class FourColors {
        // a cyan, yellow, green and magenta camera built from an sRGB-like one: G = g, M = (r + b) / 2,
        // C = (g + b) / 2, Y = (r + g) / 2, each row scaled differently
        static final double[] RGB = {3.2406, -1.5372, -0.4986, -0.9689, 1.8758, 0.0415, 0.0557, -0.2040, 1.0570};

        static double[] matrix() {
            var scales = new double[]{0.8, 0.6, 0.9, 1.0};
            var rows = new double[][]{
                    {RGB[3], RGB[4], RGB[5]},
                    {(RGB[0] + RGB[6]) / 2, (RGB[1] + RGB[7]) / 2, (RGB[2] + RGB[8]) / 2},
                    {(RGB[3] + RGB[6]) / 2, (RGB[4] + RGB[7]) / 2, (RGB[5] + RGB[8]) / 2},
                    {(RGB[0] + RGB[3]) / 2, (RGB[1] + RGB[4]) / 2, (RGB[2] + RGB[5]) / 2}};
            var matrix = new double[12];
            for (var row = 0; row < 4; row++) {
                for (var column = 0; column < 3; column++) {
                    matrix[row * 3 + column] = rows[row][column] * scales[row];
                }
            }
            return matrix;
        }

        // a flat field of the camera's response to the D65 white, planes G, M, C, Y in a GMYC pattern
        static TinyDng flat(boolean planes) {
            var matrix = matrix();
            var white = new double[]{0.95047, 1, 1.08883};
            var neutral = new double[4];
            for (var row = 0; row < 4; row++) {
                neutral[row] = matrix[row * 3] * white[0] + matrix[row * 3 + 1] * white[1] + matrix[row * 3 + 2] * white[2];
            }
            var dng = TinyDng.ramp(32, 24);
            dng.cfa = "GMYC";
            dng.planeColors = planes ? new int[]{1, 4, 3, 5} : null;
            dng.colorMatrix = matrix;
            dng.neutral = neutral;
            dng.white = 65535;
            var plane = new int[]{0, 1, 3, 2};
            for (var y = 0; y < dng.height; y++) {
                for (var x = 0; x < dng.width; x++) {
                    dng.samples[y * dng.width + x] = (int) Math.round(20000 * neutral[plane[(y % 2) * 2 + x % 2]]);
                }
            }
            return dng;
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CLIP", "BLEND"})
        @DisplayName("a neutral flat field renders gray with either highlight treatment and demosaic")
        void neutral(String highlights) {
            for (var demosaic : RawDemosaic.values()) {
                var options = RawDecoderOptions.builder().highlights(RawHighlights.valueOf(highlights)).demosaic(demosaic)
                        .build();
                var picture = (RgbaPicture) RawDecoder.decode(MediaSource.of(flat(true).write()), options).picture();
                var p = picture.pixels();
                var at = (12 * 32 + 16) * 4;
                assertEquals(p[at] & 0xFF, p[at + 1] & 0xFF, 1, demosaic.toString());
                assertEquals(p[at + 1] & 0xFF, p[at + 2] & 0xFF, 1, demosaic.toString());
                assertTrue((p[at] & 0xFF) > 40, "rendered too dark");
            }
        }

        @Test
        @DisplayName("the sensor image has a neutral of four components and a 3x4 matrix")
        void sensorImage() {
            var image = raw(flat(true));
            assertEquals(4, image.cameraNeutral().length);
            assertEquals(12, image.balancedToXyz().length);
            assertEquals(CfaPattern.bayer("GMYC"), image.cfaPattern().orElseThrow());
        }

        @Test
        @DisplayName("a temperature white balance renders, channel multipliers and a matrix override are refused")
        void whiteBalance() {
            var bytes = flat(true).write();
            assertEquals(32, RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder()
                    .whiteBalance(RawWhiteBalance.temperature(3200)).build()).picture().width());
            assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(bytes),
                    RawDecoderOptions.builder().whiteBalance(RawWhiteBalance.multipliers(2, 1, 1.5)).build()));
            assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(bytes),
                    RawDecoderOptions.builder().colorMatrix(new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1}).build()));
        }

        @Test
        @DisplayName("a four-color pattern without its color planes is refused")
        void withoutPlanes() {
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(flat(false).write())));
        }

        @Test
        @DisplayName("the sensor image of a four-color pattern without its color planes is refused before it is linearized")
        void withoutPlanesBeforeLinearizing() {
            // room for the 16-bit sensor plane (1536 bytes) but not for its linear float copy (3072 bytes)
            var limits = MediaLimits.builder().maxBytes(2500).build();
            var options = RawDecoderOptions.builder().limits(limits).build();
            assertThrows(MediaException.Unsupported.class,
                    () -> RawDecoder.decodeRaw(MediaSource.of(flat(false).write()), options));
        }
    }
}
