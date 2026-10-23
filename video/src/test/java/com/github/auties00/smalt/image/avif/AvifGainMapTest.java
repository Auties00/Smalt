package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AuxiliaryImageType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.gainmap.GainMapComputeOptions;
import com.github.auties00.smalt.image.gainmap.GainMapOptions;
import com.github.auties00.smalt.image.gainmap.GainMaps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AVIF gain maps: the {@code tmap} files of libavif's test data, including the variants libavif ignores
 * (missing brand, wrong {@code altr} order, unsupported versions) or rejects (zero gamma, extra metadata bytes of a
 * supported version), grids of color, alpha and gain map images, the auxiliary image listing, and files written by
 * {@link AvifEncoder} read back. The libavif comparisons are in {@link AvifGainMapDifferentialTest}.
 */
@DisplayName("AVIF gain maps")
class AvifGainMapTest {
    static byte[] vector(String name) {
        try {
            return Files.readAllBytes(Conformance.fetchListed("libavif-" + name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static RgbaPicture sdr(int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                picture.pixels()[at] = (byte) (255 * x / (width - 1));
                picture.pixels()[at + 1] = (byte) (255 * y / (height - 1));
                picture.pixels()[at + 2] = (byte) (128 + 100 * Math.sin(x * 0.2 + y * 0.1));
                picture.pixels()[at + 3] = (byte) 0xFF;
            }
        }
        return picture;
    }

    static double srgb(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static double pqToNits(double signal) {
        var m1 = 2610.0 / 16384;
        var m2 = 2523.0 / 4096 * 128;
        var c1 = 3424.0 / 4096;
        var c2 = 2413.0 / 4096 * 32;
        var c3 = 2392.0 / 4096 * 32;
        var p = Math.pow(signal, 1 / m2);
        return Math.pow(Math.max(p - c1, 0) / (c2 - c3 * p), 1 / m1) * 10000;
    }

    static double pq(double nits) {
        var m1 = 2610.0 / 16384;
        var m2 = 2523.0 / 4096 * 128;
        var c1 = 3424.0 / 4096;
        var c2 = 2413.0 / 4096 * 32;
        var c3 = 2392.0 / 4096 * 32;
        var p = Math.pow(Math.min(Math.max(nits / 10000, 0), 1), m1);
        return Math.pow((c1 + c2 * p) / (1 + c3 * p), m2);
    }

    static DecodedImage hdr(RgbaPicture base) {
        var picture = Rgba16Picture.allocate(base.width(), base.height(), false);
        for (var y = 0; y < base.height(); y++) {
            for (var x = 0; x < base.width(); x++) {
                var dx = (x - base.width() / 2.0) / base.width();
                var dy = (y - base.height() / 2.0) / base.height();
                var boost = 1 + 7 * Math.exp(-(dx * dx + dy * dy) * 8);
                for (var c = 0; c < 3; c++) {
                    var linear = srgb((base.pixels()[y * base.stride() + x * 4 + c] & 0xFF) / 255.0) * boost;
                    picture.pixels()[y * picture.stride() + x * 4 + c] = (short) Math.round(pq(linear * 203) * 65535);
                }
                picture.pixels()[y * picture.stride() + x * 4 + 3] = (short) 0xFFFF;
            }
        }
        var color = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT709,
                TransferCharacteristics.PQ).withContentLightLevel(new ContentLightLevel(1624, 300));
        return image(picture, Optional.of(ColorProfile.of(color)));
    }

    static DecodedImage image(Picture picture, Optional<ColorProfile> profile) {
        return new DecodedImage(picture, ImageFormat.AVIF, Orientation.NORMAL, picture.width(), picture.height(),
                Optional.empty(), Optional.empty(), Optional.empty(), profile);
    }

    static GainMap computed(RgbaPicture base, boolean multichannel) {
        return GainMaps.compute(image(base, Optional.empty()), hdr(base),
                GainMapComputeOptions.builder().multichannel(multichannel).build());
    }

    @Nested
    @DisplayName("libavif test files")
    class Libavif {
        @Test
        @DisplayName("reads the gain map of the Camera Raw conversion as the JPEG XMP gives it")
        void seine() {
            var data = vector("seine_sdr_gainmap_srgb.avif");
            var gainMap = AvifDecoder.decodeGainMap(MediaSource.of(data)).orElseThrow();
            var metadata = gainMap.metadata();
            assertTrue(metadata.isMultichannel());
            assertEquals(0, metadata.baseHeadroom());
            assertEquals(1.3, metadata.alternateHeadroom(), 1e-6);
            assertEquals(-0.256907, metadata.channel(0).minimum(), 1e-6);
            assertEquals(1.277969, metadata.channel(2).maximum(), 1e-6);
            assertEquals(0.941095, metadata.channel(1).gamma(), 1e-6);
            assertEquals(0.015625, metadata.channel(0).baseOffset(), 1e-9);
            var base = AvifDecoder.decode(MediaSource.of(data));
            assertTrue(gainMap.image().width() > 0 && gainMap.image().width() <= base.picture().width());
            var listed = AvifDecoder.auxiliaryImages(MediaSource.of(data));
            assertEquals(1, listed.size());
            assertEquals(AuxiliaryImageType.GAIN_MAP, listed.getFirst().type());
            assertEquals("tmap", listed.getFirst().identifier());
            assertEquals(gainMap.image().width(), listed.getFirst().width());
            var auxiliary = AvifDecoder.decodeAuxiliaryImage(MediaSource.of(data), 0);
            assertEquals(gainMap.image().width(), auxiliary.picture().width());
        }

        @Test
        @DisplayName("reads the gain map of an HDR base that dims to SDR")
        void hdrBase() {
            var metadata = AvifDecoder.decodeGainMap(MediaSource.of(vector("seine_hdr_gainmap_srgb.avif")))
                    .orElseThrow().metadata();
            assertTrue(metadata.isBaseBrighter());
            assertEquals(0, metadata.alternateHeadroom());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"seine_sdr_gainmap_notmapbrand.avif", "seine_hdr_gainmap_wrongaltr.avif",
                "unsupported_gainmap_version.avif", "unsupported_gainmap_minimum_version.avif"})
        @DisplayName("ignores the gain maps libavif ignores")
        void ignored(String name) {
            var data = vector(name);
            assertTrue(AvifDecoder.decodeGainMap(MediaSource.of(data)).isEmpty());
            AvifDecoder.decode(MediaSource.of(data));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"seine_sdr_gainmap_gammazero.avif", "supported_gainmap_writer_version_with_extra_bytes.avif"})
        @DisplayName("rejects the gain maps libavif rejects, still showing the base image")
        void rejected(String name) {
            var data = vector(name);
            assertThrows(MediaException.Malformed.class, () -> AvifDecoder.decodeGainMap(MediaSource.of(data)));
            AvifDecoder.decode(MediaSource.of(data));
        }

        @Test
        @DisplayName("reads the extra bytes of a later writer version")
        void laterWriter() {
            assertTrue(AvifDecoder.decodeGainMap(MediaSource.of(vector(
                    "unsupported_gainmap_writer_version_with_extra_bytes.avif"))).isPresent());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"color_grid_gainmap_different_grid.avif", "color_nogrid_alpha_nogrid_gainmap_grid.avif",
                "color_grid_alpha_grid_gainmap_nogrid.avif"})
        @DisplayName("reads grids of color, alpha and gain map images")
        void grids(String name) {
            var data = vector(name);
            var gainMap = AvifDecoder.decodeGainMap(MediaSource.of(data)).orElseThrow();
            var listed = AvifDecoder.auxiliaryImages(MediaSource.of(data));
            assertEquals(AuxiliaryImageType.ALPHA, listed.getFirst().type());
            assertEquals(AuxiliaryImageType.GAIN_MAP, listed.getLast().type());
            assertEquals(gainMap.image().width(), listed.getLast().width());
            assertEquals(gainMap.image().height(), listed.getLast().height());
            var base = AvifDecoder.decode(MediaSource.of(data));
            var rendered = GainMaps.apply(base, gainMap);
            assertEquals(base.picture().width(), rendered.picture().width());
            assertTrue(rendered.picture().hasAlpha());
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {
        @ParameterizedTest(name = "multichannel {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("writes a tmap item that reads back")
        void roundTrip(boolean multichannel) {
            var base = sdr(80, 48);
            var gainMap = computed(base, multichannel);
            var file = AvifEncoder.encode(base, gainMap, AvifEncoderOptions.lossless(),
                    AvifEncoderOptions.lossless());
            var heif = HeifFile.parse(MediaSource.of(file), MediaLimits.DEFAULT);
            assertTrue(heif.hasBrand("tmap"));
            var primary = heif.primaryItem();
            assertEquals("av01", primary.type());
            var tmap = heif.preferredAlternatives(primary.id()).getFirst();
            assertEquals("tmap", tmap.type());
            assertEquals(primary.id(), tmap.references("dimg")[0]);
            var read = AvifDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            var expected = gainMap.metadata();
            var actual = read.metadata();
            assertEquals(expected.isMultichannel(), actual.isMultichannel());
            assertEquals(expected.alternateHeadroom(), actual.alternateHeadroom(), 1e-8);
            assertEquals(expected.channel(1).maximum(), actual.channel(1).maximum(), 1e-8);
            assertEquals(ColorPrimaries.BT709, read.alternateProfile().orElseThrow().colorInfo().orElseThrow()
                    .primaries());
            assertEquals(TransferCharacteristics.PQ, read.alternateProfile().orElseThrow().colorInfo().orElseThrow()
                    .transfer());
            assertEquals(new ContentLightLevel(1624, 300), read.alternateLightLevel().orElseThrow());
            var gray = read.image() instanceof YuvPicture yuv && yuv.chromaFormat() == ChromaFormat.MONOCHROME;
            assertEquals(!multichannel, gray);
            var decoded = AvifDecoder.decode(MediaSource.of(file));
            var rendered = (Rgba16Picture) GainMaps.apply(decoded, read, GainMapOptions.builder()
                    .primaries(ColorPrimaries.BT709).build()).picture();
            // both images are coded losslessly, so the file renders as the source images do, to rounding
            var reference = (Rgba16Picture) GainMaps.apply(image(base, Optional.empty()), gainMap, GainMapOptions
                    .builder().primaries(ColorPrimaries.BT709).build()).picture();
            for (var i = 0; i < reference.pixels().length; i++) {
                assertTrue(Math.abs((reference.pixels()[i] & 0xFFFF) - (rendered.pixels()[i] & 0xFFFF)) <= 1,
                        "sample " + i);
            }
        }

        @Test
        @DisplayName("puts the orientation on the inputs of the tmap item, never on the tmap item itself")
        void orientation() {
            var base = sdr(48, 32);
            var gainMap = computed(base, false);
            var file = AvifEncoder.encode(base, gainMap, AvifEncoderOptions.builder()
                    .orientation(Orientation.TRANSVERSE).build(), AvifEncoderOptions.defaults());
            var heif = HeifFile.parse(MediaSource.of(file), MediaLimits.DEFAULT);
            var primary = heif.primaryItem();
            var tmap = heif.preferredAlternatives(primary.id()).getFirst();
            assertEquals("tmap", tmap.type());
            // libavif rejects a file whose tmap item carries irot, imir, clap or pasp
            assertNull(tmap.property(HeifProperty.Rotation.class));
            assertNull(tmap.property(HeifProperty.Mirror.class));
            var gainMapItem = heif.item(tmap.references("dimg")[1]);
            for (var item : new HeifItem[]{primary, gainMapItem}) {
                assertEquals(3, item.property(HeifProperty.Rotation.class).quarterTurns(), item.type());
                assertEquals(0, item.property(HeifProperty.Mirror.class).axis(), item.type());
            }
            assertEquals(Orientation.TRANSVERSE, AvifDecoder.decode(MediaSource.of(file)).orientation());
            var read = AvifDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            // the gain map stays in the coded orientation, as the base picture does
            assertEquals(48, read.image().width());
            assertEquals(32, read.image().height());
        }

        @Test
        @DisplayName("codes a lossless gain map exactly")
        void lossless() {
            var base = sdr(40, 24);
            var gainMap = computed(base, false);
            var file = AvifEncoder.encode(base, gainMap, AvifEncoderOptions.defaults(),
                    AvifEncoderOptions.lossless());
            var read = (YuvPicture) AvifDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow().image();
            var source = (YuvPicture) gainMap.image();
            for (var y = 0; y < 24; y++) {
                assertArrayEquals(Arrays.copyOfRange(source.y(), y * source.yStride(), y * source.yStride() + 40),
                        Arrays.copyOfRange(read.y(), y * read.yStride(), y * read.yStride() + 40), "row " + y);
            }
        }

        @Test
        @DisplayName("writes a gain map metadata that reads back the same")
        void metadata() {
            var base = sdr(16, 16);
            var gainMap = computed(base, true);
            var file = AvifEncoder.encode(base, gainMap);
            var read = AvifDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow().metadata();
            assertArrayEquals(gainMap.metadata().toByteArray(), read.toByteArray());
            assertFalse(AvifDecoder.auxiliaryImages(MediaSource.of(file)).isEmpty());
        }
    }

    @Test
    @DisplayName("never fails with an unchecked exception on damaged files")
    void damaged() {
        var file = AvifEncoder.encode(sdr(16, 16), computed(sdr(16, 16), false));
        var random = new Random(3);
        for (var i = 0; i < 300; i++) {
            var damaged = file.clone();
            for (var k = 0; k < 3; k++) {
                damaged[random.nextInt(Math.min(damaged.length, 600))] = (byte) random.nextInt(256);
            }
            try {
                AvifDecoder.decodeGainMap(MediaSource.of(damaged));
                AvifDecoder.auxiliaryImages(MediaSource.of(damaged));
            } catch (MediaException expected) {
                // rejected cleanly
            }
        }
    }
}
