package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.raw.Cr3Writer;
import com.github.auties00.smalt.image.raw.DngBuilder;
import com.github.auties00.smalt.image.raw.DngEncoder;
import com.github.auties00.smalt.image.raw.DngEncoderOptions;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawImage;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.image.raw.TinyDng;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the dual pixel images of Canon CR3 files and of the two-sample DNG files Adobe DNG Converter writes from
 * them: synthetic CR3 files of {@link Cr3Writer} whose main image is the sum of two photodiode images and whose second
 * image is one of them, two-sample DNG files of {@link DngBuilder} against the equivalent one-sample files, and the
 * CC0 dual pixel sample of the Canon EOS R5 (skipped when the sample is not cached).
 */
class DualPixelTest {
    private static final RawDecoderOptions DUAL = RawDecoderOptions.builder().dualPixelImage(true).build();

    /**
     * A dual pixel CR3 file: the second image holds photodiode A, another of the same coding photodiode B, and the
     * main image their sum, A + B less one black level.
     */
    private static final class DualFile {
        final Cr3Writer main = new Cr3Writer();
        final Cr3Writer other = new Cr3Writer();
        final byte[] data;

        DualFile(int levels) {
            main.width = 64;
            main.height = 48;
            main.levels = levels;
            main.dualPixel = new Cr3Writer();
            main.dualPixel.width = 64;
            main.dualPixel.height = 48;
            main.dualPixel.levels = levels;
            main.dualPixel.seed = 2;
            main.dualPixel.ramp();
            other.width = 64;
            other.height = 48;
            other.seed = 3;
            other.ramp();
            main.samples = new int[64 * 48];
            for (var i = 0; i < main.samples.length; i++) {
                main.samples[i] = main.dualPixel.samples[i] + other.samples[(i * 7) % main.samples.length]
                        - main.black;
            }
            data = main.write();
        }
    }

    private static int[] plane(Sensor sensor) {
        var plane = new SensorPlane(sensor.width, sensor.height, 1, false, MediaLimits.DEFAULT);
        sensor.reader.read(plane, MediaLimits.DEFAULT);
        var values = new int[plane.integers.length];
        for (var i = 0; i < values.length; i++) {
            values[i] = plane.integers[i] & 0xFFFF;
        }
        return values;
    }

    private static byte[] pixels(Picture picture) {
        return ImageOps.toRgba(picture).pixels();
    }

    private static RawFile parse(byte[] data) {
        try (var source = MediaSource.of(data)) {
            return RawParser.parse(source, MediaLimits.DEFAULT);
        }
    }

    @Nested
    @DisplayName("CR3 files of dual pixel raw mode")
    class Cr3 {
        @ParameterizedTest(name = "wavelet levels {0}")
        @ValueSource(ints = {0, 3})
        @DisplayName("both images decode photosite for photosite, with the main image's areas and levels")
        void photosites(int levels) {
            var file = new DualFile(levels);
            var parsed = parse(file.data);
            assertTrue(parsed.dualPixel != null, "dual pixel image");
            assertArrayEquals(file.main.expected, plane(parsed.sensor), "main image");
            assertArrayEquals(file.main.dualPixel.expected, plane(parsed.dualPixel), "dual pixel image");
            assertArrayEquals(parsed.sensor.active(), parsed.dualPixel.active());
            assertArrayEquals(parsed.sensor.defaultCrop, parsed.dualPixel.defaultCrop);
            assertArrayEquals(parsed.sensor.black, parsed.dualPixel.black);
            assertArrayEquals(parsed.sensor.white, parsed.dualPixel.white);
            assertEquals(parsed.sensor.compression, parsed.dualPixel.compression);
        }

        @Test
        @DisplayName("the main image less the dual pixel image is the other photodiode's image, on one linear scale")
        void difference() {
            var file = new DualFile(0);
            var main = RawDecoder.decodeRaw(MediaSource.of(file.data));
            var dual = RawDecoder.decodeRaw(MediaSource.of(file.data), DUAL);
            assertEquals(main.width(), dual.width());
            assertEquals(main.height(), dual.height());
            assertEquals(main.cfaPattern(), dual.cfaPattern());
            assertArrayEquals(main.cameraNeutral(), dual.cameraNeutral());
            assertArrayEquals(main.balancedToXyz(), dual.balancedToXyz());
            // B alone, as a file of its own, gives the linear values the difference must reproduce
            var alone = new Cr3Writer();
            alone.width = 64;
            alone.height = 48;
            alone.samples = new int[64 * 48];
            for (var i = 0; i < alone.samples.length; i++) {
                alone.samples[i] = file.other.samples[(i * 7) % alone.samples.length];
            }
            var other = RawDecoder.decodeRaw(MediaSource.of(alone.write()));
            var differs = false;
            for (var i = 0; i < main.samples().length; i++) {
                var difference = (main.samples()[i] & 0xFFFF) - (dual.samples()[i] & 0xFFFF);
                assertEquals(other.samples()[i] & 0xFFFF, difference, 1, "photosite " + i);
                differs |= (dual.samples()[i] & 0xFFFF) != (main.samples()[i] & 0xFFFF);
            }
            assertTrue(differs, "the dual pixel image is the main image");
        }

        @Test
        @DisplayName("the file reports its dual pixel image, and its main image is the one a file without it decodes")
        void info() {
            var file = new DualFile(0);
            var info = RawDecoder.info(MediaSource.of(file.data));
            assertTrue(info.hasDualPixelImage());
            assertTrue(info.toString().contains("dualPixel=true"), info.toString());
            var plain = new Cr3Writer();
            plain.width = 64;
            plain.height = 48;
            plain.samples = file.main.samples.clone();
            var bytes = plain.write();
            assertFalse(RawDecoder.info(MediaSource.of(bytes)).hasDualPixelImage());
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(bytes)).samples(),
                    RawDecoder.decodeRaw(MediaSource.of(file.data)).samples());
            assertArrayEquals(pixels(RawDecoder.decode(MediaSource.of(bytes)).picture()),
                    pixels(RawDecoder.decode(MediaSource.of(file.data)).picture()));
        }

        @Test
        @DisplayName("the dual pixel image renders at the main image's size")
        void render() {
            var file = new DualFile(0);
            var main = RawDecoder.decode(MediaSource.of(file.data));
            var dual = RawDecoder.decode(MediaSource.of(file.data), DUAL);
            assertEquals(main.picture().width(), dual.picture().width());
            assertEquals(main.picture().height(), dual.picture().height());
            assertFalse(Arrays.equals(pixels(main.picture()), pixels(dual.picture())));
            var half = RawDecoder.decode(MediaSource.of(file.data), DUAL.toBuilder().minSize(16, 12)
                    .embeddedPreview(false).build());
            assertEquals(main.picture().width() / 2, half.picture().width());
        }

        @Test
        @DisplayName("a DNG file converted with the option holds the dual pixel image")
        void convert() {
            var file = new DualFile(0);
            var dual = DngEncoder.convert(MediaSource.of(file.data), DUAL, DngEncoderOptions.defaults());
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file.data), DUAL).samples(),
                    RawDecoder.decodeRaw(MediaSource.of(dual)).samples());
            var main = DngEncoder.convert(MediaSource.of(file.data));
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file.data)).samples(),
                    RawDecoder.decodeRaw(MediaSource.of(main)).samples());
        }

        @Test
        @DisplayName("a second image of another size or coding is no dual pixel image")
        void mismatched() {
            var smaller = new Cr3Writer();
            smaller.dualPixel = new Cr3Writer();
            smaller.dualPixel.width = 48;
            assertFalse(RawDecoder.info(MediaSource.of(smaller.write())).hasDualPixelImage());
            var lossy = new Cr3Writer();
            lossy.dualPixel = new Cr3Writer();
            lossy.dualPixel.levels = 3;
            assertFalse(RawDecoder.info(MediaSource.of(lossy.write())).hasDualPixelImage());
            var deeper = new Cr3Writer();
            deeper.dualPixel = new Cr3Writer();
            deeper.dualPixel.bits = 12;
            assertFalse(RawDecoder.info(MediaSource.of(deeper.write())).hasDualPixelImage());
        }

        @Test
        @DisplayName("a file cut in its dual pixel image still decodes its main image, and fails cleanly on the other")
        void truncated() {
            var file = new DualFile(0);
            var cut = Arrays.copyOf(file.data, file.main.dualOffset + file.main.dualLength / 2);
            assertTrue(RawDecoder.info(MediaSource.of(cut)).hasDualPixelImage());
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file.data)).samples(),
                    RawDecoder.decodeRaw(MediaSource.of(cut)).samples());
            assertThrows(MediaException.Malformed.class, () -> RawDecoder.decodeRaw(MediaSource.of(cut), DUAL));
        }

        @Test
        @DisplayName("frame 0 of a dual pixel image is that image; another frame is unsupported")
        void frames() {
            var file = new DualFile(0);
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file.data), DUAL).samples(),
                    RawDecoder.decodeRaw(MediaSource.of(file.data), DUAL.toBuilder().frame(0).build()).samples());
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(file.data),
                    DUAL.toBuilder().frame(1).build()));
        }
    }

    @Test
    @DisplayName("asking a file without a dual pixel image for one is unsupported")
    void absent() {
        var plain = new Cr3Writer().write();
        assertFalse(RawDecoder.info(MediaSource.of(plain)).hasDualPixelImage());
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(plain), DUAL));
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(plain), DUAL));
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(plain),
                DUAL.toBuilder().minSize(16, 16).build()));
        assertThrows(MediaException.Unsupported.class, () -> DngEncoder.convert(MediaSource.of(plain), DUAL,
                DngEncoderOptions.defaults()));
    }

    @Test
    @DisplayName("the option round-trips through the builder and takes part in equality")
    void options() {
        assertFalse(RawDecoderOptions.defaults().dualPixelImage());
        assertTrue(DUAL.dualPixelImage());
        assertEquals(DUAL, DUAL.toBuilder().build());
        assertNotEquals(RawDecoderOptions.defaults(), DUAL);
        assertNotEquals(RawDecoderOptions.defaults().hashCode(), DUAL.hashCode());
        assertTrue(DUAL.toString().contains("dualPixelImage=true"));
        assertFalse(DUAL.toBuilder().dualPixelImage(false).build().dualPixelImage());
    }

    @Nested
    @DisplayName("DNG files of two samples per photosite")
    class Dng {
        private static final int WIDTH = 24;
        private static final int HEIGHT = 16;

        private static long[] values(int sample) {
            var values = new long[WIDTH * HEIGHT];
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    values[y * WIDTH + x] = 520 + (x * 331 + y * 173 + sample * 977 + (x * y) % 13 * 40) % 7000;
                }
            }
            return values;
        }

        // black levels by row, column and sample; white levels by sample
        private static final double[] BLACK = {512, 510, 512, 511, 509, 508, 512, 512};
        private static final double[] WHITE = {15000, 14000};

        private static byte[] file(String make, int... samples) {
            return file(make, false, samples);
        }

        private static byte[] file(String make, boolean userCrop, int... samples) {
            return file(make, userCrop, null, samples);
        }

        private static byte[] file(String make, boolean userCrop, byte[] opcodes1, int... samples) {
            var builder = new DngBuilder();
            if (userCrop) {
                builder.first.rationals(51125, 0.25, 0.25, 0.75, 0.75);
            }
            if (opcodes1 != null) {
                builder.first.undefined(51008, opcodes1);
            }
            var depths = new int[samples.length];
            Arrays.fill(depths, 16);
            var values = new long[WIDTH * HEIGHT * samples.length];
            var black = new double[4 * samples.length];
            var white = new double[samples.length];
            for (var s = 0; s < samples.length; s++) {
                var plane = values(samples[s]);
                for (var i = 0; i < plane.length; i++) {
                    values[i * samples.length + s] = plane[i];
                }
                for (var cell = 0; cell < 4; cell++) {
                    black[cell * samples.length + s] = BLACK[cell * 2 + samples[s]];
                }
                white[s] = WHITE[samples[s]];
            }
            builder.first.bytes(50706, 1, 4, 0, 0).signedRationals(50721, 0.8, -0.1, -0.05, -0.3, 1.2, 0.1, 0, 0.1,
                            0.7).rationals(50728, 0.6, 1, 0.8).shorts(50778, 21).ascii(271, make)
                    .ascii(272, "EOS R5");
            builder.first.longs(254, 0).longs(256, WIDTH).longs(257, HEIGHT).shorts(258, depths).shorts(259, 1)
                    .shorts(277, samples.length).shorts(262, 32803).shorts(33421, 2, 2).bytes(33422, 0, 1, 1, 2)
                    .shorts(50713, 2, 2).rationals(50714, black).longs(50717, Arrays.stream(white)
                            .mapToLong(v -> (long) v).toArray())
                    .strips(HEIGHT, DngBuilder.words(values, 16, ByteOrder.LITTLE_ENDIAN));
            return builder.write();
        }

        @Test
        @DisplayName("a Canon file's samples are the main and the dual pixel image, as one-sample files of each")
        void canon() {
            var both = file("Canon", 0, 1);
            assertTrue(RawDecoder.info(MediaSource.of(both)).hasDualPixelImage());
            RawImage main = RawDecoder.decodeRaw(MediaSource.of(both));
            assertEquals(1, main.channels());
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file("Canon", 0))).samples(), main.samples());
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file("Canon", 1))).samples(),
                    RawDecoder.decodeRaw(MediaSource.of(both), DUAL).samples());
            assertArrayEquals(pixels(RawDecoder.decode(MediaSource.of(file("Canon", 0))).picture()),
                    pixels(RawDecoder.decode(MediaSource.of(both)).picture()));
        }

        // MapPolynomial y = 0.5 x over one sample of the whole image
        private static byte[] halve(int plane) {
            var params = TinyDng.Opcodes.area(0, 0, HEIGHT, WIDTH, plane, 1, 1, 1, 4 + 16);
            params.putInt(1).putDouble(0).putDouble(0.5);
            return new TinyDng.Opcodes().add(8, 0, params).build();
        }

        @Test
        @DisplayName("opcode list 1 applies to each sample before the split, and is skipped when opcodes are off")
        void opcodeList1() {
            var both = file("Canon", false, halve(1), 0, 1);
            var dual = RawDecoder.decodeRaw(MediaSource.of(both), DUAL).samples();
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file("Canon", false, halve(0), 1))).samples(), dual);
            assertFalse(Arrays.equals(RawDecoder.decodeRaw(MediaSource.of(file("Canon", 1))).samples(), dual),
                    "the opcode list changes the dual pixel image");
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file("Canon", 0))).samples(),
                    RawDecoder.decodeRaw(MediaSource.of(both)).samples(), "the opcode list leaves sample 0");
            var off = RawDecoderOptions.builder().opcodes(false).build();
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file("Canon", 1)), off).samples(),
                    RawDecoder.decodeRaw(MediaSource.of(both), off.toBuilder().dualPixelImage(true).build()).samples());
        }

        @Test
        @DisplayName("the dual pixel image takes the file's default user crop")
        void userCrop() {
            var cropped = RawDecoderOptions.builder().userCrop(true).build();
            var withCrop = file("Canon", true, 0, 1);
            var main = RawDecoder.decodeRaw(MediaSource.of(withCrop), cropped);
            var dual = RawDecoder.decodeRaw(MediaSource.of(withCrop), cropped.toBuilder().dualPixelImage(true).build());
            assertTrue(main.width() < WIDTH, "the user crop applies to the main image");
            assertEquals(main.width(), dual.width());
            assertEquals(main.height(), dual.height());
            assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(file("Canon", true, 1)), cropped).samples(),
                    dual.samples());
        }

        @Test
        @DisplayName("another maker's file of two samples per photosite merges them and has no dual pixel image")
        void otherMake() {
            var both = file("FUJIFILM", 0, 1);
            var info = RawDecoder.info(MediaSource.of(both));
            assertFalse(info.hasDualPixelImage());
            assertEquals(2, RawDecoder.decodeRaw(MediaSource.of(both)).channels());
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(both), DUAL));
        }
    }

    @Test
    @DisplayName("the EOS R5 dual pixel sample: one photodiode's image lies below the main image, at about half of it")
    void sample() {
        var data = RawSamples.CR3_EOS_R5_RAW_1_6_CROP_DUAL_PIXEL.fetch();
        assertTrue(RawDecoder.info(MediaSource.of(data)).hasDualPixelImage());
        var main = RawDecoder.decodeRaw(MediaSource.of(data));
        var dual = RawDecoder.decodeRaw(MediaSource.of(data), DUAL);
        assertEquals(main.width(), dual.width());
        assertEquals(main.height(), dual.height());
        long mainSum = 0;
        long dualSum = 0;
        long above = 0;
        for (var i = 0; i < main.samples().length; i++) {
            var m = main.samples()[i] & 0xFFFF;
            var d = dual.samples()[i] & 0xFFFF;
            mainSum += m;
            dualSum += d;
            // the other photodiode's image, main less dual, is never below black by more than the read noise
            if (d > m + 512) {
                above++;
            }
        }
        assertEquals(0, above, "photosites of the dual pixel image well above the main image");
        var share = (double) dualSum / mainSum;
        assertTrue(share > 0.35 && share < 0.65, "the dual pixel image holds " + share + " of the main image");
        // a small size hint is met from the camera's JPEG preview, which shows the main image only
        var hint = RawDecoderOptions.builder().minSize(160, 120).build();
        assertTrue(RawDecoder.decode(MediaSource.of(data), hint).colorProfile().isEmpty(), "main image preview");
        var hinted = RawDecoder.decode(MediaSource.of(data), hint.toBuilder().dualPixelImage(true).build());
        assertTrue(hinted.colorProfile().isPresent(), "the dual pixel image is rendered, not taken from a preview");
    }
}
